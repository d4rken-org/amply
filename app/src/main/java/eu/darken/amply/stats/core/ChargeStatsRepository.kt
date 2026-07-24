package eu.darken.amply.stats.core

import dagger.Lazy
import eu.darken.amply.stats.core.db.BatterySampleEntity
import eu.darken.amply.stats.core.db.ChargeSessionEntity
import eu.darken.amply.stats.core.db.StatsDatabase
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
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
    private val bootIdSource: BootIdSource,
) {
    fun recentSessions(limit: Int = DEFAULT_SESSION_LIMIT): Flow<List<ChargeSessionSummary>> =
        database.get().statsDao().finishedSessions(limit).map { rows -> rows.map(::toSummary) }

    /** Count of finished sessions (for the dashboard teaser). */
    fun sessionCount(): Flow<Int> = database.get().statsDao().finishedSessionCount()

    /**
     * The in-progress charge session of the current boot, or null when nothing is open, as a live flow
     * for the dashboard card. The curve is a bounded recent window (decimated) so a session that stays
     * open for days at an OEM charge limit never triggers an unbounded reload on every appended sample.
     * `distinctUntilChangedBy(id)` keeps the inner sample flow subscribed across the per-tick session-row
     * updates (the open row's start fields are immutable, so only a change of session identity matters),
     * avoiding a redundant curve re-query on every fold.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun currentSession(): Flow<StatsLiveSession?> =
        database.get().statsDao().openSessionFlow(bootIdSource.current())
            .distinctUntilChangedBy { it?.id }
            .flatMapLatest { row ->
                if (row == null) {
                    flowOf(null)
                } else {
                    database.get().statsDao().recentSamplesForSession(row.id, LIVE_SAMPLE_WINDOW)
                        .map { samples -> toLive(row, samples) }
                }
            }

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

    private fun toLive(row: ChargeSessionEntity, samples: List<BatterySampleEntity>): StatsLiveSession {
        // Time points from the true session start (not the window's first sample) so the x-axis stays
        // truthful even when the recent window doesn't reach back to t=0.
        val start = row.startedElapsedRealtimeMillis
        val points = samples.map { sample ->
            ChargeCurvePoint(
                elapsedFromStartMillis = sample.elapsedRealtimeMillis - start,
                percent = sample.percent,
                powerMilliwatts = sample.powerMilliwatts,
                temperatureTenthsC = sample.temperatureTenthsC,
            )
        }
        return StatsLiveSession(
            id = row.id,
            startedAtWallMillis = row.startedAtWallMillis,
            startedElapsedRealtimeMillis = row.startedElapsedRealtimeMillis,
            startPercent = row.startPercent,
            partial = row.partial,
            curve = StatsDownsampler.decimate(points, LIVE_CURVE_POINTS),
        )
    }

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

        // Live dashboard curve: a bounded recent window, decimated for a compact glance.
        const val LIVE_SAMPLE_WINDOW = 300
        const val LIVE_CURVE_POINTS = 60
    }
}
