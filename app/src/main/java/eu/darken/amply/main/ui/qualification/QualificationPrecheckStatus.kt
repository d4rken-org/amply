package eu.darken.amply.main.ui.qualification

import android.os.BatteryManager
import eu.darken.amply.battery.core.BatteryReadout
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
 * Whether the battery is actually taking charge, or `null` when the readout carries no plug/status
 * information at all ([BatteryReadout.UNKNOWN]) — claiming "not charging" from an empty readout would
 * be an assertion about the device rather than about what was observed.
 */
private fun BatteryReadout.chargingOrNull(): Boolean? {
    if (plugged == null && status == null) return null
    return onCharger && status == BatteryManager.BATTERY_STATUS_CHARGING
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
    val chargeNeeded = (targetPercent - level) / 100.0 * fullCapacity
    val minutes = chargeNeeded / abs(currentMicroamps.toDouble()) * 60.0
    // Non-finite (zero current), non-positive (zero/negative counter) or absurd figures are a broken
    // reading, not a long wait.
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
