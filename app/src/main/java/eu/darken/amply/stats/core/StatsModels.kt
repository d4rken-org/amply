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

/**
 * One point on a session's charge curve, timed from the session start.
 *
 * [voltageMillivolts] and [currentNowMicroamps] are raw observations carried through verbatim —
 * unlike [powerMilliwatts] they are **not** withheld while the battery isn't taking charge, because
 * they are directional readings that stay meaningful in either direction (the current keeps the
 * sign the OEM reported; it is never made absolute here).
 */
data class ChargeCurvePoint(
    val elapsedFromStartMillis: Long,
    val percent: Int?,
    val powerMilliwatts: Int?,
    val temperatureTenthsC: Int?,
    val voltageMillivolts: Int? = null,
    val currentNowMicroamps: Int? = null,
)

/**
 * Which metrics a charge actually recorded, computed from its **raw** samples.
 *
 * Deliberately not derived from the curve a surface draws: [StatsDownsampler.decimate] thins with a
 * uniform stride, so a metric reported only intermittently can lose every one of its readings to
 * decimation. A tile deciding "nothing recorded" from the survivors would refuse to open a detail
 * screen that has data to show — the detail screen reads the undecimated samples.
 */
data class CurveMetricAvailability(
    val level: Boolean = false,
    val power: Boolean = false,
    val voltage: Boolean = false,
    val current: Boolean = false,
    val temperature: Boolean = false,
) {
    companion object {
        /** Nothing recorded — the honest state before any curve has been read. */
        val NONE = CurveMetricAvailability()

        fun of(points: List<ChargeCurvePoint>) = CurveMetricAvailability(
            level = points.any { it.percent != null },
            power = points.any { it.powerMilliwatts != null },
            voltage = points.any { it.voltageMillivolts != null },
            current = points.any { it.currentNowMicroamps != null },
            temperature = points.any { it.temperatureTenthsC != null },
        )
    }
}

/** A finished session's bounded recent curve paired with what its raw samples actually recorded. */
data class RecentCurveData(
    val curve: List<ChargeCurvePoint> = emptyList(),
    val availability: CurveMetricAvailability = CurveMetricAvailability.NONE,
)

/**
 * The in-progress charge session for the dashboard's live card. Carries only what Room owns
 * authoritatively — the session's start, its "partial" nature, and a bounded recent curve. The live
 * "now" values (current level, temperature, power) are read from the dashboard's fresh battery
 * readout instead, so the live card and the battery hero above it can never disagree.
 */
data class StatsLiveSession(
    val id: Long,
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
    /** Taken from the raw window, so a metric decimation dropped is still known to exist. */
    val availability: CurveMetricAvailability = CurveMetricAvailability.NONE,
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
