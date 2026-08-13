package eu.darken.amply.stats.core

import android.os.BatteryManager
import eu.darken.amply.battery.core.BatteryReadout
import kotlin.math.abs

/**
 * Pure conversion of battery voltage × current into a battery-terminal power magnitude, in
 * milliwatts. This is power at the battery, not charger/input power, and the magnitude alone does
 * not encode direction (charge vs discharge) — the caller derives direction from plug/charging state.
 *
 * `mV × µA = 10⁻⁹ W = 10⁻⁶ mW`, so `mW = mV × |µA| / 1_000_000`, computed in [Long] to avoid the
 * `Int` overflow that `4300 mV × 3_000_000 µA` would hit. Values outside a plausible phone/tablet
 * range are rejected as `null` because some OEM firmwares report current in the wrong unit (mA, or
 * deci-units) and would otherwise poison the session average.
 */
object StatsPowerCalculator {

    /** 250 W — generously above any phone/tablet charger; anything larger is a bad OEM reading. */
    const val MAX_PLAUSIBLE_MILLIWATTS = 250_000

    /** AOSP's assumed charging voltage when a charger advertises a current but no voltage. */
    private const val DEFAULT_CHARGING_VOLTAGE_MICROVOLTS = 5_000_000

    fun milliwatts(voltageMillivolts: Int?, currentNowMicroamps: Int?): Int? {
        if (voltageMillivolts == null || currentNowMicroamps == null) return null
        if (voltageMillivolts <= 0) return null
        val mw = voltageMillivolts.toLong() * abs(currentNowMicroamps.toLong()) / 1_000_000L
        if (mw < 0 || mw > MAX_PLAUSIBLE_MILLIWATTS) return null
        return mw.toInt()
    }

    /**
     * [milliwatts], but `null` unless the battery is actually taking charge — the single gate every
     * caller must go through before showing or storing a figure as *charge* power.
     *
     * [milliwatts] is unsigned, so a device drawing more than its charger supplies produces the same
     * positive number as one charging at that rate; without this gate that draw is indistinguishable
     * from charge power in a curve, a peak, or a headline. Direction cannot be recovered from the
     * current's sign either — that sign is OEM-defined (see [eu.darken.amply.battery.core.BatteryReadout]),
     * so [batteryStatus] is the only trustworthy signal.
     *
     * Requiring [plugged] as well as `BATTERY_STATUS_CHARGING` also drops the reading at a protection
     * hold and at full, where the number is noise rather than a charge rate.
     */
    fun chargeMilliwatts(
        batteryStatus: Int?,
        plugged: Boolean,
        voltageMillivolts: Int?,
        currentNowMicroamps: Int?,
    ): Int? {
        if (!plugged) return null
        if (batteryStatus != BatteryManager.BATTERY_STATUS_CHARGING) return null
        return milliwatts(voltageMillivolts, currentNowMicroamps)
    }

    /** [chargeMilliwatts] straight off a readout; `null` readout (nothing observed) yields `null`. */
    fun chargeMilliwatts(readout: BatteryReadout?): Int? {
        if (readout == null) return null
        return chargeMilliwatts(
            batteryStatus = readout.status,
            plugged = readout.onCharger,
            voltageMillivolts = readout.voltageMillivolts,
            currentNowMicroamps = readout.currentNowMicroamps,
        )
    }

    /**
     * What the connected charger *advertises* it can deliver, in milliwatts — not a measurement, and
     * never a stand-in for [chargeMilliwatts].
     *
     * A missing/non-positive voltage falls back to 5 V, matching AOSP's own `BatteryStatus`: chargers
     * that report a current but no voltage are USB-spec 5 V supplies, and dropping the figure entirely
     * would hide the more commonly reported half of the pair.
     */
    fun advertisedMaxMilliwatts(maxCurrentMicroamps: Int?, maxVoltageMicrovolts: Int?): Int? {
        if (maxCurrentMicroamps == null || maxCurrentMicroamps <= 0) return null
        val microvolts = maxVoltageMicrovolts?.takeIf { it > 0 } ?: DEFAULT_CHARGING_VOLTAGE_MICROVOLTS
        val mw = (maxCurrentMicroamps.toLong() / 1000L) * (microvolts.toLong() / 1000L) / 1000L
        if (mw <= 0 || mw > MAX_PLAUSIBLE_MILLIWATTS) return null
        return mw.toInt()
    }

    fun advertisedMaxMilliwatts(readout: BatteryReadout?): Int? {
        if (readout == null) return null
        return advertisedMaxMilliwatts(
            maxCurrentMicroamps = readout.maxChargingCurrentMicroamps,
            maxVoltageMicrovolts = readout.maxChargingVoltageMicrovolts,
        )
    }
}
