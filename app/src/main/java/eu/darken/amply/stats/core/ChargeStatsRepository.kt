package eu.darken.amply.stats.core

import dagger.Lazy
import eu.darken.amply.stats.core.db.ChargeSessionEntity
import eu.darken.amply.stats.core.db.StatsDatabase
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Read-side facade over the stats Room store. Exposes domain models only ([ChargeSessionSummary],
 * [ChargeCurvePoint]) so the UI never imports Room types, and injects the database lazily like the
 * recorder does. Write ownership stays entirely with [ChargeStatsRecorder]; the only writes here are
 * the explicit user "clear data" action, which is routed through the recorder to stay serialized.
 */
@Singleton
class ChargeStatsRepository @Inject constructor(
    private val database: Lazy<StatsDatabase>,
    private val recorder: ChargeStatsRecorder,
) {
    fun recentSessions(limit: Int = DEFAULT_SESSION_LIMIT): Flow<List<ChargeSessionSummary>> =
        database.get().statsDao().finishedSessions(limit).map { rows -> rows.map(::toSummary) }

    fun session(id: Long): Flow<ChargeSessionSummary?> =
        database.get().statsDao().sessionFlow(id).map { it?.let(::toSummary) }

    suspend fun curve(id: Long, maxPoints: Int = DEFAULT_CURVE_POINTS): List<ChargeCurvePoint> {
        val samples = database.get().statsDao().samplesForSessionNow(id)
        val start = samples.firstOrNull()?.elapsedRealtimeMillis ?: 0L
        val points = samples.map { sample ->
            ChargeCurvePoint(
                elapsedFromStartMillis = sample.elapsedRealtimeMillis - start,
                percent = sample.percent,
                powerMilliwatts = sample.powerMilliwatts,
                temperatureTenthsC = sample.temperatureTenthsC,
            )
        }
        return StatsDownsampler.decimate(points, maxPoints)
    }

    /** Wipe all statistics (serialized through the recorder so it can't race an in-flight sample). */
    fun clearAll() = recorder.clear()

    private fun toSummary(row: ChargeSessionEntity) = ChargeSessionSummary(
        id = row.id,
        startedAtWallMillis = row.startedAtWallMillis,
        endedAtWallMillis = row.endedAtWallMillis,
        durationMillis = row.endedElapsedRealtimeMillis?.let { it - row.startedElapsedRealtimeMillis }?.takeIf { it >= 0 },
        startPercent = row.startPercent,
        endPercent = row.endPercent,
        chargingType = ChargingTypes.fromPluggedRaw(row.pluggedRaw),
        avgPowerMilliwatts = row.avgPowerMilliwatts,
        peakPowerMilliwatts = row.runningPeakPowerMilliwatts,
        minTemperatureTenthsC = row.runningMinTemperatureTenthsC,
        avgTemperatureTenthsC = row.avgTemperatureTenthsC,
        maxTemperatureTenthsC = row.runningMaxTemperatureTenthsC,
        limitHit = row.limitHitEvidence && !row.overrideSeen,
        partial = row.partial,
        fullReachedAtWallMillis = row.fullReachedAtWallMillis,
        sealReason = row.endReason?.let { runCatching { StatsSealReason.valueOf(it) }.getOrNull() },
    )

    private companion object {
        const val DEFAULT_SESSION_LIMIT = 100
        const val DEFAULT_CURVE_POINTS = 200
    }
}
