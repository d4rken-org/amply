package eu.darken.amply.stats.core

/**
 * An immutable, fully-resolved battery observation handed to the recorder. Built once, on the
 * service's evaluation thread, from the *same* battery intent the service evaluated (never a second
 * sticky-broadcast read), so plug state, percent, and the electrical fields are always internally
 * consistent even across a rapid unplug/replug.
 *
 * [elapsedRealtimeMillis] + [bootId] drive durations and ordering (clock-change proof);
 * [wallMillis] is for display only.
 */
data class StatsSample(
    val elapsedRealtimeMillis: Long,
    val wallMillis: Long,
    val bootId: Long,
    val plugged: Boolean,
    val pluggedRaw: Int?,
    val percent: Int?,
    val batteryStatus: Int?,
    val chargingStatus: Int?,
    val temperatureTenthsC: Int?,
    val voltageMillivolts: Int?,
    val currentNowMicroamps: Int?,
    /** Battery-terminal power magnitude (mW), precomputed by [StatsPowerCalculator]; null if absent. */
    val powerMilliwatts: Int?,
    /** [android.os.BatteryManager.BATTERY_STATUS_FULL] or level ≥ 100. */
    val full: Boolean,
    /** An Amply full-charge override owns this plug cycle right now. */
    val overrideActive: Boolean,
    /** An OEM charge limit appears to be holding right now (see [StatsLimitHitDetector]). */
    val limitHeldNow: Boolean,
)
