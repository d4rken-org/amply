package eu.darken.amply.charging.core.qualification

import android.app.Notification
import android.content.Context
import android.content.Intent
import android.os.BatteryManager
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
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
import eu.darken.amply.charging.core.enforcement.EnforcementEvidenceStore
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
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
            // The run can be closed out again, by an ordinary terminal path rather than a reclaim.
            runStore.claimForFinalization()?.runId shouldBe "run-4"
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
     * no other way of reaching.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `a claim left behind by a failed release is closed out by the next tick`() {
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
            runStore.claimForFinalization() shouldBe null

            // Contained like the cases above: the surface push at the very end of finalization cannot
            // complete in this environment, and everything asserted here is decided before it.
            runCatching { runner.onTick(RawQualificationTick(sessionActive = false)) }

            runner.lastResult.value?.terminal shouldBe RunTerminal.Aborted(AbortReason.FINALIZATION_INTERRUPTED)
            // Closed out as it was found: the engine was never asked to judge a half-finalized record.
            runner.lastResult.value?.record?.phase shouldBe RunPhase.BASELINE
            runStore.currentRun() shouldBe null
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
    ) = QualificationRunner(
        context = context,
        repository = deps.repository(),
        runStore = runStore,
        evidenceStore = deps.qualificationEvidenceStore(),
        enforcementStore = deps.enforcementEvidenceStore(),
        fullChargeStore = deps.fullChargeStore(),
        buildIdentity = deps.buildIdentity(),
        batteryReader = deps.batteryReader(),
        ruleApplier = deps.ruleApplier(),
        processIdentity = deps.processIdentity(),
        bootCountProvider = deps.bootCountProvider(),
        dispatcher = dispatcher,
    )

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
