package eu.darken.amply.stats.core

import android.os.BatteryManager
import dagger.Lazy
import eu.darken.amply.stats.core.db.BatterySampleEntity
import eu.darken.amply.stats.core.db.ChargeSessionEntity
import eu.darken.amply.stats.core.db.StatsDatabase
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.first
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
     * `distinctUntilChangedBy` keeps the inner sample flow subscribed across the per-tick session-row
     * updates, avoiding a redundant curve re-query on every fold. It keys on identity **plus every row
     * field this flow actually renders**: `partial` flips when a session is resumed after a process
     * restart, and since the row is captured inside `flatMapLatest`, an id-only key would pin the stale
     * copy for the lifetime of the subscription. The remaining projected fields (the start columns) are
     * immutable once the row exists.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun currentSession(): Flow<StatsLiveSession?> =
        database.get().statsDao().openSessionFlow(bootIdSource.current())
            .distinctUntilChangedBy { row -> row?.let { it.id to it.partial } }
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

    /**
     * A session's full curve as a live flow, so the detail screen keeps updating while the session
     * is still open (samples land every recorder tick). Unbounded on purpose — unlike the dashboard's
     * bounded [currentSession] window it is only subscribed while a detail screen is open.
     */
    fun curveFlow(id: Long, maxPoints: Int = DEFAULT_CURVE_POINTS): Flow<List<ChargeCurvePoint>> =
        database.get().statsDao().samplesForSession(id).map { samples ->
            StatsDownsampler.decimate(samples.toCurve(), maxPoints)
        }

    /**
     * A session's decimated curve **and** the exact per-metric aggregates, from one pass over the
     * same raw samples.
     *
     * The aggregates deliberately do NOT come from the returned curve: [curveFlow]'s decimation
     * keeps a uniform stride, so a short temperature or current extreme is simply not among the
     * points that survive. A screen printing "Maximum" must print the session's real maximum, and
     * computing both here is what keeps the chart and the numbers beside it from disagreeing.
     */
    fun sessionMetrics(id: Long, maxPoints: Int = DEFAULT_CURVE_POINTS): Flow<SessionMetricData> =
        database.get().statsDao().samplesForSession(id).map { samples ->
            val points = samples.toCurve()
            SessionMetricData(
                curve = StatsDownsampler.decimate(points, maxPoints),
                aggregates = CurveAggregates.of(points),
            )
        }

    /**
     * A **bounded** recent curve for a session, for the battery hub's sparklines. Unlike [curveFlow]
     * this reads only the most recent [LIVE_SAMPLE_WINDOW] samples: the hub keeps this subscribed
     * while it is open, and a session held at an OEM limit stays open for days, so an unbounded read
     * would reload the entire curve on every appended sample.
     */
    fun recentCurveFlow(id: Long, maxPoints: Int = LIVE_CURVE_POINTS): Flow<List<ChargeCurvePoint>> =
        database.get().statsDao().recentSamplesForSession(id, LIVE_SAMPLE_WINDOW).map { samples ->
            StatsDownsampler.decimate(samples.toCurve(), maxPoints)
        }

    /**
     * The step samples of the most recent finished sessions, already reduced to what
     * [ChargeBandExtractor] needs, plus each session's recorded average power (the estimator's
     * "speed" figure is a median across these, not a recomputation).
     *
     * Suspending and one-shot on purpose: the charge-time model is an expensive fold that must run
     * off the main thread, and nothing here may touch Room until it is actually called.
     */
    suspend fun bandObservations(sessionLimit: Int = DEFAULT_BAND_SESSION_LIMIT): BandObservationBatch {
        val dao = database.get().statsDao()
        val sessions = dao.finishedSessions(sessionLimit).first()
        val observations = mutableListOf<BandObservation>()
        val power = mutableMapOf<Long, Int>()
        sessions.forEach { row ->
            val steps = dao.samplesForSessionNow(row.id).map { sample ->
                ChargeStepSample(
                    elapsedMillis = sample.elapsedRealtimeMillis,
                    percent = sample.percent,
                    batteryStatus = sample.batteryStatus,
                )
            }
            observations += ChargeBandExtractor.extract(
                sessionId = row.id,
                chargingType = ChargingTypes.fromPluggedRaw(row.pluggedRaw),
                samples = steps,
            )
            row.avgPowerMilliwatts?.let { power[row.id] = it }
        }
        return BandObservationBatch(observations = observations, sessionPowerMilliwatts = power)
    }


    /**
     * Raw samples → curve points, timed from the first sample. Voltage and current ride along
     * untouched (see [ChargeCurvePoint]); only power passes the charging gate below.
     */
    private fun List<BatterySampleEntity>.toCurve(): List<ChargeCurvePoint> {
        val start = firstOrNull()?.elapsedRealtimeMillis ?: 0L
        return map { sample ->
            ChargeCurvePoint(
                elapsedFromStartMillis = sample.elapsedRealtimeMillis - start,
                percent = sample.percent,
                powerMilliwatts = sample.chargePowerMilliwatts(),
                temperatureTenthsC = sample.temperatureTenthsC,
                voltageMillivolts = sample.voltageMillivolts,
                currentNowMicroamps = sample.currentNowMicroamps,
            )
        }
    }

    /**
     * A sample's power, but only where it is charge power.
     *
     * The recorder now withholds it at the source, so for anything captured since this is a no-op.
     * It exists for rows written before that gate: those stored an unsigned magnitude on every
     * sample, including ones taken while the battery was draining, and plotting those in a charge
     * curve would show draw as charging. Filtering on the sample's own [BatterySampleEntity.batteryStatus]
     * — which was always recorded — makes old sessions read as honestly as new ones.
     *
     * Only the *curve* is repaired this way. A pre-gate session's stored `avgPowerMilliwatts` and
     * `runningPeakPowerMilliwatts` were accumulated online at record time and are deliberately left
     * as they are: they cannot be recomputed once the raw samples age out of retention, and no
     * migration is worth writing for data that only exists on pre-launch test devices. Such a session
     * can therefore show a corrected curve beside a slightly-off average — it ages out, every new
     * session is clean, and "Clear statistics data" is there for anyone who would rather not wait.
     */
    private fun BatterySampleEntity.chargePowerMilliwatts(): Int? =
        powerMilliwatts.takeIf { batteryStatus == BatteryManager.BATTERY_STATUS_CHARGING }

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
                powerMilliwatts = sample.chargePowerMilliwatts(),
                temperatureTenthsC = sample.temperatureTenthsC,
                voltageMillivolts = sample.voltageMillivolts,
                currentNowMicroamps = sample.currentNowMicroamps,
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

        // How many recent finished sessions the charge-time model folds over.
        const val DEFAULT_BAND_SESSION_LIMIT = 10

        // Live dashboard curve: a bounded recent window, decimated for a compact glance.
        const val LIVE_SAMPLE_WINDOW = 300
        const val LIVE_CURVE_POINTS = 60
    }
}
