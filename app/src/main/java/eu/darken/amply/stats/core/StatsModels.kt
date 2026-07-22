package eu.darken.amply.stats.core

/** How the device was connected during a session, derived from the raw plugged bitmask. */
enum class ChargingType { AC, USB, WIRELESS, DOCK, MIXED, UNKNOWN }

/** A finished (or resumed-open) charge session, mapped from Room for the UI. */
data class ChargeSessionSummary(
    val id: Long,
    val startedAtWallMillis: Long,
    val endedAtWallMillis: Long?,
    /** Boot-scoped elapsed duration; null while open. Clock-change-proof. */
    val durationMillis: Long?,
    val startPercent: Int?,
    val endPercent: Int?,
    val chargingType: ChargingType,
    val avgPowerMilliwatts: Int?,
    val peakPowerMilliwatts: Int?,
    val minTemperatureTenthsC: Int?,
    val avgTemperatureTenthsC: Int?,
    val maxTemperatureTenthsC: Int?,
    /** Heuristic — presented as "limit likely reached", never asserted. */
    val limitHit: Boolean,
    val partial: Boolean,
    val fullReachedAtWallMillis: Long?,
    val sealReason: StatsSealReason?,
)

/** One point on a session's charge curve, timed from the session start. */
data class ChargeCurvePoint(
    val elapsedFromStartMillis: Long,
    val percent: Int?,
    val powerMilliwatts: Int?,
    val temperatureTenthsC: Int?,
)

/** Maps a raw [android.os.BatteryManager.EXTRA_PLUGGED] bitmask to a [ChargingType]. */
object ChargingTypes {
    // Literal for DOCK avoids a hard API-33 symbol reference (BATTERY_PLUGGED_DOCK).
    private const val AC = 1
    private const val USB = 2
    private const val WIRELESS = 4
    private const val DOCK = 8

    fun fromPluggedRaw(pluggedRaw: Int?): ChargingType {
        if (pluggedRaw == null || pluggedRaw == 0) return ChargingType.UNKNOWN
        val matched = buildList {
            if (pluggedRaw and AC != 0) add(ChargingType.AC)
            if (pluggedRaw and USB != 0) add(ChargingType.USB)
            if (pluggedRaw and WIRELESS != 0) add(ChargingType.WIRELESS)
            if (pluggedRaw and DOCK != 0) add(ChargingType.DOCK)
        }
        return when {
            matched.isEmpty() -> ChargingType.UNKNOWN
            matched.size > 1 -> ChargingType.MIXED
            else -> matched.first()
        }
    }
}
