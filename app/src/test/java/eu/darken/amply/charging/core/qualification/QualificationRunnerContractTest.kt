package eu.darken.amply.charging.core.qualification

import android.app.Notification
import android.content.Context
import android.content.Intent
import android.os.BatteryManager
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
import eu.darken.amply.fullcharge.core.BootCountProvider
import eu.darken.amply.fullcharge.core.ChargeSessionService
import eu.darken.amply.fullcharge.core.FullChargeStore
import eu.darken.amply.fullcharge.core.ProcessIdentity
import eu.darken.amply.fullcharge.core.RecoveryOrigin
import eu.darken.amply.fullcharge.core.WorkProvenance
import eu.darken.amply.rules.core.RuleApplier
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

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

    private fun runner(dispatcher: kotlinx.coroutines.CoroutineDispatcher) = QualificationRunner(
        context = context,
        repository = deps.repository(),
        runStore = deps.runStore(),
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
