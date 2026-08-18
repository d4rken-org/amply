package eu.darken.amply.charging.core.qualification

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import eu.darken.amply.battery.core.BatteryReader
import eu.darken.amply.charging.core.ChargeObservation
import eu.darken.amply.charging.core.ChargePolicy
import eu.darken.amply.charging.core.ChargingRepository
import eu.darken.amply.charging.core.DeviceInfo
import eu.darken.amply.charging.core.adapter.ChargingAdapter
import eu.darken.amply.charging.core.enforcement.BuildIdentitySource
import eu.darken.amply.charging.core.enforcement.EnforcementEvidence
import eu.darken.amply.charging.core.enforcement.EnforcementEvidenceStore
import eu.darken.amply.charging.core.enforcement.EnforcementVerdict
import eu.darken.amply.charging.core.enforcement.EnforcementVerdictEngine
import eu.darken.amply.common.debug.logging.Logging
import eu.darken.amply.common.debug.logging.log
import eu.darken.amply.common.debug.logging.logTag
import eu.darken.amply.fullcharge.core.BootCountProvider
import eu.darken.amply.fullcharge.core.ChargeSessionService
import eu.darken.amply.fullcharge.core.FullChargeStore
import eu.darken.amply.fullcharge.core.ProcessIdentity
import eu.darken.amply.fullcharge.core.RecoveryOrigin
import eu.darken.amply.fullcharge.core.ServiceDispatch
import eu.darken.amply.fullcharge.core.WorkProvenance
import eu.darken.amply.main.core.SurfaceUpdater
import eu.darken.amply.rules.core.RuleApplier
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** One battery observation handed over by the watcher. Copied, never processed, on the dispatch thread. */
data class RawQualificationTick(
    val plugged: Boolean,
    val percent: Int,
    val sessionActive: Boolean,
    val batteryIntent: Intent?,
    val wallMillis: Long,
)

/** How a finished run ended, for the UI and the report. */
data class QualificationResult(
    val terminal: RunTerminal,
    val record: QualificationRunRecord,
)

/**
 * Hosts the guided run: turns battery ticks into [QualificationSample]s, executes the commands
 * [QualificationRunEngine] emits, and guarantees the user's own charge policy comes back afterwards.
 *
 * Same shape as `EnforcementRecorder`: an unbounded channel drained by a single consumer on a private
 * dispatcher, with every piece of mutable state touched only by that consumer. The watcher that feeds
 * it runs under the charge service's dispatch lock with a 5 s budget, so nothing here may be done
 * there.
 *
 * **The restore is registered before the first write, not after the last one.** Run start persists the
 * baseline as a `FullChargeStore` recovery target with [RecoveryOrigin.SESSION_RESTORE], which the
 * shipped boot receiver, `ServiceDispatch` and `BootRecoveryFlow` already repay through the ungated
 * `restorePersistent()`. A process death or reboot mid-run therefore needs no recovery machinery of
 * its own, and the run record below is only what lets a *surviving* process carry on measuring.
 */
