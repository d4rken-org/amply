package eu.darken.amply.stats.core

import android.os.BatteryManager
import androidx.room.withTransaction
import dagger.Lazy
import eu.darken.amply.battery.core.BatteryReadout
import eu.darken.amply.battery.core.BatteryReader
import eu.darken.amply.common.debug.logging.Logging
import eu.darken.amply.common.debug.logging.log
import eu.darken.amply.common.debug.logging.logTag
import eu.darken.amply.stats.core.db.BatterySampleEntity
import eu.darken.amply.stats.core.db.ChargeSessionEntity
import eu.darken.amply.stats.core.db.StatsDatabase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
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
 * dropped and enable/disable/clear are strictly ordered against samples.
 *
 * Capture on/off is an in-memory [capturing] flag driven by ordered [Command.SetEnabled] commands
 * (not a per-sample DataStore read), so an unplug sample enqueued just before a disable is sealed
 * with the correct endpoint before capture stops. The [StatsDatabase] is injected lazily so a
 * corrupt/locked stats DB can't fail construction of the safety-service graph.
 */
@Singleton
class ChargeStatsRecorder @Inject constructor(
    private val database: Lazy<StatsDatabase>,
    private val preferences: StatsPreferences,
    private val bootIdSource: BootIdSource,
    private val batteryReader: BatteryReader,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val commands = Channel<Command>(Channel.UNLIMITED)

    // Mutated only by the single consumer coroutine below — no locking needed.
    private var capturing = false
    private var openSession: ChargeSessionEntity? = null
    private var previousPlugged: Boolean? = null
    private var lastRecordedElapsed: Long? = null
    private var lastRecordedPercent: Int? = null

    init {
        scope.launch {
            // Runs before any command: seals sessions left open by an unclean shutdown, and seeds the
            // capturing flag from the durable preference.
            startupRepair()
            for (command in commands) {
                try {
                    when (command) {
                        is Command.Record -> onSample(command.tick)
                        is Command.SetEnabled -> onSetEnabled(command.enabled)
                        Command.Clear -> onClear()
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

    private suspend fun startupRepair() {
        try {
            capturing = preferences.isCaptureEnabledNow()
            // Only touch the DB when we might have data — avoids creating an empty stats.db for users
            // who never enabled statistics. A dangling open row can only exist after prior recording,
            // which always stamps lastCapture.
            if (preferences.lastCaptureWallMillis.first() == null) return
            sealDanglingSessions()
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
            powerMilliwatts = StatsPowerCalculator.milliwatts(readout.voltageMillivolts, readout.currentNowMicroamps),
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
        purgeOldSamples(endWallMillis)
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
        // Reset in-memory state immediately after the durable delete, before the cosmetic pref write,
        // so a failing pref edit can't strand a reference to a now-deleted parent row.
        resetInMemory()
        runCatching { preferences.clearLastCapture() }
    }

    /**
     * Seal every session left open by an unclean shutdown (process kill / reboot). Never resumes:
     * losing the monitor mid-charge breaks curve/elapsed continuity, so the session is closed
     * honestly and the next plug opens a fresh one. This also sidesteps having to trust the boot
     * count — a resumed session would risk a negative post-reboot duration.
     */
    private suspend fun sealDanglingSessions() {
        val dao = database.get().statsDao()
        val open = dao.openSessions()
        if (open.isEmpty()) return
        val currentBoot = bootIdSource.current()
        open.forEach { row ->
            val sealed = sealFromLastKnown(row, currentBoot)
            // Drop a dangling row that never captured anything (same rule as a live seal).
            if (StatsSessionEngine.isDiscardable(sealed)) dao.deleteSession(sealed.id) else dao.updateSession(sealed)
        }
        open.maxOfOrNull { it.runningLastWallMillis ?: it.startedAtWallMillis }?.let { purgeOldSamples(it) }
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

    /** Bound the raw-sample table: drop closed-session samples older than the retention window. */
    private suspend fun purgeOldSamples(nowWallMillis: Long) {
        val cutoff = nowWallMillis - RAW_SAMPLE_RETENTION_MILLIS
        runCatching { database.get().statsDao().deleteSamplesOlderThan(cutoff) }
            .onFailure { log(TAG, Logging.Priority.WARN) { "Stats retention purge failed: ${it.message}" } }
    }

    private suspend fun markRecorded(sample: StatsSample) {
        lastRecordedElapsed = sample.elapsedRealtimeMillis
        lastRecordedPercent = sample.percent
        preferences.setLastCaptureWallMillis(sample.wallMillis)
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
    }

    private companion object {
        val TAG = logTag("Stats", "Recorder")
        const val RAW_SAMPLE_RETENTION_MILLIS = 30L * 24 * 60 * 60 * 1000 // 30 days
    }
}
