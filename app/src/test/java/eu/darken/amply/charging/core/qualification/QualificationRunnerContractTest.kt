package eu.darken.amply.charging.core.qualification

import android.app.Notification
import android.content.Context
import android.content.Intent
import android.os.BatteryManager
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.test.core.app.ApplicationProvider
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import dagger.hilt.components.SingletonComponent
import eu.darken.amply.R
import eu.darken.amply.battery.core.BatteryReader
import eu.darken.amply.charging.core.ChargePolicy
import eu.darken.amply.charging.core.ChargingRepository
import eu.darken.amply.charging.core.enforcement.BuildIdentitySource
import eu.darken.amply.charging.core.enforcement.EnforcementEvidenceState
import eu.darken.amply.charging.core.enforcement.EnforcementEvidenceStore
import eu.darken.amply.charging.core.enforcement.EnforcementVerdict
import eu.darken.amply.charging.core.enforcement.EnforcementVerdictEngine
import eu.darken.amply.common.AppDataStore
import eu.darken.amply.common.debug.logging.Logging
import eu.darken.amply.common.serialization.SerializationModule
import eu.darken.amply.fullcharge.core.BootCountProvider
import eu.darken.amply.fullcharge.core.ChargeSessionService
import eu.darken.amply.fullcharge.core.FullChargeStore
import eu.darken.amply.fullcharge.core.ProcessIdentity
import eu.darken.amply.fullcharge.core.RecoveryOrigin
import eu.darken.amply.fullcharge.core.WorkProvenance
import eu.darken.amply.rules.core.RuleApplier
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * What the run guarantees about *owning* the charge policy, as opposed to what it measures (that is
 * `QualificationRunEngineTest`'s job).
 *
 * All three properties here exist to protect the same thing: the user's own charge setting, which the
 * run holds temporarily and owes back. A session started underneath it would capture the run's
 * temporary policy as that setting; a recovery started underneath it would repay the run's own owed
 * baseline while the run keeps writing; and a passive observation taken while the flag says no run is
 * happening reads charging the run itself commanded as charging past a cap — a terminal refutation.
 */
@HiltAndroidTest
@Config(application = HiltTestApplication::class, sdk = [36])
@RunWith(RobolectricTestRunner::class)
class QualificationRunnerContractTest {

    @get:Rule val hiltRule = HiltAndroidRule(this)

    @get:Rule val tempFolder = TemporaryFolder()

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface Deps {
        fun runStore(): QualificationRunStore
        fun fullChargeStore(): FullChargeStore
        fun repository(): ChargingRepository
        fun qualificationEvidenceStore(): QualificationEvidenceStore
        fun enforcementEvidenceStore(): EnforcementEvidenceStore
        fun buildIdentity(): BuildIdentitySource
        fun batteryReader(): BatteryReader
        fun ruleApplier(): RuleApplier
        fun processIdentity(): ProcessIdentity
        fun bootCountProvider(): BootCountProvider
    }

    private companion object {
        const val QUALIFICATION_EVIDENCE_KEY = "qualification.result.v1"
        const val ENFORCEMENT_EVIDENCE_KEY = "enforcement.evidence.v1"
    }

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var deps: Deps

    /** Only for the flaky-store case below; the rest run on the injected stores. */
    private val storeScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    @After
    fun teardown() {
        storeScope.cancel()
    }

    @Before
    fun setup() {
        hiltRule.inject()
        deps = EntryPointAccessors.fromApplication(context, Deps::class.java)
        // A plugged-in device, so a run in flight is not aborted for the wrong reason mid-test.
        context.sendStickyBroadcast(
            Intent(Intent.ACTION_BATTERY_CHANGED).apply {
                putExtra(BatteryManager.EXTRA_PLUGGED, BatteryManager.BATTERY_PLUGGED_AC)
                putExtra(BatteryManager.EXTRA_LEVEL, 80)
                putExtra(BatteryManager.EXTRA_SCALE, 100)
                putExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_CHARGING)
            },
        )
    }

    private fun record(runId: String = "run-1", token: String = "token-1") = QualificationRunRecord(
        baseline = ChargePolicy.FixedLimit(80),
        runId = runId,
        runToken = token,
        adapterId = "lineageos-chargingcontrol-v1",
        buildIdentity = "build-a",
        protocolVersion = QualificationProtocol.PROTOCOL_VERSION,
        lowCap = 70,
        releasePolicy = ChargePolicy.FixedLimit(85),
        runStartedAtWallMillis = 1_000L,
        phaseStartedAtWallMillis = 1_000L,
        // This process's own token: a record from a dead one is closed out by startup repair, which
        // is a different property (below) and would take the run away before the guard is asked.
        provenance = WorkProvenance(
            token = deps.processIdentity().token,
            pid = 1,
            bootCount = 1,
            createdAtMillis = 1_000L,
        ),
    )

    /**
     * The collision the run's own recovery target makes possible. A session start that merely skipped
     * `beginOrResume` would fall through to the pending-recovery arm, find the target the run
     * registered before its first write, and start repaying it — restoring and clearing the baseline
     * out from under a run that is still writing temporary policies.
     */
    @Test
    fun `a full-charge session refuses to start while a run is live and leaves the run's restore owed`() {
        val runStore = deps.runStore()
        val fullChargeStore = deps.fullChargeStore()
        runBlocking {
            runStore.put(record())
            fullChargeStore.setPendingRecoveryTarget(
                policy = ChargePolicy.FixedLimit(80),
                workId = "run-1",
                provenance = WorkProvenance(token = "t", pid = 1, bootCount = 1, createdAtMillis = 1_000L),
                origin = RecoveryOrigin.SESSION_RESTORE,
            )
        }

        val controller = Robolectric.buildService(ChargeSessionService::class.java).create()
        val service = controller.get()
        service.onStartCommand(
            Intent(context, ChargeSessionService::class.java).setAction(ChargeSessionService.ACTION_START),
            0,
            1,
        )

        // The refusing branch keeps the service alive for the run and posts the run's own
        // notification, which is how this asserts the command was handled rather than merely slow.
        awaitNotification(service, context.getString(R.string.qualification_notification_title)) shouldBe true
        // The fall-through this guards against reaches the pending-recovery arm a moment later and
        // replaces that notification with the recovering one, so the absence has to be given time.
        neverNotified(service, context.getString(R.string.recovering_notification_title)) shouldBe true
        runBlocking {
            fullChargeStore.currentSession() shouldBe null
            (fullChargeStore.pendingRecoveryTarget() != null) shouldBe true
        }
        controller.destroy()
    }

    /**
     * The flag the passive enforcement recorder stamps onto every battery tick. It must start
     * claiming a run, because the error directions are not symmetric: a wrong `true` costs the
     * recorder a few seconds of observation, a wrong `false` lets it record a terminal refutation for
     * charging the run itself commanded.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `runActiveNow claims a run until startup repair resolves it`() {
        val scheduler = TestCoroutineScheduler()
        val runner = runner(StandardTestDispatcher(scheduler))

        // Startup repair has not been allowed to run: the answer is the safe one, not the unknown one.
        runner.runActiveNow shouldBe true

        // Repair suspends on real durable reads, so releasing it takes more than one advance.
        val deadline = System.currentTimeMillis() + 10_000L
        while (runner.runActiveNow && System.currentTimeMillis() < deadline) {
            scheduler.advanceUntilIdle()
            Thread.sleep(20)
        }

        runner.runActiveNow shouldBe false
    }

    /**
     * Cancellation racing the terminal path. The tick loop reads the record, evaluates it to a pass,
     * and only then finalizes — a cancel committing in that gap must be what the run ends as, because
     * finalization records durable evidence and then clears the record it was cancelled in.
     */
    @Test
    fun `a cancel committed before finalization downgrades even a pass`() = runBlocking<Unit> {
        val runStore = deps.runStore()
        val evidenceStore = deps.qualificationEvidenceStore()
        val runner = runner(Dispatchers.Unconfined)
        runStore.put(record(runId = "run-2", token = "token-2"))
        // What the tick loop read before it evaluated; the cancel lands after it.
        runStore.currentRun()!!.cancelled shouldBe false
        runStore.requestCancel()

        // Contained: the very last thing finalization does is push the widget/tile surfaces, which
        // Glance cannot complete in this environment. Everything asserted below is decided before it.
        runCatching { runner.finish(RunTerminal.Passed) }

        runner.lastResult.value?.terminal shouldBe RunTerminal.Aborted(AbortReason.USER_CANCELLED)
        evidenceStore.currentState() shouldBe QualificationEvidenceState.Absent
        runStore.currentRun() shouldBe null
    }

    /**
     * The finalization claim is durable, and a process can die between taking it and clearing the
     * record — a window that spans a policy write, slow on a Shizuku adapter. Left alone, that record
     * is permanent: every terminal path finds it already claimed and returns, so the run reads as
     * running forever, charge rules never evaluate again, no session or further run can start, and the
     * baseline the run owes is never restored. Startup repair therefore reclaims it by run id.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `a run abandoned mid-finalization by a dead process is reclaimed and closed out`() {
        val runStore = deps.runStore()
        // The scheduler is deliberately never advanced: the runner's own startup repair stays queued,
        // so what this asserts is one repair, invoked here, rather than a race between two.
        val runner = runner(StandardTestDispatcher(TestCoroutineScheduler()))
        runBlocking {
            runStore.put(
                record(runId = "run-3", token = "token-3").copy(
                    finalizing = true,
                    provenance = WorkProvenance(
                        token = "a-dead-process",
                        pid = 2,
                        bootCount = 1,
                        createdAtMillis = 1_000L,
                    ),
                ),
            )

            runner.startupRepair()

            runStore.currentRun() shouldBe null
            runner.isRunning() shouldBe false
        }
    }

    /**
     * The same permanent wedge reached from the other side. Finalization is not only fallible, it is
     * **cancellable**: the production caller is the charge service's own lifecycle scope, and
     * `onDestroy` cancels it across a window that spans the restore write. A claim left held by that
     * cancellation is indistinguishable from one held by a live finalization — this process's own
     * provenance means startup repair will not reclaim it — so every terminal attempt is refused for
     * the rest of the process's life.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `a finalization cancelled after its claim gives the claim back`() {
        val runStore = deps.runStore()
        // Never advanced: the runner's own startup repair stays queued, so the only thing the stepping
        // below moves is the finish() under test.
        val runner = runner(StandardTestDispatcher(TestCoroutineScheduler()))
        runBlocking { runStore.put(record(runId = "run-4", token = "token-4")) }

        // finish() runs on a dispatcher this test hands one continuation at a time, which turns "cancel
        // somewhere inside finalization" from a race into a step: when finalization's opening line has
        // been logged the claim is committed and the coroutine is parked in the restore that follows.
        val steps = StepDispatcher()
        val watcher = LogWatcher("Run run-4 finished")
        Logging.install(watcher)
        val job = try {
            val job = CoroutineScope(steps).launch { runner.finish(RunTerminal.Passed) }
            stepUntil(steps) { watcher.seen } shouldBe true
            runBlocking { runStore.currentRun()?.finalizing } shouldBe true
            job.cancel()
            stepUntil(steps) { job.isCompleted } shouldBe true
            job
        } finally {
            Logging.remove(watcher)
        }

        job.isCancelled shouldBe true
        runBlocking {
            runStore.currentRun()?.finalizing shouldBe false
            // The outcome it decided stays on the record, so the next attempt replays it instead of
            // inventing one from a later tick's readings.
            runStore.currentRun()?.finalization?.toTerminal() shouldBe RunTerminal.Passed
            // The run can be closed out again, by an ordinary terminal path rather than a reclaim.
            runStore.claimForFinalization(
                FinalizationIntent.of(RunTerminal.Aborted(AbortReason.FINALIZATION_INTERRUPTED), 1L),
            )?.runId shouldBe "run-4"
        }
    }

    /**
     * A run is never resumable across a process boundary. Startup repair normally closes a foreign
     * record out before the first tick, but its finalization can fail and leave the record stored and
     * unclaimed — and then the tick loop would measure a dead process's anchors, hold clock and
     * baseline rate, and could end the run Passed or Refuted on observations this process never made.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `a tick closes out a record this process does not own instead of measuring it`() {
        val runStore = deps.runStore()
        // Never advanced, so the runner's own startup repair cannot be what closes the record out.
        val runner = runner(StandardTestDispatcher(TestCoroutineScheduler()))
        runBlocking {
            runStore.put(
                record(runId = "run-5", token = "token-5").copy(
                    phase = RunPhase.BASELINE,
                    provenance = WorkProvenance(
                        token = "a-dead-process",
                        pid = 2,
                        bootCount = 1,
                        createdAtMillis = 1_000L,
                    ),
                ),
            )

            // Contained for the same reason as above: the surface push at the very end cannot complete
            // in this environment, and everything asserted here is decided before it.
            runCatching { runner.onTick(RawQualificationTick(sessionActive = false)) }

            runner.lastResult.value?.terminal shouldBe RunTerminal.Aborted(AbortReason.PROCESS_DEATH)
            // Closed out exactly as it was found: the engine was never asked to judge it.
            runner.lastResult.value?.record?.phase shouldBe RunPhase.BASELINE
            runStore.currentRun() shouldBe null
        }
    }

    /**
     * The same permanent wedge again, this time with nothing cancelled and nobody dead. Finalization
     * clears the record and the `finally` gives the claim back — **both are store writes**, so a store
     * that cannot be written loses them together. The record is then claimed with this process's own
     * provenance: startup repair skips it (not foreign), every ordinary terminal path refuses it
     * (already claimed), and nothing retries once the store recovers. The app would read a run as live
     * for the rest of the process's life — rules suspended, no session, no further run, and the
     * baseline this run owes never restored.
     *
     * So a claimed record is close-out-only on the next tick, which is the retry the store recovery has
     * no other way of reaching — and that retry **replays the terminal the run decided**, which is
     * written down with the claim. Substituting an abort here would tell the user the run ended for a
     * reason it did not, and on a pass or a refutation it would contradict evidence already on disk.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `a claim left behind by a failed release is replayed, not aborted, by the next tick`() {
        val flaky = FlakyStore(
            PreferenceDataStoreFactory.create(scope = storeScope) {
                File(tempFolder.newFolder(), "run.preferences_pb")
            },
        )
        val runStore = QualificationRunStore(AppDataStore(flaky), SerializationModule.json())
        // Never advanced, so the runner's own startup repair cannot be what closes the record out —
        // and it would not anyway: the record carries this process's provenance.
        val runner = runner(StandardTestDispatcher(TestCoroutineScheduler()), runStore = runStore)
        runBlocking {
            runStore.put(record(runId = "run-6", token = "token-6").copy(phase = RunPhase.BASELINE))

            // One write left, then the store is full: the claim commits, and everything finalization
            // wants to write after it — clearing the record, and the `finally` handing the claim back —
            // fails.
            flaky.writeBudget = 1
            runCatching { runner.finish(RunTerminal.Aborted(AbortReason.RUN_CEILING)) }

            flaky.writeBudget = FlakyStore.UNLIMITED
            runStore.currentRun()?.finalizing shouldBe true
            // A recovered store changes nothing on its own: the ordinary terminal path is still refused.
            runStore.claimForFinalization(FinalizationIntent.of(RunTerminal.Passed, 1L)) shouldBe null
            runner.clearResult()

            // Contained like the cases above: the surface push at the very end of finalization cannot
            // complete in this environment, and everything asserted here is decided before it.
            runCatching { runner.onTick(RawQualificationTick(sessionActive = false)) }

            runner.lastResult.value?.terminal shouldBe RunTerminal.Aborted(AbortReason.RUN_CEILING)
            // Closed out as it was found: the engine was never asked to judge a half-finalized record.
            runner.lastResult.value?.record?.phase shouldBe RunPhase.BASELINE
            runStore.currentRun() shouldBe null
        }
    }

    /**
     * The reporting half of the same failure, and the one that matters most. Finalization writes the
     * evidence *before* it clears the record, so a store that fails in between leaves a pass that
     * licenses charge control on disk while the record says only "a finalization started". Inventing
     * an abort there tells the user nothing was recorded about a run that did earn its pass.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `a pass interrupted after its evidence write is replayed as a pass`() {
        val flaky = FlakyStore(
            PreferenceDataStoreFactory.create(scope = storeScope) {
                File(tempFolder.newFolder(), "run.preferences_pb")
            },
        )
        val runStore = QualificationRunStore(AppDataStore(flaky), SerializationModule.json())
        val evidenceStore = deps.qualificationEvidenceStore()
        val runner = runner(StandardTestDispatcher(TestCoroutineScheduler()), runStore = runStore)
        runBlocking {
            runStore.put(
                record(runId = "run-7", token = "token-7").copy(
                    buildIdentity = deps.buildIdentity().current(),
                    phase = RunPhase.CUT_2,
                    signal = FlowSignal.COUNTER,
                    observedHoldPercent = 70,
                ),
            )

            // The claim spends the last write; the evidence store has its own, so the pass lands and
            // then the clear and the release both fail.
            flaky.writeBudget = 1
            runCatching { runner.finish(RunTerminal.Passed) }

            flaky.writeBudget = FlakyStore.UNLIMITED
            (evidenceStore.currentState() is QualificationEvidenceState.Present) shouldBe true
            // Only the replay may put this back, so a stale value cannot be what the assertion reads.
            runner.clearResult()

            runCatching { runner.onTick(RawQualificationTick(sessionActive = false)) }

            runner.lastResult.value?.terminal shouldBe RunTerminal.Passed
            runStore.currentRun() shouldBe null
            val state = evidenceStore.currentState()
            (state as QualificationEvidenceState.Present).evidence.capPercent shouldBe 70
        }
    }

    /** The same for a refutation, which is terminal: dropping it would hand control back for good. */
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `a refutation interrupted after its evidence write is replayed as a refutation`() {
        val flaky = FlakyStore(
            PreferenceDataStoreFactory.create(scope = storeScope) {
                File(tempFolder.newFolder(), "run.preferences_pb")
            },
        )
        val runStore = QualificationRunStore(AppDataStore(flaky), SerializationModule.json())
        val enforcementStore = deps.enforcementEvidenceStore()
        val runner = runner(StandardTestDispatcher(TestCoroutineScheduler()), runStore = runStore)
        runBlocking {
            runStore.put(
                record(runId = "run-8", token = "token-8").copy(
                    buildIdentity = deps.buildIdentity().current(),
                    phase = RunPhase.CUT_1,
                    signal = FlowSignal.COUNTER,
                    observedHoldPercent = 75,
                ),
            )

            flaky.writeBudget = 1
            runCatching { runner.finish(RunTerminal.Refuted) }

            flaky.writeBudget = FlakyStore.UNLIMITED
            runner.clearResult()

            runCatching { runner.onTick(RawQualificationTick(sessionActive = false)) }

            runner.lastResult.value?.terminal shouldBe RunTerminal.Refuted
            runStore.currentRun() shouldBe null
            val state = enforcementStore.currentState()
            (state as EnforcementEvidenceState.Present).evidence.verdict shouldBe EnforcementVerdict.REFUTED
            state.evidence.observedPercent shouldBe 75
        }
    }

    /**
     * A record claimed by a build that had no intent to write down. There is nothing to replay, so the
     * close-out keeps saying what it can honestly say: a finalization started and did not finish.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `a claim with no recorded outcome still closes out as interrupted`() {
        val runStore = deps.runStore()
        val runner = runner(StandardTestDispatcher(TestCoroutineScheduler()), runStore = runStore)
        runBlocking {
            runStore.put(
                record(runId = "run-9", token = "token-9").copy(
                    phase = RunPhase.BASELINE,
                    finalizing = true,
                ),
            )

            runCatching { runner.onTick(RawQualificationTick(sessionActive = false)) }

            runner.lastResult.value?.terminal shouldBe RunTerminal.Aborted(AbortReason.FINALIZATION_INTERRUPTED)
            runStore.currentRun() shouldBe null
        }
    }

    /**
     * A process death between deciding a terminal and writing it out is the same interruption as a
     * failed store write, and gets the same answer. The measurement was over and the verdict durably
     * decided before the death, so the run finishes here rather than becoming
     * [AbortReason.PROCESS_DEATH] — which for a refutation would leave the device holding control it
     * demonstrably does not honour.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `a foreign record carrying an outcome is replayed rather than aborted`() {
        val runStore = deps.runStore()
        // Never advanced, so the runner's own startup repair cannot be what closes the record out.
        val runner = runner(StandardTestDispatcher(TestCoroutineScheduler()), runStore = runStore)
        runBlocking {
            runStore.put(
                record(runId = "run-10", token = "token-10").copy(
                    buildIdentity = deps.buildIdentity().current(),
                    phase = RunPhase.CUT_2,
                    signal = FlowSignal.COUNTER,
                    observedHoldPercent = 70,
                    finalizing = true,
                    finalization = FinalizationIntent.of(RunTerminal.Passed, 1_700_000_000_000L),
                    provenance = WorkProvenance(
                        token = "a-dead-process",
                        pid = 2,
                        bootCount = 1,
                        createdAtMillis = 1_000L,
                    ),
                ),
            )

            runCatching { runner.onTick(RawQualificationTick(sessionActive = false)) }

            runner.lastResult.value?.terminal shouldBe RunTerminal.Passed
            runStore.currentRun() shouldBe null
            val state = deps.qualificationEvidenceStore().currentState()
            // Stamped from the decision, not from this process's clock, so a replay is byte-identical.
            (state as QualificationEvidenceState.Present).evidence.completedAtWallMillis shouldBe
                1_700_000_000_000L
        }
    }

    /**
     * The measurement is persisted **with** the claim precisely so a replay can publish it, and this
     * is the composition of the two: the closing sample is merged into the record at claim time, the
     * clear then fails, and the replay in the next tick must hand the surfaces that stored record —
     * its advanced phase, its closing readings and the phase-log row the merge appended — rather than
     * only the terminal.
     *
     * The progress deliberately changes the phase, because that is the arm of the merge that writes to
     * the phase log. No engine terminal advances the phase today; what is pinned here is the runner's
     * own contract that whatever the caller hands [QualificationRunner.finish] survives to the replay,
     * not an engine sequence.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `a replayed finalization publishes the measurement persisted with its claim`() {
        val flaky = FlakyStore(
            PreferenceDataStoreFactory.create(scope = storeScope) {
                File(tempFolder.newFolder(), "run.preferences_pb")
            },
        )
        val runStore = QualificationRunStore(AppDataStore(flaky), SerializationModule.json())
        val runner = runner(StandardTestDispatcher(TestCoroutineScheduler()), runStore = runStore)
        runBlocking {
            val stored = record(runId = "run-11", token = "token-11").copy(
                phase = RunPhase.RESUME,
                commanded = ChargePolicy.FixedLimit(85).stableId,
                phaseStartedAtWallMillis = 5_000L,
                windowStartPercent = 72,
                windowStartCounter = 1_000_000,
                signal = FlowSignal.COUNTER,
                baselineRatePerHour = 9_000L,
            )
            runStore.put(stored)
            val advanced = stored.toProgress().copy(
                phase = RunPhase.CUT_2,
                phaseStartedAt = 9_000L,
                commanded = ChargePolicy.FixedLimit(70),
                commandedAt = 9_000L,
                commandAckedAt = 0L,
                windowAnchoredAt = 0L,
                windowStartPercent = -1,
                windowStartCounter = null,
                observedHoldPercent = 71,
            )

            // One write left: the claim commits with the merge folded into it, and the clear and the
            // release that follow both fail.
            flaky.writeBudget = 1
            runCatching {
                runner.finish(
                    terminal = RunTerminal.Inconclusive(InconclusiveReason.NO_RECUT),
                    progress = advanced,
                    phaseRatePerHour = 4_200L,
                    exitPercent = 74,
                    exitCounter = 1_050_000,
                )
            }

            flaky.writeBudget = FlakyStore.UNLIMITED
            // Only the replay may put this back, so a stale value cannot be what the assertions read.
            runner.clearResult()

            runCatching { runner.onTick(RawQualificationTick(sessionActive = false)) }

            val replayed = runner.lastResult.value
            replayed?.terminal shouldBe RunTerminal.Inconclusive(InconclusiveReason.NO_RECUT)
            replayed?.record?.phase shouldBe RunPhase.CUT_2
            replayed?.record?.observedHoldPercent shouldBe 71
            replayed?.record?.phaseLog shouldBe listOf(
                PhaseRecord(
                    phase = RunPhase.RESUME,
                    commanded = ChargePolicy.FixedLimit(85).stableId,
                    enteredAtWallMillis = 5_000L,
                    entryPercent = 72,
                    entryCounter = 1_000_000,
                    exitAtWallMillis = 9_000L,
                    exitPercent = 74,
                    exitCounter = 1_050_000,
                    ratePerHour = 4_200L,
                ),
            )
            runStore.currentRun() shouldBe null
        }
    }

    /**
     * A replay can run in a process started by an **app update**, and the versions the evidence is
     * stamped with decide whether it still licenses anything. Taking them from today's constants would
     * present a measurement made by a superseded protocol as one the current protocol produced —
     * exactly the hole the version stamp exists to close, reopened through the replay door.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `a replayed pass is stamped with the protocol version that measured it`() {
        val runStore = deps.runStore()
        // Its own store, so what the assertions read is this replay's write and nothing another case
        // left in the process-wide one.
        val evidenceData = freshDataStore()
        val evidenceStore = QualificationEvidenceStore(evidenceData, deps.buildIdentity(), SerializationModule.json())
        val runner = runner(
            StandardTestDispatcher(TestCoroutineScheduler()),
            runStore = runStore,
            evidenceStore = evidenceStore,
        )
        runBlocking {
            runStore.put(
                record(runId = "run-12", token = "token-12").copy(
                    buildIdentity = deps.buildIdentity().current(),
                    protocolVersion = QualificationProtocol.PROTOCOL_VERSION - 1,
                    phase = RunPhase.CUT_2,
                    signal = FlowSignal.COUNTER,
                    observedHoldPercent = 70,
                    finalizing = true,
                    finalization = FinalizationIntent.of(RunTerminal.Passed, 1_700_000_000_000L),
                    provenance = deadProcess(),
                ),
            )

            runCatching { runner.onTick(RawQualificationTick(sessionActive = false)) }

            runner.lastResult.value?.terminal shouldBe RunTerminal.Passed
            evidenceData.raw(QUALIFICATION_EVIDENCE_KEY) shouldContain
                """"protocolVersion":${QualificationProtocol.PROTOCOL_VERSION - 1}"""
            // And the store, not the runner, is what decides what that pass is worth: a superseded
            // protocol's pass licenses nothing.
            evidenceStore.currentState() shouldBe QualificationEvidenceState.Absent
        }
    }

    /**
     * The same across an update for a refutation, where the stamp routes the verdict into
     * `EnforcementEvidenceStore`'s per-version migration instead of past it.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `a replayed refutation is stamped with the algorithm version that measured it`() {
        val runStore = deps.runStore()
        // Its own store, for the same reason as above — and here it is load-bearing: a refutation is
        // terminal for its scope, so one left by another case would make this write a no-op.
        val enforcementData = freshDataStore()
        val enforcementStore = EnforcementEvidenceStore(enforcementData, deps.buildIdentity(), SerializationModule.json())
        val runner = runner(
            StandardTestDispatcher(TestCoroutineScheduler()),
            runStore = runStore,
            enforcementStore = enforcementStore,
        )
        runBlocking {
            runStore.put(
                record(runId = "run-13", token = "token-13").copy(
                    buildIdentity = deps.buildIdentity().current(),
                    enforcementAlgorithmVersion = EnforcementVerdictEngine.ALGORITHM_VERSION - 1,
                    phase = RunPhase.CUT_1,
                    signal = FlowSignal.COUNTER,
                    observedHoldPercent = 75,
                    finalizing = true,
                    finalization = FinalizationIntent.of(RunTerminal.Refuted, 1_700_000_000_000L),
                    provenance = deadProcess(),
                ),
            )

            runCatching { runner.onTick(RawQualificationTick(sessionActive = false)) }

            runner.lastResult.value?.terminal shouldBe RunTerminal.Refuted
            enforcementData.raw(ENFORCEMENT_EVIDENCE_KEY) shouldContain
                """"algorithmVersion":${EnforcementVerdictEngine.ALGORITHM_VERSION - 1}"""
            // Written as version 1, it goes through the store's own migration — which keeps a
            // refutation, restamped — rather than being laundered into version 2 by the runner.
            val state = enforcementStore.currentState()
            (state as EnforcementEvidenceState.Present).evidence.algorithmVersion shouldBe
                EnforcementVerdictEngine.ALGORITHM_VERSION
        }
    }

    /** A store that stops accepting writes once its [writeBudget] runs out, the way a full one does. */
    private class FlakyStore(private val delegate: DataStore<Preferences>) : DataStore<Preferences> {
        @Volatile var writeBudget: Int = UNLIMITED

        override val data: Flow<Preferences> get() = delegate.data

        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences {
            if (writeBudget == 0) throw IOException("No space left on device")
            if (writeBudget > 0) writeBudget--
            return delegate.updateData(transform)
        }

        companion object {
            const val UNLIMITED = -1
        }
    }

    /** Runs one dispatched continuation at a time, so a cancellation can be placed exactly. */
    private class StepDispatcher : kotlinx.coroutines.CoroutineDispatcher() {
        private val queued = LinkedBlockingQueue<Runnable>()

        override fun dispatch(context: kotlin.coroutines.CoroutineContext, block: Runnable) {
            queued.add(block)
        }

        fun step(): Boolean = queued.poll(200L, TimeUnit.MILLISECONDS)?.also { it.run() } != null
    }

    private class LogWatcher(private val marker: String) : Logging.Logger {
        @Volatile var seen: Boolean = false

        override fun log(
            priority: Logging.Priority,
            tag: String,
            message: String,
            metadata: Map<String, Any>?,
        ) {
            if (message.contains(marker)) seen = true
        }
    }

    private fun stepUntil(steps: StepDispatcher, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + 10_000L
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return true
            steps.step()
        }
        return condition()
    }

    private fun runner(
        dispatcher: kotlinx.coroutines.CoroutineDispatcher,
        runStore: QualificationRunStore = deps.runStore(),
        evidenceStore: QualificationEvidenceStore = deps.qualificationEvidenceStore(),
        enforcementStore: EnforcementEvidenceStore = deps.enforcementEvidenceStore(),
    ) = QualificationRunner(
        context = context,
        repository = deps.repository(),
        runStore = runStore,
        evidenceStore = evidenceStore,
        enforcementStore = enforcementStore,
        fullChargeStore = deps.fullChargeStore(),
        buildIdentity = deps.buildIdentity(),
        batteryReader = deps.batteryReader(),
        ruleApplier = deps.ruleApplier(),
        processIdentity = deps.processIdentity(),
        bootCountProvider = deps.bootCountProvider(),
        dispatcher = dispatcher,
    )

    /** Provenance of a process that is gone, so the record is close-out-only on the next tick. */
    private fun deadProcess() = WorkProvenance(
        token = "a-dead-process",
        pid = 2,
        bootCount = 1,
        createdAtMillis = 1_000L,
    )

    /**
     * A store of this test's own. The injected one is a process-wide singleton whose backing file
     * outlives a single case here, so evidence assertions have to stand on a store nobody else wrote.
     */
    private fun freshDataStore() = AppDataStore(
        PreferenceDataStoreFactory.create(scope = storeScope) {
            File(tempFolder.newFolder(), "evidence.preferences_pb")
        },
    )

    /** The stored evidence exactly as it was written, which is where the version stamp is visible. */
    private suspend fun AppDataStore.raw(key: String): String =
        store.data.first()[stringPreferencesKey(key)] ?: ""

    private fun postedTitle(service: ChargeSessionService): String? =
        shadowOf(service).lastForegroundNotification?.extras?.getString(Notification.EXTRA_TITLE)

    private fun awaitNotification(service: ChargeSessionService, title: String): Boolean = runBlocking {
        withTimeoutOrNull(10_000L) {
            while (postedTitle(service) != title) delay(20)
            true
        } ?: false
    }

    private fun neverNotified(
        service: ChargeSessionService,
        title: String,
        forMillis: Long = 1_000L,
    ): Boolean = runBlocking {
        withTimeoutOrNull(forMillis) {
            while (postedTitle(service) != title) delay(20)
            false
        } ?: true
    }
}