@Singleton
class QualificationRunner @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: ChargingRepository,
    private val runStore: QualificationRunStore,
    private val evidenceStore: QualificationEvidenceStore,
    private val enforcementStore: EnforcementEvidenceStore,
    private val fullChargeStore: FullChargeStore,
    private val buildIdentity: BuildIdentitySource,
    private val batteryReader: BatteryReader,
    private val ruleApplier: RuleApplier,
    private val processIdentity: ProcessIdentity,
    private val bootCountProvider: BootCountProvider,
    @QualificationDispatcher dispatcher: CoroutineDispatcher,
) {
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val ticks = Channel<RawQualificationTick>(Channel.UNLIMITED)
    private val resultFlow = MutableStateFlow<QualificationResult?>(null)

    init {
        scope.launch {
            // Before any tick, exactly as ChargeStatsRecorder does: a record left behind by a dead
            // process must be closed out rather than measured against.
            try {
                startupRepair()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log(TAG, Logging.Priority.ERROR) { "Qualification startup repair failed: ${e.message}" }
            }
            for (tick in ticks) {
                try {
                    onTick(tick)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    log(TAG, Logging.Priority.ERROR) { "Qualification tick failed: ${e.message}" }
                }
            }
        }
    }

    /** Enqueue only; never blocks and never throws, so a watcher tick cannot stall the service. */
    fun offer(tick: RawQualificationTick) {
        ticks.trySend(tick)
    }

    /** Whether a run is in flight, which is also what keeps the foreground service alive. */
    suspend fun isRunning(): Boolean = runStore.currentRun() != null

    /**
     * The same answer without suspending, for callers on a latency-critical path — the charge
     * service's watcher dispatch, which stamps it onto each battery tick at capture time.
     *
     * Maintained by this runner's own lifecycle rather than read from the store, because the point is
     * to be free to read. It can only lag a run started by another process until the first tick, and
     * lagging *false* is the harmless direction: it lets the passive recorder observe normally.
     */
    @Volatile
    var runActiveNow: Boolean = false
        private set

    /**
     * Whether this device can be offered a run right now, and with what caps.
     *
     * Composed here rather than in the ViewModel so the pre-check list, the entry-point visibility
     * and [start] all consult one answer instead of three approximations of it.
     *
     * Access readiness deliberately does **not** use `ChargingState.canApply`: that folds in
     * `controlEnabled`, which is false for precisely the CANDIDATE devices a run exists to serve. It
     * asks the same question minus the tier — is there a backend that could carry the write.
     */
    suspend fun eligibility(): RunEligibility {
        val selection = repository.currentSelection()
        val state = repository.state.value
        val readout = batteryReader.read()
        val accessReady = if (state.writeRequiresShizuku) {
            state.access?.shizuku?.ready == true
        } else {
            state.access?.canControl == true
        }
        return qualificationEligibility(
            adapter = selection.adapter,
            support = selection.support,
            evidence = enforcementStore.currentState(),
            plugged = readout.onCharger,
            percent = readout.levelPercent ?: -1,
            accessReady = accessReady,
            sessionActive = fullChargeStore.currentSession() != null,
            pendingRecovery = fullChargeStore.pendingRecoveryTarget() != null,
            ruleOwnsPolicy = runCatching { ruleApplier.ruleOwnsPolicy() }.getOrDefault(true),
            baselineReadable = repository.syncReadback() is ChargeObservation.Verified,
        )
    }

    /**
     * Begin a run against [adapter] with the caps in [plan].
     *
     * Ordering is deliberate and matches `ChargeSessionService.setPersistentPolicy`: suspend the rule
     * cohort and persist both the recovery target and the run record **before** the first policy
     * write, so a death between any two steps leaves work that is owed rather than a device in a
     * state nothing remembers.
     */
    suspend fun start(adapter: ChargingAdapter, plan: RunPlan): Boolean {
        if (runStore.currentRun() != null) return false
        // Refuse rather than guess. Falling back to the adapter's protective default would overwrite
        // a setting the run could not read — an unrecognized native mode, or a transient readback
        // failure — and then persistently restore the guess, permanently replacing the user's real
        // choice with 80%. A run is never worth that: not knowing what to put back means not touching
        // it in the first place.
        val baselinePolicy =
            (repository.syncReadback() as? ChargeObservation.Verified)?.policy
        if (baselinePolicy == null) {
            log(TAG, Logging.Priority.WARN) { "Refusing to start: the current charge policy is unreadable" }
            return false
        }
        val restoreTarget = baselinePolicy
        val now = System.currentTimeMillis()
        val runId = UUID.randomUUID().toString()

        fullChargeStore.setPendingRecoveryTarget(
            policy = restoreTarget,
            workId = runId,
            provenance = provenance(now),
            origin = RecoveryOrigin.SESSION_RESTORE,
        )
        runStore.put(
            QualificationRunRecord(
                baseline = restoreTarget,
                runId = runId,
                runToken = UUID.randomUUID().toString(),
                adapterId = adapter.id,
                buildIdentity = buildIdentity.current(),
                protocolVersion = QualificationProtocol.PROTOCOL_VERSION,
                shape = plan.shape,
                candidate = false,
                baselineVerified = true,
                phase = RunPhase.PREFLIGHT,
                runStartedAtWallMillis = now,
                phaseStartedAtWallMillis = now,
                lowCap = plan.lowCap,
                releasePolicy = plan.releasePolicy,
                provenance = provenance(now),
            ),
        )
        runActiveNow = true
        // Nothing observes a run on its own: the record is what makes QualificationWatcher.isEnabled
        // true, but the service that fans out battery ticks may not be running at all — no session, no
        // gesture, no other watcher. Written first, nudged second, so the service's isEnabled query
        // already sees the run and keeps itself alive.
        val record = runStore.currentRun()
        if (record != null && !nudgeService()) {
            log(TAG, Logging.Priority.ERROR) { "Qualification run $runId could not start its service" }
            finish(record, RunTerminal.Aborted(AbortReason.SERVICE_UNAVAILABLE))
            return false
        }
        log(TAG, Logging.Priority.INFO) {
            "Qualification run $runId started on ${adapter.id}: ${plan.shape} cap=${plan.lowCap}"
        }
        SurfaceUpdater.updateNow(context)
        return true
    }

    /**
     * Start (or wake) the charge-session service so its battery ticks reach this run. Same dispatch
     * the statistics recorder uses when its capture toggle turns on.
     */
    private fun nudgeService(): Boolean = runCatching {
        ContextCompat.startForegroundService(
            context,
            ServiceDispatch.startIntent(context, ChargeSessionService.ACTION_MONITOR),
        )
    }.onFailure {
        log(TAG, Logging.Priority.WARN) { "Charge service nudge failed: ${it.message}" }
    }.isSuccess

    suspend fun cancel() {
        runStore.requestCancel()
    }

    /**
     * Resolve a record left behind by a dead process. The measurement cannot be resumed — its anchors
     * and hold clock describe an observation this process never made — so it is closed out and the
     * baseline restored. The recovery target registered at start means the restore is owed either
     * way; this only makes it happen now rather than at the next boot.
     */
    internal suspend fun startupRepair() {
        val record = runStore.currentRun() ?: return
        val ours = record.provenance?.token == processIdentity.token
        if (ours) return
        log(TAG, Logging.Priority.WARN) { "Qualification run ${record.runId} survived its process; closing it out" }
        finish(record, RunTerminal.Aborted(AbortReason.PROCESS_DEATH))
    }

    private suspend fun onTick(tick: RawQualificationTick) {
        val record = runStore.currentRun()
        runActiveNow = record != null
        if (record == null) return
        val readout = tick.batteryIntent?.let { batteryReader.read(it) } ?: batteryReader.read()
        val sample = QualificationSample(
            nowMillis = tick.wallMillis.takeIf { it > 0 } ?: System.currentTimeMillis(),
            plugged = tick.plugged,
            percent = tick.percent.takeIf { it >= 0 } ?: readout.levelPercent ?: -1,
            chargeCounter = readout.chargeCounterMicroampHours,
            configured = repository.syncReadback(),
            sessionActive = tick.sessionActive,
            writeFailed = record.writeFailed,
            cancelled = record.cancelled,
        )
        val progress = record.toProgress()
        val outcome = QualificationRunEngine.evaluate(progress, sample)
        // Captured before the phase advances: it is the rate the phase that just ended was judged on.
        val phaseRate = QualificationRunEngine.ratePerHour(progress, sample) ?: 0L
        outcome.terminal?.let {
            finish(record, it, outcome.progress, phaseRate)
            return
        }
        // Merge into the CURRENT stored record rather than the copy this tick started from: a cancel
        // or a write failure flagged from another coroutine in between would otherwise be overwritten
        // with the stale `false` and silently lost.
        val advanced = runStore.mergeProgress { it.merge(outcome.progress, phaseRate) } ?: return
        outcome.command?.let { command ->
            when (command) {
                is RunCommand.Apply -> executeApply(advanced, command.policy)
            }
        }
        if (outcome.command != null) SurfaceUpdater.updateNow(context)
    }

    private suspend fun executeApply(record: QualificationRunRecord, policy: ChargePolicy) {
        log(TAG, Logging.Priority.INFO) { "Run ${record.runId} applying ${policy.stableId}" }
        val result = repository.applyForQualification(policy, record.runToken)
        if (!result.success) {
            log(TAG, Logging.Priority.WARN) { "Run ${record.runId} write failed: ${result.message}" }
            runStore.markWriteFailed()
        }
    }

    /**
     * Close a run out. The restore comes first and its recovery target is cleared **only once the
     * write succeeded** — a failed restore deliberately leaves the target behind so the shipped boot
     * and foreground recovery paths still owe it.
     */
    private suspend fun finish(
        record: QualificationRunRecord,
        terminal: RunTerminal,
        progress: QualificationProgress? = null,
        phaseRatePerHour: Long = 0L,
    ) {
        log(TAG, Logging.Priority.INFO) { "Run ${record.runId} finished: $terminal" }
        // CONFIGURATION_DRIFT means the user made a NEWER choice than the baseline this run captured.
        // Restoring here would overwrite it with a stale value, which is the one thing worse than not
        // restoring at all. The same holds for a full-charge session that started mid-run: it owns
        // the policy now and has its own restore obligation.
        val supersededByUser = terminal is RunTerminal.Aborted &&
            (terminal.reason == AbortReason.CONFIGURATION_DRIFT || terminal.reason == AbortReason.SESSION_STARTED)
        val restored = if (supersededByUser) {
            log(TAG, Logging.Priority.INFO) {
                "Run ${record.runId} superseded by a newer choice; leaving the current policy alone"
            }
            true
        } else {
            runCatching {
                repository.restorePersistent(record.baseline, forceNotify = true).success
            }.getOrElse {
                log(TAG, Logging.Priority.ERROR) { "Run ${record.runId} restore threw: ${it.message}" }
                false
            }
        }
        if (restored) {
            // Owner-scoped: between this run registering its target and clearing it, a widget or tile
            // write can have stored a NEWER one in the same single slot. Clearing unconditionally
            // would drop that newer obligation on the floor, and nothing would ever repay it.
            fullChargeStore.clearPendingRecoveryTargetIfOwnedBy(record.runId)
        } else {
            log(TAG, Logging.Priority.ERROR) {
                "Run ${record.runId} could not restore ${record.baseline.stableId}; leaving the recovery target"
            }
        }
        when (terminal) {
            is RunTerminal.Passed -> evidenceStore.record(
                QualificationEvidence(
                    adapterId = record.adapterId,
                    buildIdentity = record.buildIdentity,
                    protocolVersion = QualificationProtocol.PROTOCOL_VERSION,
                    shape = record.shape,
                    signal = progress?.signal ?: record.signal,
                    capPercent = record.lowCap,
                    observedHoldPercent = progress?.observedHoldPercent ?: record.observedHoldPercent ?: -1,
                    candidatePromotion = record.candidate,
                    exercisedPolicies = listOf(
                        ChargePolicy.FixedLimit(record.lowCap).stableId,
                        record.releasePolicy.stableId,
                    ),
                    completedAtWallMillis = System.currentTimeMillis(),
                ),
            )

            is RunTerminal.Refuted -> enforcementStore.record(
                EnforcementEvidence(
                    adapterId = record.adapterId,
                    buildIdentity = record.buildIdentity,
                    algorithmVersion = EnforcementVerdictEngine.ALGORITHM_VERSION,
                    verdict = EnforcementVerdict.REFUTED,
                    capPercent = record.lowCap,
                    observedPercent = progress?.observedHoldPercent ?: -1,
                    observedAtWallMillis = System.currentTimeMillis(),
                ),
            )

            // Nothing is stored: a run that could not measure must be repeatable, and an absent
            // record must never be readable as a pass.
            is RunTerminal.Inconclusive, is RunTerminal.Aborted -> Unit
        }
        resultFlow.value = QualificationResult(
            terminal,
            progress?.let { record.merge(it, phaseRatePerHour) } ?: record,
        )
        runStore.clear()
        runActiveNow = false
        repository.refresh()
        SurfaceUpdater.updateNow(context)
    }

    /**
     * The outcome of the most recent run in this process.
     *
     * A flow rather than a value, because a run usually finishes while the screen is open and often
     * while the phone is locked: the result has to arrive at whatever is watching, not sit waiting to
     * be polled. Deliberately not persisted — it is presentation only, and everything that must
     * survive a restart (the verdict, the restore) already does.
     */
    val lastResult: StateFlow<QualificationResult?> = resultFlow

    fun clearResult() {
        resultFlow.value = null
    }

    private fun provenance(now: Long) = WorkProvenance(
        token = processIdentity.token,
        pid = android.os.Process.myPid(),
        bootCount = bootCountProvider.current(),
        createdAtMillis = now,
    )

    private companion object {
        val TAG = logTag("Charging", "Qualification", "Runner")
    }
}

