package eu.darken.amply.battery.core

import android.os.BatteryManager

/**
 * Pure mapping from raw battery extras/properties to a [BatteryReadout]. Kept Android-free (only
 * the [BatteryManager] int constants) so it is directly JVM-unit-testable.
 *
 * Convention: callers pass the platform's own "unavailable" markers unchanged and this factory
 * normalizes them to `null` — [Int.MIN_VALUE] (the documented [BatteryManager.getIntProperty]
 * sentinel), the caller's [ABSENT] marker for a missing intent extra, and out-of-range values.
 */
object BatteryReadoutFactory {

    /** Sentinel a caller passes when a sticky-intent extra is entirely absent. */
    const val ABSENT = Int.MIN_VALUE

    @Suppress("LongParameterList")
    fun build(
        level: Int = ABSENT,
        scale: Int = ABSENT,
        status: Int = ABSENT,
        chargingStatus: Int = ABSENT,
        plugged: Int = ABSENT,
        health: Int = ABSENT,
        technology: String? = null,
        temperatureTenths: Int = ABSENT,
        voltageMillivolts: Int = ABSENT,
        currentNowMicroamps: Int = ABSENT,
        chargeCounterMicroampHours: Int = ABSENT,
        cycleCount: Int = ABSENT,
    ): BatteryReadout = BatteryReadout(
        levelPercent = percentOf(level, scale),
        status = status.orNull(),
        chargingStatus = chargingStatus.orNull(),
        plugged = plugged.orNull(),
        health = health.orNull(),
        technology = technology?.trim()?.ifEmpty { null },
        temperatureTenthsC = temperatureTenths.orNull(),
        voltageMillivolts = voltageMillivolts.orNull(),
        // Current is signed; only the MIN_VALUE/absent sentinel is dropped, negatives are kept.
        currentNowMicroamps = currentNowMicroamps.orNull(),
        chargeCounterMicroampHours = chargeCounterMicroampHours.orNull(),
        cycleCount = cycleCount.orNull(),
    )

    /** Percent only when the level/scale pair is internally consistent; otherwise `null`. */
    private fun percentOf(level: Int, scale: Int): Int? {
        if (scale <= 0 || level < 0 || level > scale) return null
        return level * 100 / scale
    }

    private fun Int.orNull(): Int? = if (this == ABSENT) null else this
}
