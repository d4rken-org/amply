package eu.darken.amply.charging.core.qualification

import android.content.Context
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import eu.darken.amply.battery.core.BatteryReader
import eu.darken.amply.charging.core.ChargeObservation
import eu.darken.amply.charging.core.ChargePolicy
import eu.darken.amply.charging.core.ChargingRepository
import eu.darken.amply.charging.core.QualificationRestoreOutcome
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
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * A nudge from the watcher: "evaluate now".
 *
 * It deliberately carries **no battery readings**. `BATTERY_PROPERTY_CHARGE_COUNTER` is a live
 * `BatteryManager` property rather than a broadcast extra, so a tick built under the service's
 * dispatch lock could only ever pair a broadcast-time level with a processing-time counter — two
 * different instants in the one piece of arithmetic the whole verdict rests on — and reading it there
 * would put a Binder call under a lock the service keeps free for policy recovery. The runner takes
 * one coherent snapshot on its own worker instead.
 *
 * [sessionActive] is not a battery reading: it is what the service knew when it dispatched, and a
 * session taking the policy mid-run is an abort either way.
 */
data class RawQualificationTick(
    val sessionActive: Boolean,
)

/**
 * What a finished run's close-out may say about the user's own charge setting.
 *
 * Deliberately not a boolean. "No recovery obligation remains" is true in three situations a surface
 * must describe differently: a restore was written and succeeded, nothing was owed because a newer
 * choice already holds, and the restore was skipped on purpose so that newer choice is not clobbered.
 * Only the first of those put the setting back, so only the first may say so — the other two have
 * nothing to report and must stay silent rather than claim a write that never happened.
 */
enum class QualificationRestorePresentation {
    /** The close-out wrote the user's own setting back and the write succeeded. */
    APPLIED,

    /**
     * The close-out tried to write the setting back and could not. The recovery target stays behind
     * for boot recovery, but nothing here observes that, so no surface may promise a retry.
     */
    PENDING,

    /** Nothing to say: no write was owed, or one was deliberately not made. */
    OMIT,
}

