package eu.darken.amply.stats.core

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

    fun milliwatts(voltageMillivolts: Int?, currentNowMicroamps: Int?): Int? {
        if (voltageMillivolts == null || currentNowMicroamps == null) return null
        if (voltageMillivolts <= 0) return null
        val mw = voltageMillivolts.toLong() * abs(currentNowMicroamps.toLong()) / 1_000_000L
        if (mw < 0 || mw > MAX_PLAUSIBLE_MILLIWATTS) return null
        return mw.toInt()
    }
}
