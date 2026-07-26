package eu.darken.amply.stats.core

import android.content.Context
import android.os.BatteryManager
import android.os.SystemClock
import androidx.room.withTransaction
import dagger.Lazy
import dagger.hilt.android.qualifiers.ApplicationContext
import eu.darken.amply.battery.core.BatteryReadout
import eu.darken.amply.battery.core.BatteryReader
import eu.darken.amply.common.debug.logging.Logging
import eu.darken.amply.common.debug.logging.log
import eu.darken.amply.common.debug.logging.logTag
import eu.darken.amply.stats.core.db.BatterySampleEntity
import eu.darken.amply.stats.core.db.ChargeSessionEntity
import eu.darken.amply.stats.core.db.StatsDao
import eu.darken.amply.stats.core.db.StatsDatabase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Serialized, off-service-thread writer for battery statistics. The watcher hands raw ticks in via
 * [offer], which only enqueues (never blocks, never touches Room/Binder/DataStore), so all
 * enrichment (parsing the intent, reading live battery properties, boot id) and all database work
 * happen on this recorder's own IO coroutine — entirely off the charge-session service's
 * `commandMutex`. That is what guarantees a slow read/write here can never delay the safety-critical
 * charge-policy restore. The command channel is FIFO and unbounded, so plug transitions are never
 * dropped and enable/disable/clear/purge are strictly ordered against samples.
 *
 * Capture on/off is an in-memory [capturing] flag driven by ordered [Command.SetEnabled] commands
 * (not a per-sample DataStore read), so an unplug sample enqueued just before a disable is sealed
 * with the correct endpoint before capture stops. The [StatsDatabase] is injected lazily so a
 * corrupt/locked stats DB can't fail construction of the safety-service graph.
 *
 * Retention ([StatsRetention]) is applied opportunistically rather than on a schedule: at process
 * start, whenever a session is sealed, and on an explicit [purgeNow] after the user moves the
 * retention slider. With capture **off** only the first and last of those fire — no samples arrive to
 * seal anything — so history can sit past its window until the app is next started or the slider is
 * touched. Accepted: nothing about stale local rows warrants a background job.
 */
