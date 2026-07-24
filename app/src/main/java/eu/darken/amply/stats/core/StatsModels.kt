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

/**
 * The in-progress charge session for the dashboard's live card. Carries only what Room owns
 * authoritatively — the session's start, its "partial" nature, and a bounded recent curve. The live
 * "now" values (current level, temperature, power) are read from the dashboard's fresh battery
 * readout instead, so the live card and the battery hero above it can never disagree.
 */
data class StatsLiveSession(
    val startedAtWallMillis: Long,
    /**
     * Boot-scoped monotonic start ([android.os.SystemClock.elapsedRealtime]). Elapsed "charging for" is
     * derived from this against the current elapsed-realtime, so a wall-clock/NTP adjustment can't make
     * the duration negative or jump — and it shares the curve's clock.
     */
    val startedElapsedRealtimeMillis: Long,
    val startPercent: Int?,
    /** True when capture began mid-charge — the card frames it as "since …", not a full history. */
    val partial: Boolean,
    val curve: List<ChargeCurvePoint>,
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
