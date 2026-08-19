package eu.darken.amply.charging.core.qualification

import android.app.Application
import android.app.Notification
import android.content.ComponentName
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
import eu.darken.amply.fullcharge.core.BootRecoveryEngine
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
import org.robolectric.shadows.ShadowSystemClock
import java.io.File
import java.io.IOException
import java.time.Duration
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
        const val RUN_KEY = "qualification.run.v1"
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
     * The same collision reached through the other door. Reopening the app dispatches `ACTION_CHECK`,
     * which resolves to boot recovery on *any* pending target — and during a run that target is the
     * run's own, registered before its first write. Boot recovery repays it and then **clears** it, so
     * the run keeps commanding experimental policies with nothing left owed, and its finalization
     * finds no owner and skips the restore: the device stays on the experimental cap for good.
     */
    @Test
    fun `boot recovery leaves a recovery target the live run owns alone`() {
        val runStore = deps.runStore()
        val fullChargeStore = deps.fullChargeStore()
        runBlocking {
            runStore.put(record(runId = "run-17", token = "token-17"))
            fullChargeStore.setPendingRecoveryTarget(
                policy = ChargePolicy.FixedLimit(80),
                workId = "run-17",
                origin = RecoveryOrigin.SESSION_RESTORE,
            )
        }

        val refused = LogWatcher("a qualification run owns the charge policy")
        val recovered = LogWatcher("Recovery: resuming convergence check")
        val controller = Robolectric.buildService(ChargeSessionService::class.java).create()
        val service = controller.get()
        Logging.install(refused)
        Logging.install(recovered)
        try {
            service.onStartCommand(
                Intent(context, ChargeSessionService::class.java).setAction(ChargeSessionService.ACTION_CHECK),
                0,
                1,
            )
            // The flow that would consume the target never started; the markers are sticky, so the
            // grace this spends also gives the refusal below time to appear.
            neverSeen(recovered) shouldBe true
            // And refusing is what proves the command was handled rather than merely slow.
            awaitSeen(refused) shouldBe true
        } finally {
            Logging.remove(refused)
            Logging.remove(recovered)
        }

        runBlocking {
            fullChargeStore.pendingRecoveryTarget() shouldBe ChargePolicy.FixedLimit(80)
            fullChargeStore.currentRecovery()?.workId shouldBe "run-17"
        }
        controller.destroy()

        // Refusing costs nothing because the run itself still owes the restore: its own close-out
        // finds the target still owned and writes the baseline.
        val writes = LogWatcher("apply(policy=")
        val runner = runner(StandardTestDispatcher(TestCoroutineScheduler()))
        runBlocking {
            // Re-stated rather than assumed: the service instance above fed its own runner ticks that
            // may have closed this record out. What the case is about is the target surviving, and the
            // close-out below has to start from a run that exists.
            runStore.put(record(runId = "run-17", token = "token-17"))
            Logging.install(writes)
            try {
                // Contained like the other finalization cases: the surface push at the very end cannot
                // complete in this environment, and the restore is decided long before it.
                runCatching { runner.finish(RunTerminal.Aborted(AbortReason.USER_CANCELLED)) }
            } finally {
                Logging.remove(writes)
            }

            writes.seen shouldBe true
            // The write cannot land here, so the target is still owed; the slot is process-wide.
            fullChargeStore.clearPendingRecoveryTarget()
        }
    }

    /**
     * The guard is scoped to a target the run owns, not to "a run exists". A session's or a widget
     * write's unfinished obligation is somebody else's, and a run happening at the same time is no
     * reason to leave it unpaid.
     */
    @Test
    fun `recovery of a target the run does not own still runs while a run is live`() {
        val runStore = deps.runStore()
        val fullChargeStore = deps.fullChargeStore()
        runBlocking {
            runStore.put(record(runId = "run-18", token = "token-18"))
            fullChargeStore.setPendingRecoveryTarget(
                policy = ChargePolicy.FixedLimit(80),
                workId = "widget-write-2",
                origin = RecoveryOrigin.USER_REQUEST,
            )
        }

        val refused = LogWatcher("a qualification run owns the charge policy")
        val recovered = LogWatcher("Recovery: resuming convergence check")
        val controller = Robolectric.buildService(ChargeSessionService::class.java).create()
        val service = controller.get()
        Logging.install(refused)
        Logging.install(recovered)
        try {
            service.onStartCommand(
                Intent(context, ChargeSessionService::class.java).setAction(ChargeSessionService.ACTION_CHECK),
                0,
                1,
            )
            awaitSeen(recovered) shouldBe true
            refused.seen shouldBe false
        } finally {
            Logging.remove(refused)
            Logging.remove(recovered)
        }
        controller.destroy()

        runBlocking {
            runStore.clear()
            fullChargeStore.clearPendingRecoveryTarget()
        }
    }

    /**
     * What the guard above costs when the close-out it defers to **fails**, which at boot is the
     * ordinary case rather than the exotic one: the backend or the provider is often not ready yet.
     *
     * The sequence: a process death or reboot mid-run, `BootReceiver` dispatches `ACTION_RECOVER`, the
     * guard refuses it because the stale record still owns the target, startup repair then closes that
     * record out and its single baseline write fails. The record is cleared regardless — and with it
     * goes `QualificationWatcher.isEnabled`, so the next `continueGestureOrStop` stops the service,
     * and that function decides from the session, the gesture and the watchers, never from a pending
     * recovery target. Nothing would re-dispatch recovery, and the device would sit on the run's
     * experimental policy — its deliberately less-protective release policy included — until the next
     * foreground launch or reboot.
     *
     * So the failed close-out hands the owed work back: it leaves the recovery target in place and
     * dispatches `ACTION_RECOVER` itself, **after** clearing the record, which is exactly what makes
     * the guard let it through this time. The second half below feeds the dispatched intent to a
     * service and watches it reach `BootRecoveryFlow`'s bounded rewrite loop.
     *
     * The two cases above cannot catch this: they reinstate the run by hand and call `finish`
     * directly, so neither ever runs the startup-repair-to-boot-recovery hand-off.
     *
     * What it does **not** prove: that recovery converges. It cannot here — no adapter can be driven
     * in this environment, which is also why the restore fails without being made to. The surviving
     * target is what stands for that failure, and convergence is `BootRecoveryFlowTest`'s question.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `a close-out whose restore fails hands the owed baseline to boot recovery`() {
        val runStore = deps.runStore()
        val fullChargeStore = deps.fullChargeStore()
        val application: Application = ApplicationProvider.getApplicationContext()
        // Never advanced, so this runner's own queued startup repair cannot be what closes the record
        // out — the repair under test is the one invoked below.
        val runner = runner(StandardTestDispatcher(TestCoroutineScheduler()), runStore = runStore)
        runBlocking {
            runStore.put(record(runId = "run-19", token = "token-19").copy(provenance = deadProcess()))
            fullChargeStore.setPendingRecoveryTarget(
                policy = ChargePolicy.FixedLimit(80),
                workId = "run-19",
                origin = RecoveryOrigin.SESSION_RESTORE,
            )
        }
        shadowOf(application).clearStartedServices()

        runBlocking {
            // Contained like the other finalization cases: the surface push at the very end cannot
            // complete in this environment, and everything asserted here is decided before it.
            runCatching { runner.startupRepair() }

            // The record is gone whether or not the restore worked, which is the whole problem.
            runStore.currentRun() shouldBe null
            // And the restore did not work, so the baseline is still owed and still owned by the run.
            fullChargeStore.pendingRecoveryTarget() shouldBe ChargePolicy.FixedLimit(80)
            fullChargeStore.currentRecovery()?.workId shouldBe "run-19"
        }

        val dispatched = shadowOf(application).nextStartedService
        dispatched.component shouldBe ComponentName(context, ChargeSessionService::class.java)
        dispatched.action shouldBe ChargeSessionService.ACTION_RECOVER

        // The hand-off arriving where it was sent: with the record cleared the ownership guard has
        // nothing to refuse, so this reaches the rewrite loop instead of being turned away again.
        val refused = LogWatcher("a qualification run owns the charge policy")
        val recovered = LogWatcher("Recovery: resuming convergence check")
        val controller = Robolectric.buildService(ChargeSessionService::class.java).create()
        Logging.install(refused)
        Logging.install(recovered)
        try {
            controller.get().onStartCommand(dispatched, 0, 1)
            awaitSeen(recovered) shouldBe true
            refused.seen shouldBe false
        } finally {
            Logging.remove(refused)
            Logging.remove(recovered)
        }
        controller.destroy()

        runBlocking {
            // The recovery slot is process-wide; the write cannot land here, so it is cleared by hand.
            fullChargeStore.clearPendingRecoveryTarget()
        }
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
            // This record was written straight into the store, so no recovery target names it and the
            // close-out owes nothing: the restore counts as complete and the result may say so.
            runner.lastResult.value?.restored shouldBe true
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

    /**
     * The record shape the version field's own introduction left behind: the finalization intent
     * shipped one build before `enforcementAlgorithmVersion`, so a record written in between carries a
     * replayable refutation *and* a zero version. Reading that zero as "whatever this build measures
     * at" would stamp the measurement with a later algorithm's version and impose a refutation that
     * algorithm never produced. Every build that could write this shape measured at version 2, so 2 is
     * what the replay pins — and `EnforcementEvidenceStore` decides from there what a version-2
     * verdict is still worth.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `a replayed refutation from a record written before the version field is stamped version 2`() {
        // Both stores are this test's own: the run record has to be written as raw JSON rather than
        // through the data class, and a refutation is terminal for its scope.
        val runData = freshDataStore()
        val runStore = QualificationRunStore(runData, SerializationModule.json())
        val enforcementData = freshDataStore()
        val enforcementStore = EnforcementEvidenceStore(enforcementData, deps.buildIdentity(), SerializationModule.json())
        val runner = runner(
            StandardTestDispatcher(TestCoroutineScheduler()),
            runStore = runStore,
            enforcementStore = enforcementStore,
        )
        runBlocking {
            // Hand-written, because today's data class cannot produce this shape: no
            // `enforcementAlgorithmVersion` key at all, next to an intent that must still be replayed.
            runData.writeRaw(
                RUN_KEY,
                """
                {"baseline":"fixed:80","runId":"run-16","runToken":"token-16",
                 "adapterId":"lineageos-chargingcontrol-v1",
                 "buildIdentity":"${deps.buildIdentity().current()}",
                 "protocolVersion":${QualificationProtocol.PROTOCOL_VERSION},
                 "phase":"CUT_1","lowCap":70,"releasePolicy":"fixed:85","signal":"COUNTER",
                 "observedHoldPercent":75,"finalizing":true,
                 "finalization":{"kind":"REFUTED","decidedAtWallMillis":1700000000000},
                 "provenance":{"token":"a-dead-process","pid":2,"bootCount":1,"createdAtMillis":1000}}
                """.trimIndent(),
            )
            // The premise of the case: it decodes, and it decodes unstamped.
            runStore.currentRun()?.enforcementAlgorithmVersion shouldBe 0

            runCatching { runner.onTick(RawQualificationTick(sessionActive = false)) }

            runner.lastResult.value?.terminal shouldBe RunTerminal.Refuted
            // The literal 2, not the constant: after the next algorithm bump this record must still be
            // stamped 2, because 2 is the version that measured it.
            enforcementData.raw(ENFORCEMENT_EVIDENCE_KEY) shouldContain """"algorithmVersion":2"""
        }
    }

    /**
     * The replay must not restore a policy the run no longer owes. The sequence it comes from:
     * finalization restored the baseline and cleared its recovery target, its record clear then
     * failed while the claim release succeeded, and before the next tick the user pressed a widget's
     * persistent-policy button — which is *not* refused while a run record exists and clears its own
     * recovery target once its write lands. Writing `record.baseline` again there would silently
     * revert that explicit choice, with nothing left owed to bring it back.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `a replayed finalization leaves a policy the run no longer owes alone`() {
        val runStore = deps.runStore()
        val fullChargeStore = deps.fullChargeStore()
        val runner = runner(StandardTestDispatcher(TestCoroutineScheduler()), runStore = runStore)
        val writes = LogWatcher("apply(policy=")
        runBlocking {
            runStore.put(
                record(runId = "run-14", token = "token-14").copy(
                    phase = RunPhase.CUT_2,
                    finalization = FinalizationIntent.of(
                        RunTerminal.Inconclusive(InconclusiveReason.NO_RECUT),
                        1_700_000_000_000L,
                    ),
                ),
            )
            // The widget press, as it leaves the store: its own recovery record, owned by it.
            fullChargeStore.setPendingRecoveryTarget(
                policy = ChargePolicy.Unrestricted,
                workId = "widget-write-1",
                origin = RecoveryOrigin.USER_REQUEST,
            )

            Logging.install(writes)
            try {
                runCatching { runner.onTick(RawQualificationTick(sessionActive = false)) }
            } finally {
                Logging.remove(writes)
            }

            // The terminal is still published and the record still closed out; only the write is skipped.
            runner.lastResult.value?.terminal shouldBe RunTerminal.Inconclusive(InconclusiveReason.NO_RECUT)
            runStore.currentRun() shouldBe null
            writes.seen shouldBe false
            // Nothing is owed by this run any more, so the result may say the setting is the user's
            // own again — skipping the write is a completed restore here, not a failed one.
            runner.lastResult.value?.restored shouldBe true
            // And the newer obligation is left exactly as its owner stored it.
            fullChargeStore.currentRecovery()?.workId shouldBe "widget-write-1"
            fullChargeStore.pendingRecoveryTarget() shouldBe ChargePolicy.Unrestricted

            // The recovery slot is process-wide here; leaving this one behind would follow the next case.
            fullChargeStore.clearPendingRecoveryTarget()
        }
    }

    /**
     * The ordinary half of the same guard: the run still owns the owed restore, so the replay does
     * write the baseline. It cannot succeed in this environment (no adapter can be driven here), which
     * is why the assertion is that the write was attempted at all — whether it lands is
     * `ChargingRepositoryRestoreGateTest`'s question.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `a replayed finalization still restores a baseline the run does owe`() {
        val runStore = deps.runStore()
        val fullChargeStore = deps.fullChargeStore()
        val runner = runner(StandardTestDispatcher(TestCoroutineScheduler()), runStore = runStore)
        val writes = LogWatcher("apply(policy=")
        runBlocking {
            runStore.put(
                record(runId = "run-15", token = "token-15").copy(
                    phase = RunPhase.CUT_2,
                    finalization = FinalizationIntent.of(
                        RunTerminal.Inconclusive(InconclusiveReason.NO_RECUT),
                        1_700_000_000_000L,
                    ),
                ),
            )
            fullChargeStore.setPendingRecoveryTarget(
                policy = ChargePolicy.FixedLimit(80),
                workId = "run-15",
                origin = RecoveryOrigin.SESSION_RESTORE,
            )

            Logging.install(writes)
            try {
                runCatching { runner.onTick(RawQualificationTick(sessionActive = false)) }
            } finally {
                Logging.remove(writes)
            }

            runner.lastResult.value?.terminal shouldBe RunTerminal.Inconclusive(InconclusiveReason.NO_RECUT)
            writes.seen shouldBe true
            // The write was attempted and could not land, so the published result must not tell the
            // user their setting is back: it is still on the run's value with the restore owed.
            runner.lastResult.value?.restored shouldBe false

            // The write cannot land here, so the target is still owed; the slot is process-wide.
            fullChargeStore.clearPendingRecoveryTarget()
        }
    }

    /**
     * The hand-off above, losing the race it should never have had to run.
     *
     * Clearing the run record is *itself* what turns the last watcher off, so the dispatch and the
     * service's own stop decision are triggered by the same event. A stop decision that runs a moment
     * after the clear calls `stopMonitoring`, which invalidates the queued work and calls `stopSelf`,
     * and `START_STICKY` redelivers a **null** intent rather than the `ACTION_RECOVER` that was in
     * flight. The device would then sit on the run's experimental policy with the baseline still owed.
     *
     * What separates this from the hand-off case above: there, startup repair finishes completely and
     * its captured intent is then fed to a **fresh** service instance, so the clear and the service's
     * processing never overlap. Here one live instance is already draining commands while the repair
     * runs on the test thread, and the dispatched intent is deliberately **never delivered** — the
     * shadow captures it, which is exactly the "the dispatch was lost" case. So what has to keep the
     * device recoverable is the service's own stop decision: an owed recovery target that no live run
     * owns starts recovery instead of stopping.
     *
     * The loop feeds ordinary monitor commands because a stop decision has to come from somewhere; in
     * production it is the terminal battery tick or the 30s poll. Either order is a pass for the
     * property: a decision that runs before the clear still sees the watcher enabled and keeps
     * monitoring, one that runs after must recover. It stops the moment the service stops itself, so a
     * later nudge cannot paper over a `stopSelf` that already happened.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `a stop decision racing the hand-off recovers the owed baseline instead of stopping`() {
        val runStore = deps.runStore()
        val fullChargeStore = deps.fullChargeStore()
        val application: Application = ApplicationProvider.getApplicationContext()
        // Never advanced, so this runner's own queued startup repair cannot be what closes the record
        // out — the repair under test is the one invoked below.
        val runner = runner(StandardTestDispatcher(TestCoroutineScheduler()), runStore = runStore)
        runBlocking {
            runStore.put(record(runId = "run-20", token = "token-20").copy(provenance = deadProcess()))
            fullChargeStore.setPendingRecoveryTarget(
                policy = ChargePolicy.FixedLimit(80),
                workId = "run-20",
                origin = RecoveryOrigin.SESSION_RESTORE,
            )
        }
        shadowOf(application).clearStartedServices()

        val recovered = LogWatcher("Recovery: resuming convergence check")
        val controller = Robolectric.buildService(ChargeSessionService::class.java).create()
        val service = controller.get()
        Logging.install(recovered)
        try {
            // Up and monitoring for the run: the record is what keeps QualificationWatcher enabled,
            // and this command is handled on the service's own dispatcher, not this thread.
            service.onStartCommand(monitorIntent(), 0, 1)
            // …so the repair below overlaps that processing. It closes the dead process's record out,
            // fails its single baseline write (nothing can write a policy in this environment), leaves
            // the target and dispatches ACTION_RECOVER — which the shadow captures and never delivers.
            runBlocking { runCatching { runner.startupRepair() } }

            val deadline = System.currentTimeMillis() + 20_000L
            while (
                !recovered.seen &&
                !shadowOf(service).isStoppedBySelf &&
                System.currentTimeMillis() < deadline
            ) {
                service.onStartCommand(monitorIntent(), 0, 1)
                Thread.sleep(50)
            }

            shadowOf(service).isStoppedBySelf shouldBe false
            recovered.seen shouldBe true
        } finally {
            Logging.remove(recovered)
        }
        controller.destroy()

        runBlocking {
            // The recovery slot is process-wide; the write cannot land here, so it is cleared by hand.
            fullChargeStore.clearPendingRecoveryTarget()
        }
    }

    /**
     * What the stop-decision check above must NOT do, and the reason it is gated on there being no
     * recovery in flight.
     *
     * A recovery job ends by calling that very same stop decision, and `BootRecoveryFlow` deliberately
     * **keeps** the pending target when a re-write fails, so the next service start can retry it. An
     * ungated check would therefore find an owed, unowned target in the recovery job's own tail and
     * start the whole flow again — a persistently failing restore turning into a permanent retry loop
     * at the flow's 25s/75s cadence, with the foreground service never stopping. The gate is invisible
     * in the happy path, so without this case it reads as a redundant condition.
     */
    @Test
    fun `a recovery whose re-write fails stops the service instead of restarting itself`() {
        val fullChargeStore = deps.fullChargeStore()
        runBlocking {
            // No run: this target is a widget write's obligation, so nothing owns it and the check
            // under test applies to it in full.
            deps.runStore().clear()
            fullChargeStore.setPendingRecoveryTarget(
                policy = ChargePolicy.FixedLimit(80),
                workId = "widget-write-3",
                origin = RecoveryOrigin.USER_REQUEST,
            )
        }

        val recovered = LogWatcher("Recovery: resuming convergence check")
        val finished = LogWatcher("Boot recovery outcome:")
        val controller = Robolectric.buildService(ChargeSessionService::class.java).create()
        val service = controller.get()
        Logging.install(recovered)
        Logging.install(finished)
        try {
            service.onStartCommand(recoverIntent(), 0, 1)
            awaitSeen(recovered) shouldBe true

            // The convergence loop decides from elapsedRealtime, which Robolectric holds still: advance
            // it past the verify delay so the next tick decides REWRITE rather than WAIT. One advance,
            // deliberately — overshooting the total budget would end the flow by giving up, which
            // clears the target and takes the looping state away.
            ShadowSystemClock.advanceBy(Duration.ofMillis(BootRecoveryEngine.VERIFY_DELAY_MILLIS + 5_000L))
            awaitSeen(finished, timeoutMillis = 60_000L) shouldBe true

            // The state that invites the loop: the re-write failed and the target is still owed.
            runBlocking { fullChargeStore.pendingRecoveryTarget() } shouldBe ChargePolicy.FixedLimit(80)
            // And the tail stopped instead of recovering again.
            awaitStoppedBySelf(service) shouldBe true
            recovered.count shouldBe 1
        } finally {
            Logging.remove(recovered)
            Logging.remove(finished)
        }
        controller.destroy()

        runBlocking { fullChargeStore.clearPendingRecoveryTarget() }
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

        /** How OFTEN the marker was logged — a repeat is how a restarted loop shows up. */
        @Volatile var count: Int = 0

        override fun log(
            priority: Logging.Priority,
            tag: String,
            message: String,
            metadata: Map<String, Any>?,
        ) {
            if (message.contains(marker)) {
                seen = true
                count++
            }
        }
    }

    /** Waits for a [LogWatcher]'s marker, the way [awaitNotification] waits for a notification. */
    private fun awaitSeen(watcher: LogWatcher, timeoutMillis: Long = 10_000L): Boolean = runBlocking {
        withTimeoutOrNull(timeoutMillis) {
            while (!watcher.seen) delay(20)
            true
        } ?: false
    }

    /** The absence of a marker, given the same grace a slow path would need to reach it. */
    private fun neverSeen(watcher: LogWatcher, forMillis: Long = 1_000L): Boolean =
        !awaitSeen(watcher, forMillis)

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

    /** Stores a record as raw JSON, for shapes today's data classes can no longer produce. */
    private suspend fun AppDataStore.writeRaw(key: String, value: String) {
        store.updateData { prefs ->
            prefs.toMutablePreferences().apply { set(stringPreferencesKey(key), value) }.toPreferences()
        }
    }

    private fun postedTitle(service: ChargeSessionService): String? =
        shadowOf(service).lastForegroundNotification?.extras?.getString(Notification.EXTRA_TITLE)

    private fun awaitNotification(service: ChargeSessionService, title: String): Boolean = runBlocking {
        withTimeoutOrNull(10_000L) {
            while (postedTitle(service) != title) delay(20)
            true
        } ?: false
    }

    private fun monitorIntent(): Intent =
        Intent(context, ChargeSessionService::class.java).setAction(ChargeSessionService.ACTION_MONITOR)

    private fun recoverIntent(): Intent =
        Intent(context, ChargeSessionService::class.java).setAction(ChargeSessionService.ACTION_RECOVER)

    /** Waits for the service to stop itself, the way [awaitSeen] waits for a log marker. */
    private fun awaitStoppedBySelf(
        service: ChargeSessionService,
        timeoutMillis: Long = 20_000L,
    ): Boolean = runBlocking {
        withTimeoutOrNull(timeoutMillis) {
            while (!shadowOf(service).isStoppedBySelf) delay(20)
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