/** The engine's view of a persisted run. */
internal fun QualificationRunRecord.toProgress() = QualificationProgress(
    shape = shape,
    phase = phase,
    runStartedAt = runStartedAtWallMillis,
    phaseStartedAt = phaseStartedAtWallMillis,
    lowCap = lowCap,
    releasePolicy = releasePolicy,
    commanded = commanded?.let { ChargePolicy.fromStableId(it) },
    commandedAt = commandedAtWallMillis,
    windowStartAt = windowStartAtWallMillis,
    windowStartPercent = windowStartPercent,
    windowStartCounter = windowStartCounter,
    signal = signal,
    baselineRatePerHour = baselineRatePerHour,
    impliedFullCapacity = impliedFullCapacity,
    candidate = candidate,
    observedHoldPercent = observedHoldPercent,
)

/**
 * Fold the engine's advanced state back into the persisted record, appending to the phase log.
 *
 * [phaseRatePerHour] is the rate the closing phase was judged on, which only the caller can compute —
 * it needs the sample that ended the phase, not the state that followed it.
 */
internal fun QualificationRunRecord.merge(
    progress: QualificationProgress,
    phaseRatePerHour: Long = 0L,
): QualificationRunRecord {
    val phaseChanged = progress.phase != phase
    return copy(
        phase = progress.phase,
        phaseStartedAtWallMillis = progress.phaseStartedAt,
        commanded = progress.commanded?.stableId,
        commandedAtWallMillis = progress.commandedAt,
        windowStartAtWallMillis = progress.windowStartAt,
        windowStartPercent = progress.windowStartPercent,
        windowStartCounter = progress.windowStartCounter,
        signal = progress.signal,
        baselineRatePerHour = progress.baselineRatePerHour,
        impliedFullCapacity = progress.impliedFullCapacity,
        observedHoldPercent = progress.observedHoldPercent,
        phaseLog = if (phaseChanged) {
            phaseLog + PhaseRecord(
                phase = phase,
                commanded = commanded.orEmpty(),
                enteredAtWallMillis = phaseStartedAtWallMillis,
                entryPercent = windowStartPercent,
                entryCounter = windowStartCounter,
                exitAtWallMillis = progress.phaseStartedAt,
                exitPercent = progress.windowStartPercent,
                exitCounter = progress.windowStartCounter,
                // The rate this phase was actually judged on, so a report says why, not just what.
                ratePerHour = phaseRatePerHour,
            )
        } else {
            phaseLog
        },
    )
}