/** How a finished run ended, for the UI and the report. */
data class QualificationResult(
    val terminal: RunTerminal,
    val record: QualificationRunRecord,
    /**
     * What may be said about the user's own charge setting, and nothing else — this does not decide
     * anything the close-out does. Presentation only, like the result itself: the obligation that
     * outlives the process is the persisted recovery target, not this value.
     */
    val restorePresentation: QualificationRestorePresentation,
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
    private val startFailureFlow = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /**
     * Serializes everything that decides whether a run exists: startup repair, run start, and each
     * tick's read of the record. Without it repair can compute "no run", have a start commit one
     * underneath it, and then publish its own stale answer over the fresh one — which is precisely
     * the window [runActiveNow] exists to close.
     */
    private val stateMutex = Mutex()

    /**
     * Serializes [finish] end to end — claim, finalization and the release of the claim.
     *
     * [finish] is reachable from two places at once: this runner's own tick consumer, and the charge
     * service's scope by way of [startRequested]. Without this, a tick that finds a claimed record
     * could force-reclaim one a live finalization is still working through, and both would restore the
     * baseline and write evidence for the same run. Held, that tick simply waits and then finds the
     * record already cleared.
     */
    private val finalizationMutex = Mutex()

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
     * to be free to read.
     *
     * **It starts `true` and only becomes accurate when [startupRepair] resolves it**, because the two
     * possible errors are not symmetric. Claiming a run while none is happening costs the passive
     * enforcement recorder the first seconds of a process: it declines to observe, which at worst
     * delays a refutation until the next tick. Claiming *no* run while one is in flight makes that
     * same recorder read charging the run itself commanded as ordinary charging past a cap — a
     * terminal refutation of a device that may be perfectly fine. A record on disk is exactly the
     * situation where this flag is read before repair has run, so it defaults to the recoverable
     * error.
     */
    @Volatile
    var runActiveNow: Boolean = true
        private set

    /**
     * Emits when a start request could not open a run, so the screen that asked for one can go back
     * to its pre-check instead of waiting for a run that will never appear. Starting is a serialized
     * service command now, so its refusal cannot come back as a return value.
     */
    val startFailed: SharedFlow<Unit> = startFailureFlow

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
     * Resolve eligibility and begin a run, for the charge service's `ACTION_QUALIFICATION_START`
     * command.
     *
     * Runs are started **only** from there, never straight from a surface: run start and full-charge
     * session start both claim the charge policy, and the service's command queue is the one place
     * where the two are serialized against each other. A check-then-act from the UI would let both
     * observe a free policy and take it.
     */
    suspend fun startRequested(): Boolean {
        val eligibility = eligibility()
        if (eligibility !is RunEligibility.Eligible) {
            log(TAG, Logging.Priority.WARN) { "Qualification run refused: $eligibility" }
            startFailureFlow.tryEmit(Unit)
            return false
        }
        val started = start(eligibility)
        if (!started) startFailureFlow.tryEmit(Unit)
        return started
    }

    /**
     * Begin the run [eligible] resolved: its adapter, its caps, and whether its value mapping is a
     * guess.
     *
     * Ordering is deliberate and matches `ChargeSessionService.setPersistentPolicy`: persist both the
     * recovery target and the run record **before** the first policy write, so a death between any
     * two steps leaves work that is owed rather than a device in a state nothing remembers.
     */
    suspend fun start(eligible: RunEligibility.Eligible): Boolean {
        val adapter = eligible.adapter
        val plan = eligible.plan
        val record = stateMutex.withLock {
            if (runStore.currentRun() != null) return@withLock null
            // Refuse rather than guess. Falling back to the adapter's protective default would
            // overwrite a setting the run could not read — an unrecognized native mode, or a transient
            // readback failure — and then persistently restore the guess, permanently replacing the
            // user's real choice with 80%. A run is never worth that: not knowing what to put back
            // means not touching it in the first place.
            val baselinePolicy = (repository.syncReadback() as? ChargeObservation.Verified)?.policy
            if (baselinePolicy == null) {
                log(TAG, Logging.Priority.WARN) { "Refusing to start: the current charge policy is unreadable" }
                return@withLock null
            }
            val now = System.currentTimeMillis()
            val runId = UUID.randomUUID().toString()
            fullChargeStore.setPendingRecoveryTarget(
                policy = baselinePolicy,
                workId = runId,
                provenance = provenance(now),
                origin = RecoveryOrigin.SESSION_RESTORE,
            )
            runStore.put(
                QualificationRunRecord(
                    baseline = baselinePolicy,
                    runId = runId,
                    runToken = UUID.randomUUID().toString(),
                    adapterId = adapter.id,
                    buildIdentity = buildIdentity.current(),
                    protocolVersion = QualificationProtocol.PROTOCOL_VERSION,
                    enforcementAlgorithmVersion = EnforcementVerdictEngine.ALGORITHM_VERSION,
                    shape = plan.shape,
                    // Whether the commanded values are a guess is the eligibility layer's answer, not
                    // a literal here: it is what stops a guessed mapping from producing a terminal
                    // refutation, and a second copy of that decision would eventually disagree.
                    candidate = eligible.isCandidate,
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
            runStore.currentRun()
        } ?: return false
        // Nothing observes a run on its own: the record is what makes QualificationWatcher.isEnabled
        // true, but the service that fans out battery ticks may not be running at all — no session, no
        // gesture, no other watcher. Written first, nudged second, so the service's isEnabled query
        // already sees the run and keeps itself alive.
        if (!nudgeService()) {
            log(TAG, Logging.Priority.ERROR) { "Qualification run ${record.runId} could not start its service" }
            finish(RunTerminal.Aborted(AbortReason.SERVICE_UNAVAILABLE))
            return false
        }
        log(TAG, Logging.Priority.INFO) {
            "Qualification run ${record.runId} started on ${adapter.id}: ${plan.shape} cap=${plan.lowCap}"
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

    /**
     * Hand a restore this run could not perform back to boot recovery.
     *
     * The run record is what keeps the charge service alive, so a close-out that fails its single
     * baseline write used to be the end of the line: the record is cleared either way, the service
     * stopped at the next stop decision, and nothing re-dispatched recovery. A boot-time close-out is
     * where that bites — the backend or provider is routinely not ready yet — and the device would sit
     * on the run's experimental policy, which includes its deliberately less-protective release
     * policy, until the next foreground launch or reboot.
     *
     * This dispatch is one of the two halves that close that hole, and it is the half that cannot be
     * done from inside the service: it starts one that has already stopped, or was never running. The
     * other half lives in `ChargeSessionService`, whose stop decisions now keep the instance alive for
     * an owed recovery target no live run owns — which is what covers this dispatch racing the very
     * stop it is meant to prevent (clearing the record is what turns the last watcher off).
     *
     * `BootRecoveryFlow` is the machinery for a failed restore: it re-writes until the hardware
     * confirms or a budget expires. The recovery target is deliberately left in place above, so it is
     * still there for that flow to find.
     *
     * **It cannot loop.** Recovery never calls back into [finalize] — it repays the persisted target
     * and clears it — and the run record this method just cleared is what a second close-out would
     * need. One dispatch, no cycle. Fire-and-forget on purpose too: a `startForegroundService` takes
     * no lock, so this changes nothing about the lock order the finalization runs under.
     */
    private fun handOffToRecovery(record: QualificationRunRecord) {
        log(TAG, Logging.Priority.INFO) {
            "Run ${record.runId} hands its unrestored baseline back to boot recovery"
        }
        runCatching {
            ContextCompat.startForegroundService(
                context,
                ServiceDispatch.startIntent(context, ChargeSessionService.ACTION_RECOVER),
            )
        }.onFailure {
            log(TAG, Logging.Priority.WARN) { "Recovery hand-off dispatch failed: ${it.message}" }
        }
    }

    suspend fun cancel() {
        runStore.requestCancel()
    }

    /**
     * Resolve a record left behind by a dead process. The measurement cannot be resumed — its anchors
     * and hold clock describe an observation this process never made — so it is closed out and the
     * baseline restored. The recovery target registered at start means the restore is owed either
     * way; this only makes it happen now rather than at the next boot.
     *
     * It also publishes the first accurate [runActiveNow], under the same lock a start takes, so the
     * flag's pessimistic initial `true` cannot be replaced by a `false` computed before a start that
     * has since committed.
     *
     * The close-out **reclaims** the record even when the dead process had already claimed it for
     * finalization: dying mid-finalization is precisely the case this repair exists for, and without
     * the forced claim the record would be permanently unfinalizable — a run that never ends, so no
     * rule ever evaluates, no session ever starts, and the user's own policy is never restored. The
     * provenance filter above is what keeps this from stealing a finalization still in flight *here*.
     *
     * A record whose finalization got as far as writing its outcome down is **replayed**, not aborted:
     * a `Passed` or `Refuted` decided before the process died now finishes here instead of becoming
     * [AbortReason.PROCESS_DEATH]. Deliberate — that measurement was complete and its verdict was
     * durably decided before the death, and the dangerous direction is dropping a refutation, which
     * leaves the device holding control it demonstrably does not honour. [AbortReason.PROCESS_DEATH]
     * remains the fallback for a record that never got that far.
     */
    internal suspend fun startupRepair() {
        val stale = stateMutex.withLock {
            val record = runStore.currentRun()
            runActiveNow = record != null
            record?.takeIf { it.provenance?.token != processIdentity.token }
        } ?: return
        log(TAG, Logging.Priority.WARN) { "Qualification run ${stale.runId} survived its process; closing it out" }
        finish(
            stale.finalization?.toTerminal() ?: RunTerminal.Aborted(AbortReason.PROCESS_DEATH),
            reclaimRunId = stale.runId,
        )
    }

    internal suspend fun onTick(tick: RawQualificationTick) {
        val record = stateMutex.withLock {
            runStore.currentRun().also { runActiveNow = it != null }
        } ?: return
        // A run is never resumable across a process boundary: its anchors, hold clock and baseline
        // rate describe observations this process never made. A foreign record may therefore only
        // ever be closed out, never measured — evaluating one could end it Passed or Refuted on
        // readings from a dead process, which are the two catastrophes this design exists to prevent.
        //
        // [startupRepair] normally closes such a record out before the first tick, but its
        // finalization can fail (a store write, the pending recovery target) and then it returns with
        // the foreign record still stored and no longer claimed. The guard belongs here, where the
        // damage would happen: a failed repair simply retries on the next tick instead of the engine
        // measuring what it left behind.
        //
        // Closing it out replays the outcome it already decided when it has one, and only falls back
        // to PROCESS_DEATH when it does not — see [startupRepair] for why that direction is the safe
        // one.
        if (record.provenance?.token != processIdentity.token) {
            log(TAG, Logging.Priority.WARN) {
                "Qualification run ${record.runId} belongs to another process; closing it out"
            }
            finish(
                record.finalization?.toTerminal() ?: RunTerminal.Aborted(AbortReason.PROCESS_DEATH),
                reclaimRunId = record.runId,
            )
            return
        }
        // A claimed record is close-out-only for the same reason, one step further in: a finalization
        // is either in flight for it or was abandoned by one. Its own release is a store write and can
        // fail exactly as the finalization did — full storage, a transient I/O failure — and a claim
        // left held is refused by every ordinary terminal path, so the app would keep reading a run as
        // live for the rest of the process's life: rules suspended, no session, no new run, and the
        // baseline this run owes never restored.
        //
        // The forced reclaim is safe only because [finalizationMutex] serializes the whole of [finish]:
        // a finalization still in flight holds it, so this waits and then finds nothing left to claim.
        // An abandoned claim has no holder, so this is what recovers it, on the next tick after the
        // store recovers.
        //
        // A stored outcome is close-out-only too, claim or no claim: the release is a separate write
        // and can succeed where the clear failed, leaving a record that is unclaimed but whose terminal
        // was already decided. Either way the outcome is REPLAYED — FINALIZATION_INTERRUPTED is only
        // for a record claimed by a build that did not write one down.
        if (record.finalizing || record.finalization != null) {
            log(TAG, Logging.Priority.WARN) {
                "Qualification run ${record.runId} has an unfinished finalization; closing it out"
            }
            finish(
                record.finalization?.toTerminal() ?: RunTerminal.Aborted(AbortReason.FINALIZATION_INTERRUPTED),
                reclaimRunId = record.runId,
            )
            return
        }
        // One coherent snapshot, taken here on this runner's own worker: the level and the charge
        // counter come from the same read, and the timestamp describes that read rather than whenever
        // the broadcast that woke us was captured.
        val readout = batteryReader.read()
        val now = System.currentTimeMillis()
        val sample = QualificationSample(
            nowMillis = now,
            plugged = readout.onCharger,
            percent = readout.levelPercent ?: -1,
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
            finish(it, outcome.progress, phaseRate, sample.percent, sample.chargeCounter)
            return
        }
        // Merge into the CURRENT stored record rather than the copy this tick started from: a cancel
        // or a write failure flagged from another coroutine in between would otherwise be overwritten
        // with the stale `false` and silently lost. The closing sample's readings are passed in
        // explicitly — the next phase's anchors are deliberately empty until it opens its own window,
        // so a phase log built from them would record nothing.
        val advanced = runStore.mergeProgress {
            it.merge(outcome.progress, phaseRate, sample.percent, sample.chargeCounter)
        } ?: return
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
            return
        }
        // The phase's clock starts here, not when the engine emitted the command: everything before
        // this point still ran under the previous configuration.
        runStore.markApplied(System.currentTimeMillis())
    }

    /**
     * Close a run out. The restore comes first and its recovery target is cleared **only once the
     * write succeeded** — a failed restore deliberately leaves the target behind so the shipped boot
     * and foreground recovery paths still owe it.
     *
     * The record is **claimed for finalization in one transaction** first, and that claim is where the
     * outcome is both resolved and written down. A cancel (or a failed write) that committed before it
     * downgrades the terminal, even one already computed as a pass; one arriving after cannot commit
     * at all. The direction is safe by construction: a downgrade never turns an abort into a pass.
     *
     * What the claim returns is therefore authoritative, not the [terminal] argument: a record that
     * already carries a [FinalizationIntent] is one whose finalization was interrupted, and this is a
     * **replay** of the outcome it decided rather than a fresh decision. [terminal] is only the
     * proposal, used when there is no stored intent.
     *
     * Leaving [finalize] without having cleared the record **gives the claim back**, on every exit —
     * a throw and a cancellation alike. The claim is durable, so leaving it held would make the record
     * unfinalizable for the rest of this process's life while still reading as a live run: the run
     * would never end and the user's own policy would never be restored. Cancellation is not the
     * exotic case here — the production caller is the charge service's own lifecycle scope, which
     * `onDestroy` cancels, and the window spans the restore write. So the release sits in a `finally`
     * and runs [NonCancellable], or it would be cancelled with everything else. On the success path it
     * is a no-op: [finalize] cleared the record, so the run-id guard finds nothing to match.
     *
     * That release can itself fail — it is another store write — which is why it is not the only way a
     * claim comes back: [onTick] closes out any record it finds still claimed.
     *
     * [reclaimRunId] is for the close-out paths alone: a record abandoned mid-finalization, by a dead
     * process or by a release that could not be written, is claimed already, and forcing the claim is
     * the only way it ever ends. It is safe only under [finalizationMutex] — a finalization still in
     * flight holds it, so no reclaim can run beside one.
     */
    internal suspend fun finish(
        terminal: RunTerminal,
        progress: QualificationProgress? = null,
        phaseRatePerHour: Long = 0L,
        exitPercent: Int = -1,
        exitCounter: Int? = null,
        reclaimRunId: String? = null,
    ): Unit = finalizationMutex.withLock {
        val record = runStore.claimForFinalization(
            proposed = FinalizationIntent.of(terminal, System.currentTimeMillis()),
            reclaimRunId = reclaimRunId,
            // The measurement is persisted with the outcome that was decided from it, so a replay
            // finds both. Nothing to merge on a close-out path, which has no sample of its own.
            merge = if (progress != null) {
                { it.merge(progress, phaseRatePerHour, exitPercent, exitCounter) }
            } else {
                { it }
            },
        )
        if (record == null) {
            log(TAG, Logging.Priority.WARN) { "Nothing to finalize for $terminal; the run is already being closed out" }
            return@withLock
        }
        val effective = record.finalization?.toTerminal() ?: terminal
        if (effective != terminal) {
            log(TAG, Logging.Priority.INFO) { "Run ${record.runId} outcome downgraded from $terminal to $effective" }
        }
        try {
            finalize(record, effective)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log(TAG, Logging.Priority.ERROR) { "Run ${record.runId} finalization failed: ${e.message}" }
        } finally {
            // The release is a store write and can fail for whatever reason the finalization did.
            // Swallowed rather than thrown: it would replace the finalization's exception — the one
            // that says what actually went wrong — with a second symptom of the same cause. Nothing is
            // lost by it, because a claim left held is recovered by the next tick.
            withContext(NonCancellable) {
                runCatching { runStore.releaseFinalizationClaim(record.runId) }.onFailure {
                    log(TAG, Logging.Priority.ERROR) {
                        "Run ${record.runId} could not release its finalization claim: ${it.message}"
                    }
                }
            }
        }
    }

    /**
     * Write [terminal] out for [record]: restore, evidence, publish, clear.
     *
     * **Replayable, and idempotent by construction rather than by machinery.** Any of the four steps
     * can fail halfway, so the outcome is persisted with the claim and this runs again — possibly in
     * the next process — against a record that may already have had some of it applied. Each step
     * survives that: the restore only writes while this run still owns the owed restore, so a replay
     * either re-writes a policy that is already set or skips a baseline nobody is owed any more;
     * `QualificationEvidenceStore.record` overwrites unconditionally, and everything it writes is
     * taken from the record, so the second write is byte-identical; `EnforcementEvidenceStore.record`
     * refuses a duplicate for the same scope and returns false; and republishing [resultFlow] hands
     * whatever is watching the same result twice. Nothing here needs a "have I already done this"
     * ledger, and one would only be a second thing to keep in sync.
     *
     * That byte-identity is also why the evidence timestamps come from the intent's
     * [FinalizationIntent.decidedAtWallMillis] rather than a fresh clock read, and why the protocol
     * and algorithm versions come from the record rather than from today's constants: a replay can
     * run in a process started by an app update, and a measurement must be stamped with the versions
     * that produced it so the evidence stores' own scoping and migration decide whether it still
     * applies.
     */
    private suspend fun finalize(
        record: QualificationRunRecord,
        terminal: RunTerminal,
    ) {
        log(TAG, Logging.Priority.INFO) { "Run ${record.runId} finished: $terminal" }
        val decidedAt = record.finalization?.decidedAtWallMillis?.takeIf { it != 0L }
            ?: System.currentTimeMillis()
        // CONFIGURATION_DRIFT means the user made a NEWER choice than the baseline this run captured.
        // Restoring here would overwrite it with a stale value, which is the one thing worse than not
        // restoring at all. A full-charge session starting mid-run is NOT such a case any more: run
        // start and session start are serialized through the charge service's command queue, and the
        // session is refused while a run is live, so this abort can only come from a stale flag — and
        // then the baseline is still owed.
        val supersededByUser = terminal is RunTerminal.Aborted &&
            terminal.reason == AbortReason.CONFIGURATION_DRIFT
        // The behavioural flag and what a surface may say are computed side by side, never derived
        // from one another. [restored] answers "does this run still owe a write" and drives the
        // recovery-target clearing below; it is equally true of a restore that landed, of a newer
        // choice that made the restore unnecessary, and of one deliberately skipped. Only the first
        // of those actually put the user's setting back, so only the first may be shown as one.
        val (restored, restoreWritePresentation) = if (supersededByUser) {
            log(TAG, Logging.Priority.INFO) {
                "Run ${record.runId} superseded by a newer choice; leaving the current policy alone"
            }
            true to QualificationRestorePresentation.OMIT
        } else {
            // Ownership-gated, because this whole method is replayable: an interrupted finalization
            // can have restored and cleared its recovery target already, and the user can have made
            // an explicit persistent choice (a widget button) in between — one that clears its own
            // recovery target when it lands. Writing the baseline again there would revert that
            // choice permanently. Not owning the target means the restore is done or is somebody
            // else's now; either way it counts as complete here and only the write is skipped.
            runCatching {
                when (val outcome = repository.restoreQualificationBaselineIfOwned(record.runId, record.baseline)) {
                    is QualificationRestoreOutcome.Applied -> when {
                        outcome.result.success -> true to QualificationRestorePresentation.APPLIED
                        else -> false to QualificationRestorePresentation.PENDING
                    }

                    is QualificationRestoreOutcome.Superseded -> {
                        log(TAG, Logging.Priority.INFO) {
                            "Run ${record.runId} no longer owes its baseline; leaving the current policy alone"
                        }
                        true to QualificationRestorePresentation.OMIT
                    }
                }
            }.getOrElse {
                log(TAG, Logging.Priority.ERROR) { "Run ${record.runId} restore threw: ${it.message}" }
                false to QualificationRestorePresentation.PENDING
            }
        }
        // SERVICE_UNAVAILABLE is refused before the run's service ever starts, so nothing was ever
        // taken away: its own copy says so, and any restore line there — a reassuring one included —
        // would talk about a setting the user never saw changed, while a failure line would alarm them
        // about one. The restore above still runs (the recovery target is registered before the first
        // write, not after the last), it simply has nothing to report.
        val restorePresentation = if (terminal is RunTerminal.Aborted &&
            terminal.reason == AbortReason.SERVICE_UNAVAILABLE
        ) {
            QualificationRestorePresentation.OMIT
        } else {
            restoreWritePresentation
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
            // Both evidence writes take their version from the RECORD, never from the current
            // constant: a replay can run in a process started by an app update, and stamping the
            // measurement with a version that did not produce it would slip a superseded pass past
            // QualificationEvidenceStore's scoping, and a superseded refutation past
            // EnforcementEvidenceStore's per-verdict migration.
            is RunTerminal.Passed -> evidenceStore.record(
                QualificationEvidence(
                    adapterId = record.adapterId,
                    buildIdentity = record.buildIdentity,
                    protocolVersion = record.protocolVersion,
                    shape = record.shape,
                    signal = record.signal,
                    capPercent = record.lowCap,
                    observedHoldPercent = record.observedHoldPercent ?: -1,
                    candidatePromotion = record.candidate,
                    exercisedPolicies = listOf(
                        ChargePolicy.FixedLimit(record.lowCap).stableId,
                        record.releasePolicy.stableId,
                    ),
                    completedAtWallMillis = decidedAt,
                ),
            )

            is RunTerminal.Refuted -> enforcementStore.record(
                EnforcementEvidence(
                    adapterId = record.adapterId,
                    buildIdentity = record.buildIdentity,
                    // Zero can only come from a record written by a build that stored a finalization
                    // intent but not yet this field, and every such build measured at algorithm
                    // version 2. Pinning that constant instead of the current one preserves the
                    // measurement's provenance across an app update, so EnforcementEvidenceStore
                    // stays free to decide how a version-2 verdict migrates in a later release.
                    algorithmVersion = record.enforcementAlgorithmVersion
                        .takeIf { it != 0 }
                        ?: LEGACY_UNSTAMPED_QUALIFICATION_ALGORITHM_VERSION,
                    verdict = EnforcementVerdict.REFUTED,
                    capPercent = record.lowCap,
                    observedPercent = record.observedHoldPercent ?: -1,
                    observedAtWallMillis = decidedAt,
                ),
            )

            // Nothing is stored: a run that could not measure must be repeatable, and an absent
            // record must never be readable as a pass.
            is RunTerminal.Inconclusive, is RunTerminal.Aborted -> Unit
        }
        resultFlow.value = QualificationResult(terminal, record, restorePresentation = restorePresentation)
        stateMutex.withLock {
            runStore.clear()
            runActiveNow = false
        }
        // After the clear, deliberately: the recovery target this run still owes is now unowned by any
        // live run, so `ChargeSessionService.startRecovery`'s ownership guard no longer refuses it and
        // BootRecoveryFlow's bounded rewrite loop — which exists for exactly this kind of failure — can
        // repay it. Before the clear this dispatch would be refused by that same guard.
        if (!restored) handOffToRecovery(record)
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

        /**
         * The algorithm version a run record without [QualificationRunRecord.enforcementAlgorithmVersion]
         * was measured at. Deliberately a literal, not [EnforcementVerdictEngine.ALGORITHM_VERSION]:
         * the window of builds that could write such a record is closed, and every one of them ran
         * algorithm version 2.
         */
        const val LEGACY_UNSTAMPED_QUALIFICATION_ALGORITHM_VERSION = 2
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
    commandAckedAt = commandAckedAtWallMillis,
    windowAnchoredAt = windowAnchoredAtWallMillis,
    windowStartPercent = windowStartPercent,
    windowStartCounter = windowStartCounter,
    windowSignalChanges = windowSignalChanges,
    lastSignalValue = lastSignalValue,
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
 * it needs the sample that ended the phase, not the state that followed it. [exitPercent] and
 * [exitCounter] are that same sample's readings, passed explicitly for the same reason: the next
 * phase's anchors are empty until its own window opens, so reading them off the advanced state would
 * write a phase log full of blanks.
 */
internal fun QualificationRunRecord.merge(
    progress: QualificationProgress,
    phaseRatePerHour: Long = 0L,
    exitPercent: Int = -1,
    exitCounter: Int? = null,
): QualificationRunRecord {
    val phaseChanged = progress.phase != phase
    return copy(
        phase = progress.phase,
        phaseStartedAtWallMillis = progress.phaseStartedAt,
        commanded = progress.commanded?.stableId,
        commandedAtWallMillis = progress.commandedAt,
        commandAckedAtWallMillis = progress.commandAckedAt,
        windowAnchoredAtWallMillis = progress.windowAnchoredAt,
        windowStartPercent = progress.windowStartPercent,
        windowStartCounter = progress.windowStartCounter,
        windowSignalChanges = progress.windowSignalChanges,
        lastSignalValue = progress.lastSignalValue,
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
                exitPercent = exitPercent,
                exitCounter = exitCounter,
                // The rate this phase was actually judged on, so a report says why, not just what.
                ratePerHour = phaseRatePerHour,
            )
        } else {
            phaseLog
        },
    )
}
