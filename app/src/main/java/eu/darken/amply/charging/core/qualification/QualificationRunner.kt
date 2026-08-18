package eu.darken.amply.charging.core.qualification

import android.content.Context
import android.content.Intent
import dagger.hilt.android.qualifiers.ApplicationContext
import eu.darken.amply.battery.core.BatteryReader
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
import eu.darken.amply.fullcharge.core.FullChargeStore
import eu.darken.amply.fullcharge.core.ProcessIdentity
import eu.darken.amply.fullcharge.core.RecoveryOrigin
import eu.darken.amply.fullcharge.core.WorkProvenance
import eu.darken.amply.main.core.SurfaceUpdater
import eu.darken.amply.rules.core.PlugKind
import eu.darken.amply.rules.core.RuleApplier
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
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

    init {
        scope.launch {
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
     * Begin a run against [adapter] with the caps in [plan].
     *
     * Ordering is deliberate and matches `ChargeSessionService.setPersistentPolicy`: suspend the rule
     * cohort and persist both the recovery target and the run record **before** the first policy
     * write, so a death between any two steps leaves work that is owed rather than a device in a
     * state nothing remembers.
     */
    suspend fun start(adapter: ChargingAdapter, plan: RunPlan): Boolean {
        if (runStore.currentRun() != null) return false
        val baseline = repository.syncReadback()
        val baselinePolicy = (baseline as? eu.darken.amply.charging.core.ChargeObservation.Verified)?.policy
        val restoreTarget = baselinePolicy ?: adapter.defaultProtectivePolicy
        val now = System.currentTimeMillis()
        val runId = UUID.randomUUID().toString()

        // Rules first: a matching rule would otherwise rewrite the policy mid-experiment and the run
        // would measure Amply arguing with itself.
        val (pluggedNow, plugKindNow) = currentPlug()
        try {
            ruleApplier.suspendMatchingCohort(pluggedNow, plugKindNow)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log(TAG, Logging.Priority.WARN) { "Rule suspension failed: ${e.message}" }
        }

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
                baselineVerified = baselinePolicy != null,
                phase = RunPhase.PREFLIGHT,
                runStartedAtWallMillis = now,
                phaseStartedAtWallMillis = now,
                lowCap = plan.lowCap,
                releasePolicy = plan.releasePolicy,
                provenance = provenance(now),
            ),
        )
        log(TAG, Logging.Priority.INFO) {
            "Qualification run $runId started on ${adapter.id}: ${plan.shape} cap=${plan.lowCap}"
        }
        SurfaceUpdater.updateNow(context)
        return true
    }

    suspend fun cancel() {
        runStore.requestCancel()
    }

    /**
     * Resolve a record left behind by a dead process. The measurement cannot be resumed — its anchors
     * and hold clock describe an observation this process never made — so it is closed out and the
     * baseline restored. The recovery target registered at start means the restore is owed either
     * way; this only makes it happen now rather than at the next boot.
     */
    suspend fun startupRepair() {
        val record = runStore.currentRun() ?: return
        val ours = record.provenance?.token == processIdentity.token
        if (ours) return
        log(TAG, Logging.Priority.WARN) { "Qualification run ${record.runId} survived its process; closing it out" }
        finish(record, RunTerminal.Aborted(AbortReason.PROCESS_DEATH))
    }

    private suspend fun onTick(tick: RawQualificationTick) {
        val record = runStore.currentRun() ?: return
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
        val outcome = QualificationRunEngine.evaluate(record.toProgress(), sample)
        outcome.terminal?.let {
            finish(record, it, outcome.progress)
            return
        }
        val advanced = record.merge(outcome.progress)
        runStore.put(advanced)
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
    ) {
        log(TAG, Logging.Priority.INFO) { "Run ${record.runId} finished: $terminal" }
        val restored = runCatching {
            repository.restorePersistent(record.baseline, forceNotify = true).success
        }.getOrElse {
            log(TAG, Logging.Priority.ERROR) { "Run ${record.runId} restore threw: ${it.message}" }
            false
        }
        if (restored) {
            fullChargeStore.clearPendingRecoveryTarget()
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
        lastResult = QualificationResult(terminal, progress?.let { record.merge(it) } ?: record)
        runStore.clear()
        repository.refresh()
        SurfaceUpdater.updateNow(context)
    }

    /**
     * The outcome of the most recent run in this process, for the result screen. Deliberately not
     * persisted: it is a presentation detail, and everything that must survive a restart — the
     * verdict, the restore — already is.
     */
    @Volatile
    var lastResult: QualificationResult? = null
        private set

    fun consumeResult(): QualificationResult? = lastResult.also { lastResult = null }

    private fun provenance(now: Long) = WorkProvenance(
        token = processIdentity.token,
        pid = android.os.Process.myPid(),
        bootCount = bootCountProvider.current(),
        createdAtMillis = now,
    )

    private fun currentPlug(): Pair<Boolean, PlugKind?> {
        val raw = runCatching {
            context.registerReceiver(null, android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED))
                ?.getIntExtra(android.os.BatteryManager.EXTRA_PLUGGED, 0)
        }.getOrNull() ?: 0
        return (raw != 0) to PlugKind.fromExtraPlugged(raw)
    }

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
    anchorPercent = anchorPercent,
    anchorCounter = anchorCounter,
    holdSince = holdSinceWallMillis,
    signal = signal,
    candidate = candidate,
    observedHoldPercent = observedHoldPercent,
)

/** Fold the engine's advanced state back into the persisted record, appending to the phase log. */
internal fun QualificationRunRecord.merge(progress: QualificationProgress): QualificationRunRecord {
    val phaseChanged = progress.phase != phase
    return copy(
        phase = progress.phase,
        phaseStartedAtWallMillis = progress.phaseStartedAt,
        commanded = progress.commanded?.stableId,
        commandedAtWallMillis = progress.commandedAt,
        anchorPercent = progress.anchorPercent,
        anchorCounter = progress.anchorCounter,
        holdSinceWallMillis = progress.holdSince,
        signal = progress.signal,
        observedHoldPercent = progress.observedHoldPercent,
        phaseLog = if (phaseChanged) {
            phaseLog + PhaseRecord(
                phase = phase,
                commanded = commanded.orEmpty(),
                enteredAtWallMillis = phaseStartedAtWallMillis,
                entryPercent = anchorPercent,
                entryCounter = anchorCounter,
                exitAtWallMillis = progress.phaseStartedAt,
                exitPercent = progress.anchorPercent,
                exitCounter = progress.anchorCounter,
            )
        } else {
            phaseLog
        },
    )
}