@Singleton
class ChargeStatsRecorder @Inject constructor(
    @ApplicationContext private val context: Context,
    private val database: Lazy<StatsDatabase>,
    private val preferences: StatsPreferences,
    private val bootIdSource: BootIdSource,
    private val batteryReader: BatteryReader,
    @StatsDispatcher dispatcher: CoroutineDispatcher,
) {
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val commands = Channel<Command>(Channel.UNLIMITED)

    // Mutated only by the single consumer coroutine below — no locking needed.
    private var capturing = false
    private var openSession: ChargeSessionEntity? = null
    private var previousPlugged: Boolean? = null
    private var lastRecordedElapsed: Long? = null
    private var lastRecordedPercent: Int? = null

    init {
        scope.launch {
            // Runs before any command: seeds the capturing flag from the durable preference, reconciles
            // sessions left open by an unclean shutdown, and applies retention.
            startupRepair()
            for (command in commands) {
                try {
                    when (command) {
                        is Command.Record -> onSample(command.tick)
                        is Command.SetEnabled -> onSetEnabled(command.enabled)
                        Command.Clear -> onClear()
                        Command.Purge -> onPurge()
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    log(TAG, Logging.Priority.WARN) { "Stats command ${command::class.simpleName} failed: ${e.message}" }
                }
            }
        }
    }

    /** Enqueue a raw tick. Returns immediately; the caller (a monitor watcher) never awaits I/O. */
    fun offer(tick: RawStatsTick) {
        commands.trySend(Command.Record(tick))
    }

    /** Ordered enable/disable. Disabling seals any open session before capture stops. */
    fun setEnabled(enabled: Boolean) {
        commands.trySend(Command.SetEnabled(enabled))
    }

    /** Wipe all statistics. If capture is still enabled, the next sample opens a fresh session. */
    fun clear() {
        commands.trySend(Command.Clear)
    }

    /** Apply the retention window now, ordered against samples — for a changed retention setting. */
    fun purgeNow() {
        commands.trySend(Command.Purge)
    }

    private suspend fun startupRepair() {
        try {
            capturing = preferences.isCaptureEnabledNow()
            // Only touch the DB when we might have data — avoids creating an empty stats.db for users
            // who never enabled statistics. Capture being enabled is also sufficient: such a user gets
            // a stats.db on the next tick anyway, so opening it here costs nothing.
            if (!capturing && !statsDatabaseExists()) return
            reconcileDanglingSessions()
            // After reconciliation, never inside it: retention has to run even when there was no
            // dangling row to reconcile — the normal state after a clean shutdown, which is exactly
            // when a purge is the only thing this repair pass has to do.
            purgeExpiredSessions(System.currentTimeMillis())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log(TAG, Logging.Priority.WARN) { "Stats startup repair failed: ${e.message}" }
        }
    }

    private suspend fun onSample(tick: RawStatsTick) {
        if (!capturing) return
        val sample = buildSample(tick)
        val recordDue = StatsCadence.shouldRecord(
            lastRecordedElapsedMillis = lastRecordedElapsed,
            nowElapsedMillis = sample.elapsedRealtimeMillis,
            lastRecordedPercent = lastRecordedPercent,
            currentPercent = sample.percent,
        )
        when (val transition = StatsSessionEngine.decide(openSession != null, previousPlugged, sample.plugged, recordDue)) {
            is StatsTransition.Open -> openNewSession(sample, transition.partial)
            is StatsTransition.Append -> if (transition.record) appendSample(sample) else latchEvidence(sample)
            is StatsTransition.Seal -> sealCurrent(
                reason = transition.reason,
                endWallMillis = sample.wallMillis,
                endElapsedMillis = sample.elapsedRealtimeMillis,
                endPercent = sample.percent,
            )
            StatsTransition.Ignore -> Unit
        }
        previousPlugged = sample.plugged
    }

    private fun buildSample(tick: RawStatsTick): StatsSample {
        val readout = tick.batteryIntent?.let { batteryReader.read(it) } ?: BatteryReadout.UNKNOWN
        val percent = tick.percent.takeIf { it >= 0 } ?: readout.levelPercent
        val full = tick.batteryStatus == BatteryManager.BATTERY_STATUS_FULL ||
            (percent != null && percent >= 100)
        return StatsSample(
            elapsedRealtimeMillis = tick.observedElapsedRealtimeMillis,
            wallMillis = tick.wallMillis,
            bootId = bootIdSource.current(),
            plugged = tick.plugged,
            pluggedRaw = readout.plugged,
            percent = percent,
            batteryStatus = tick.batteryStatus,
            chargingStatus = readout.chargingStatus,
            temperatureTenthsC = readout.temperatureTenthsC,
            voltageMillivolts = readout.voltageMillivolts,
            currentNowMicroamps = readout.currentNowMicroamps,
            // Charge power only: an unsigned magnitude recorded while the battery is not gaining
            // charge would land in the curve, the peak, and the average as if it were a charge rate.
            // The inputs stay on the sample (voltage/current/status), so nothing is lost — only the
            // derived field is withheld where it would mean something it doesn't.
            powerMilliwatts = StatsPowerCalculator.chargeMilliwatts(
                batteryStatus = tick.batteryStatus,
                plugged = tick.plugged,
                voltageMillivolts = readout.voltageMillivolts,
                currentNowMicroamps = readout.currentNowMicroamps,
            ),
            full = full,
            overrideActive = tick.sessionActive,
            limitHeldNow = StatsLimitHitDetector.heldNow(
                plugged = tick.plugged,
                chargingStatus = readout.chargingStatus,
                batteryStatus = tick.batteryStatus,
                percent = percent,
                currentNowMicroamps = readout.currentNowMicroamps,
            ),
        )
    }

    private suspend fun openNewSession(sample: StatsSample, partial: Boolean) {
        val entity = StatsSessionEngine.open(sample, partial)
        val db = database.get()
        val id = db.withTransaction {
            val newId = db.statsDao().insertSession(entity)
            db.statsDao().insertSample(sample.toEntity(newId))
            newId
        }
        openSession = entity.copy(id = id)
        markRecorded(sample)
    }

    private suspend fun appendSample(sample: StatsSample) {
        val current = openSession ?: return
        val folded = StatsSessionEngine.fold(current, sample)
        val db = database.get()
        db.withTransaction {
            db.statsDao().updateSession(folded)
            db.statsDao().insertSample(sample.toEntity(folded.id))
        }
        openSession = folded
        markRecorded(sample)
    }

    /**
     * Persist a below-cadence sample's sticky evidence (see [StatsSessionEngine.latchEvidence]) so a
     * limit hold or override that starts and ends within one cadence window isn't lost when the
     * sample itself is dropped. Writes only when a flag actually flips — at most once per flag per
     * session — and never touches aggregates or the curve.
     */
    private suspend fun latchEvidence(sample: StatsSample) {
        val current = openSession ?: return
        val updated = StatsSessionEngine.latchEvidence(current, sample) ?: return
        database.get().statsDao().updateSession(updated)
        openSession = updated
    }

    private suspend fun sealCurrent(
        reason: StatsSealReason,
        endWallMillis: Long,
        endElapsedMillis: Long,
        endPercent: Int?,
    ) {
        val current = openSession ?: return
        val sealed = StatsSessionEngine.seal(current, reason, endWallMillis, endElapsedMillis, endPercent)
        val dao = database.get().statsDao()
        // A session that captured nothing (e.g. record-toggled on then off while already plugged) is
        // deleted rather than kept as a spurious 0-minute history row; its samples cascade.
        if (StatsSessionEngine.isDiscardable(sealed)) {
            dao.deleteSession(sealed.id)
        } else {
            dao.updateSession(sealed)
        }
        openSession = null
        lastRecordedElapsed = null
        lastRecordedPercent = null
        purgeExpiredSessions(endWallMillis)
    }

    private suspend fun onSetEnabled(enabled: Boolean) {
        capturing = enabled
        if (!enabled) {
            openSession?.let { current ->
                sealCurrent(
                    reason = StatsSealReason.DISABLED,
                    endWallMillis = current.runningLastWallMillis ?: current.startedAtWallMillis,
                    endElapsedMillis = current.runningLastElapsedRealtimeMillis ?: current.startedElapsedRealtimeMillis,
                    endPercent = current.runningLastPercent,
                )
            }
            resetInMemory()
        }
    }

    private suspend fun onClear() {
        val db = database.get()
        db.withTransaction {
            db.statsDao().deleteAllSamples()
            db.statsDao().deleteAllSessions()
        }
        // Reset in-memory state immediately after the durable delete, so nothing keeps a reference to
        // a now-deleted parent row.
        resetInMemory()
    }

    /**
     * Apply retention on demand. Guarded like [startupRepair] so dragging the retention slider can
     * never be what creates `stats.db` for a user who has no recorded data at all.
     */
    private suspend fun onPurge() {
        if (!capturing && !statsDatabaseExists()) return
        purgeExpiredSessions(System.currentTimeMillis())
    }

    /**
     * Reconcile sessions left open by an unclean shutdown (process kill / reboot). The newest row is
     * offered to [StatsSessionEngine.evaluateResume] against the battery state observed right now; if
     * the same plug event is still underway it is reattached, keeping the true plug-in time and one
     * history row per physical charge. Everything else is sealed as before.
     *
     * The probe happens here rather than on the first watcher tick on purpose. A held-open row would
     * be rendered as a live session by the dashboard for as long as no tick arrives — which also
     * suppresses the "couldn't start capture" retry when the foreground service fails to start — and
     * would need leak guards on the disable/clear paths. Resolving synchronously costs one sticky
     * broadcast read and leaves [onSample] untouched.
     */
    private suspend fun reconcileDanglingSessions() {
        val dao = database.get().statsDao()
        val open = dao.openSessions()
        if (open.isEmpty()) return
        val currentBoot = bootIdSource.current()
        // Capture is off, so no tick is coming to advance a resumed row — seal everything, as before.
        // Ordered by id (monotonic), matching the row `openSessionFlow` shows as live; `openSessions`
        // orders by elapsed-realtime, which disagrees across a reboot.
        val candidate = if (capturing) open.maxByOrNull { it.id } else null
        val resumed = candidate?.let { resumeOrNull(it, currentBoot) }
        open.filter { it.id != resumed?.id }.forEach { row -> sealDangling(dao, row, currentBoot) }
    }

    /**
     * Reattach [row] if the charge it recorded is still running, else null. In-memory state is
     * installed only *after* the durable write returns: command failures are logged and swallowed
     * (see the loop above), and a remembered-but-unwritten session would let a later tick open a
     * second row against a row the database still has open.
     */
    private suspend fun resumeOrNull(row: ChargeSessionEntity, currentBoot: Long): ChargeSessionEntity? {
        val readout = batteryReader.read()
        val probe = ResumeProbe(
            elapsedRealtimeMillis = SystemClock.elapsedRealtime(),
            bootId = currentBoot,
            plugged = (readout.plugged ?: 0) != 0,
            percent = readout.levelPercent,
        )
        return when (val decision = StatsSessionEngine.evaluateResume(row, probe)) {
            is ResumeDecision.Reject -> {
                log(TAG) { "Not resuming session ${row.id}: ${decision.reason}" }
                null
            }

            is ResumeDecision.Resume -> {
                database.get().statsDao().updateSession(decision.session)
                openSession = decision.session
                // Continue the existing cadence instead of restarting it, so the resumed session
                // doesn't force an extra curve point on top of the one it already has.
                lastRecordedElapsed = decision.session.runningLastElapsedRealtimeMillis
                lastRecordedPercent = decision.session.runningLastPercent
                log(TAG, Logging.Priority.INFO) { "Resumed charge session ${decision.session.id}" }
                decision.session
            }
        }
    }

    private suspend fun sealDangling(dao: StatsDao, row: ChargeSessionEntity, currentBoot: Long) {
        val sealed = sealFromLastKnown(row, currentBoot)
        // Drop a dangling row that never captured anything (same rule as a live seal).
        if (StatsSessionEngine.isDiscardable(sealed)) dao.deleteSession(sealed.id) else dao.updateSession(sealed)
    }

    private fun sealFromLastKnown(row: ChargeSessionEntity, currentBoot: Long): ChargeSessionEntity {
        val reason = if (row.bootId != currentBoot) StatsSealReason.REBOOT else StatsSealReason.INTERRUPTED
        return StatsSessionEngine.seal(
            session = row,
            reason = reason,
            endWallMillis = row.runningLastWallMillis ?: row.startedAtWallMillis,
            endElapsedMillis = row.runningLastElapsedRealtimeMillis ?: row.startedElapsedRealtimeMillis,
            endPercent = row.runningLastPercent,
        )
    }

    /**
     * Apply the user's retention window: whole finished entries that ended before the cutoff go, and
     * the surviving ones lose curve points older than it (a long charge that ended recently keeps its
     * summary while its oldest samples age out).
     */
    private suspend fun purgeExpiredSessions(nowWallMillis: Long) {
        val cutoff = StatsRetention.cutoffWallMillis(nowWallMillis, preferences.retentionDaysNow())
        runCatching {
            val dao = database.get().statsDao()
            dao.deleteSessionsEndedBefore(cutoff)
            dao.deleteSamplesOlderThan(cutoff)
        }.onFailure { log(TAG, Logging.Priority.WARN) { "Stats retention purge failed: ${it.message}" } }
    }

    /**
     * True if a stats DB file is on disk. Sound where the old last-capture timestamp was not: that
     * stamp was written *after* the first row was committed, so a process death in the gap left data
     * the guard couldn't see.
     */
    private fun statsDatabaseExists(): Boolean =
        runCatching { context.getDatabasePath(StatsDatabase.NAME).exists() }.getOrDefault(false)

    private fun markRecorded(sample: StatsSample) {
        lastRecordedElapsed = sample.elapsedRealtimeMillis
        lastRecordedPercent = sample.percent
    }

    private fun resetInMemory() {
        openSession = null
        previousPlugged = null
        lastRecordedElapsed = null
        lastRecordedPercent = null
    }

    private fun StatsSample.toEntity(sessionId: Long) = BatterySampleEntity(
        sessionId = sessionId,
        wallMillis = wallMillis,
        elapsedRealtimeMillis = elapsedRealtimeMillis,
        bootId = bootId,
        percent = percent,
        batteryStatus = batteryStatus,
        chargingStatus = chargingStatus,
        pluggedRaw = pluggedRaw,
        temperatureTenthsC = temperatureTenthsC,
        voltageMillivolts = voltageMillivolts,
        currentNowMicroamps = currentNowMicroamps,
        powerMilliwatts = powerMilliwatts,
    )

    private sealed interface Command {
        data class Record(val tick: RawStatsTick) : Command
        data class SetEnabled(val enabled: Boolean) : Command
        data object Clear : Command
        data object Purge : Command
    }

    private companion object {
        val TAG = logTag("Stats", "Recorder")
    }
}
