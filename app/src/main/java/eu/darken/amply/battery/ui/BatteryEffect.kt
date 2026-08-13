package eu.darken.amply.battery.ui

import android.os.BatteryManager
import androidx.annotation.StringRes
import eu.darken.amply.R
import eu.darken.amply.battery.core.BatteryReadout
import eu.darken.amply.stats.core.StatsPowerCalculator

/**
 * What the battery is observably doing, for the dashboard hero's one explanatory line.
 *
 * Derived from [BatteryReadout] **alone** — never from the configured policy. The hero's title
 * already states the policy, and that title is frequently only a *last-requested* claim (Android
 * blocks third-party reads of the hidden Pixel values). If this line also came from the policy it
 * would restate the same unverified claim as though it were an observation. Reading it straight off
 * the public battery broadcast makes it independently true.
 *
 * Each variant carries the observed [percent] rather than a literal, so nothing here invents a
 * number the platform did not report; `null` renders the shared "Not reported" placeholder.
 */
sealed interface BatteryEffect {

    val percent: Int?

    /** External power is present and the battery is taking charge. */
    data class Charging(override val percent: Int?) : BatteryEffect

    /**
     * External power is present and the battery is *positively reported* as not taking charge.
     * Deliberately *not* named "paused": `BATTERY_STATUS_NOT_CHARGING` is also what a device reports
     * when it is too hot, on a weak supply, or faulty. Attributing it to a protection policy would be
     * a guess.
     */
    data class ConnectedNotCharging(override val percent: Int?) : BatteryEffect

    /**
     * External power is present and the charge status is unknown or unreported. Distinct from
     * [ConnectedNotCharging] because "connected" and "not charging" are two separate observations:
     * a device that reports a plug but no usable status has told us the first and not the second, and
     * folding it into "not charging" would invent the missing half.
     */
    data class Connected(override val percent: Int?) : BatteryEffect

    /** External power is present and the battery reports itself full — at whatever level it reports. */
    data class Full(override val percent: Int?) : BatteryEffect

    /** No external power reported. */
    data class OnBattery(override val percent: Int?) : BatteryEffect

    /**
     * The platform did not report a plug state at all, so neither "connected" nor "on battery" can be
     * claimed. A null `plugged` is *unknown*, not zero — [BatteryReadout.onCharger] collapses it to
     * false, which is the right conservative answer for "may we act", but the wrong one for "what do
     * we tell the user is happening".
     */
    data object Unknown : BatteryEffect {
        override val percent: Int? get() = null
    }

    companion object {
        fun from(readout: BatteryReadout?): BatteryEffect {
            val data = readout ?: return Unknown
            val level = data.levelPercent
            return when {
                data.plugged == null -> Unknown
                !data.onCharger -> OnBattery(level)
                data.status == BatteryManager.BATTERY_STATUS_FULL -> Full(level)
                data.status == BatteryManager.BATTERY_STATUS_CHARGING -> Charging(level)
                // Both of these are the device positively reporting that the battery is not gaining
                // charge — a limit hold, a thermal pause, or a load exceeding the supply.
                data.status == BatteryManager.BATTERY_STATUS_NOT_CHARGING ||
                    data.status == BatteryManager.BATTERY_STATUS_DISCHARGING -> ConnectedNotCharging(level)
                // UNKNOWN, absent, or a future constant: we know there is power and nothing more.
                else -> Connected(level)
            }
        }
    }
}

/**
 * Charge power for a live reading line, or `null` when no figure may be shown.
 *
 * Delegates to [StatsPowerCalculator.chargeMilliwatts] rather than re-deriving the rule, so the
 * live headline and the recorded curve/peak/average can never disagree about what counts as charge
 * power.
 */
fun chargePowerMilliwatts(readout: BatteryReadout): Int? = StatsPowerCalculator.chargeMilliwatts(readout)

/**
 * What to show in place of a withheld charge power, as a string resource.
 *
 * "Not charging" is only used where the battery *positively* reported that it isn't taking charge
 * (unplugged, held at a limit, full). Everywhere else the figure is missing rather than zero — a
 * connected device with no usable status has not told us it isn't charging — so it falls back to the
 * shared "Not reported".
 */
@StringRes
fun chargePowerFallbackRes(effect: BatteryEffect): Int = when (effect) {
    is BatteryEffect.OnBattery,
    is BatteryEffect.ConnectedNotCharging,
    is BatteryEffect.Full,
    -> R.string.battery_value_not_charging

    is BatteryEffect.Charging,
    is BatteryEffect.Connected,
    BatteryEffect.Unknown,
    -> R.string.battery_value_not_reported
}
