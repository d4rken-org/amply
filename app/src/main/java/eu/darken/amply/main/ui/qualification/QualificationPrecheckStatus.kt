package eu.darken.amply.main.ui.qualification

import android.os.BatteryManager
import eu.darken.amply.battery.core.BatteryReadout
import eu.darken.amply.charging.core.qualification.QualificationProtocol
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The numbers behind the pre-check's own verdict: where the battery is, where it has to be, whether
 * it is moving, and roughly how long that takes.
 *
 * Every field is nullable and absent rather than guessed — a screen that says "too low" without a
 * figure is unhelpful, but one that invents a figure is worse.
 */
data class PrecheckStatusUi(
    val currentPercent: Int? = null,
    /** The level a run needs, when the block is a level block at all. Null otherwise. */
    val requiredPercent: Int? = null,
    /** Null means "nothing observed yet", not "not charging". */
    val charging: Boolean? = null,
    val estimatedMinutes: Int? = null,
)

/** Assemble the pre-check block from one battery readout plus the level the eligibility rule wants. */
internal fun precheckStatus(readout: BatteryReadout, requiredPercent: Int?): PrecheckStatusUi = PrecheckStatusUi(
    currentPercent = readout.levelPercent,
    requiredPercent = requiredPercent,
    charging = readout.chargingOrNull(),
    estimatedMinutes = requiredPercent?.let { estimateMinutesToPercent(readout, it) },
)

/**
 * Whether the battery is actually taking charge — a genuine tri-state, because "not charging" is a
 * claim about the device and the block only makes claims it observed.
 *
 * `true` needs both halves to agree: something plugged in **and** [BatteryManager.BATTERY_STATUS_CHARGING].
 * `false` needs only one observed negative — nothing plugged in, or a status of discharging, not
 * charging, or full — since either settles it on its own. Everything else is `null`: a missing half of
 * the positive pair, an unrecognised status, and [BatteryManager.BATTERY_STATUS_UNKNOWN], which is a
 * valid thing for the platform to report and says nothing either way.
 */
private fun BatteryReadout.chargingOrNull(): Boolean? {
    if (plugged == 0) return false
    if (status == BatteryManager.BATTERY_STATUS_DISCHARGING ||
        status == BatteryManager.BATTERY_STATUS_NOT_CHARGING ||
        status == BatteryManager.BATTERY_STATUS_FULL
    ) {
        return false
    }
    if (plugged == null || status == null) return null
    return if (status == BatteryManager.BATTERY_STATUS_CHARGING) true else null
}

/**
 * Minutes until the battery reaches [targetPercent] at the currently reported rate, or `null` when
 * that cannot be derived.
 *
 * The direction gate is `onCharger` **and** `BATTERY_STATUS_CHARGING`, for the reason
 * [eu.darken.amply.stats.core.StatsPowerCalculator.chargeMilliwatts] documents: the current's sign is
 * OEM-defined, so only the status says whether charge is going in. Requiring both also drops the
 * reading at a protection hold, where the number is noise rather than a rate.
 *
 * Everything else is arithmetic on the reported charge counter: the implied full capacity is
 * `counter / (level / 100)`, the charge still needed is that scaled by the level gap, and the time is
 * that divided by the reported current. It is an *instantaneous-rate extrapolation* on a curve that is
 * not linear, which is why callers render it in coarse buckets ([etaBucket]) rather than to the minute.
 */
internal fun estimateMinutesToPercent(readout: BatteryReadout, targetPercent: Int): Int? {
    if (!readout.onCharger) return null
    if (readout.status != BatteryManager.BATTERY_STATUS_CHARGING) return null
    val level = readout.levelPercent ?: return null
    val counterMicroampHours = readout.chargeCounterMicroampHours ?: return null
    val currentMicroamps = readout.currentNowMicroamps ?: return null
    // Outside 1..99 the counter-to-capacity division is either undefined (0) or tells nothing useful.
    if (level !in 1..99) return null
    if (targetPercent <= level) return null

    val fullCapacity = counterMicroampHours / (level / 100.0)
    // The same decision [eu.darken.amply.charging.core.qualification.QualificationRunEngine.resolveSignal]
    // makes about the same input: below this the counter is not believable as microamp-hours, so it is
    // not a rate signal here either. A counter reported in milli-units against a correctly scaled
    // current otherwise yields a confident *short* estimate, which the ceiling below cannot catch — it
    // only rejects a wait that is implausibly long.
    if (fullCapacity < QualificationProtocol.MIN_PLAUSIBLE_FULL_MICROAMP_HOURS) return null
    val chargeNeeded = (targetPercent - level) / 100.0 * fullCapacity
    val minutes = chargeNeeded / abs(currentMicroamps.toDouble()) * 60.0
    // Non-finite (zero current) or absurd figures are a broken reading, not a long wait. The
    // non-positive arm is unreachable once the capacity above is plausible, and stays as a floor.
    if (!minutes.isFinite() || minutes <= 0.0 || minutes > MAX_PLAUSIBLE_MINUTES) return null
    return minutes.roundToInt()
}

/** 24 hours. Anything longer is a misreported current, not a charge time worth showing. */
private const val MAX_PLAUSIBLE_MINUTES = 24 * 60.0

/**
 * How an estimate may be *spoken*. Deliberately coarse: the input is an instantaneous-rate
 * extrapolation across a charge curve that flattens near the top, so "43 minutes" would claim a
 * precision the arithmetic does not have. Round tens below three quarters of an hour, then two
 * hour-shaped buckets — enough to decide whether to wait or come back later, which is the only
 * decision this figure supports.
 */
internal sealed interface EtaBucket {
    data class Minutes(val minutes: Int) : EtaBucket
    data object AboutAnHour : EtaBucket
    data object OverAnHour : EtaBucket
}

internal fun etaBucket(minutes: Int): EtaBucket = when {
    minutes < 45 -> EtaBucket.Minutes((((minutes + 5) / 10) * 10).coerceAtLeast(10))
    minutes <= 75 -> EtaBucket.AboutAnHour
    else -> EtaBucket.OverAnHour
}
