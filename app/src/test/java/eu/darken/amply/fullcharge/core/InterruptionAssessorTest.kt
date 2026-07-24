package eu.darken.amply.fullcharge.core

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import eu.darken.amply.charging.core.ChargePolicy
import eu.darken.amply.common.AppDataStore
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

// Robolectric because ProcessIdentity reads android.os.Process.myPid(); the store deps run against
// an in-memory DataStore and the exit/boot seams are plain fakes, so the sequencing stays testable.
@RunWith(RobolectricTestRunner::class)
class InterruptionAssessorTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val identity = ProcessIdentity()
    private val fixedLimit = ChargePolicy.FixedLimit(80)

    private var bootCount: Int? = 5
    private var exits: List<ExitRecord> = emptyList()

    private lateinit var appDataStore: AppDataStore
    private lateinit var fullChargeStore: FullChargeStore
    private lateinit var interruptionStore: InterruptionStore
    private lateinit var assessor: InterruptionAssessor

    @Before
    fun setup() {
        appDataStore = AppDataStore(
            PreferenceDataStoreFactory.create(scope = scope) { File(tmp.newFolder(), "t.preferences_pb") },
        )
        fullChargeStore = FullChargeStore(appDataStore)
        interruptionStore = InterruptionStore(appDataStore)
        assessor = InterruptionAssessor(
            fullChargeStore = fullChargeStore,
            interruptionStore = interruptionStore,
            exitSource = ExitSource { exits },
            identity = identity,
            bootCountProvider = BootCountProvider { bootCount },
        )
    }

    @After
    fun teardown() {
        scope.cancel()
    }

    private fun deadProvenance(pid: Int = 4321, createdAt: Long = 1_000L, boot: Int? = 5) =
        WorkProvenance(token = "dead-process", pid = pid, bootCount = boot, createdAtMillis = createdAt)

    @Test
    fun `a legacy session record is adopted silently with no event`() = runTest {
        fullChargeStore.startSession(fixedLimit, startedAtMillis = 1_000L, provenance = null)

        assessor.captureSessionPickup(fullChargeStore.currentSession()!!) shouldBe null
        fullChargeStore.currentSession()!!.provenance!!.token shouldBe identity.token
        interruptionStore.event.first() shouldBe null
    }

    @Test
    fun `a same-process session pickup is adopted with no assessment`() = runTest {
        fullChargeStore.startSession(
            fixedLimit,
            startedAtMillis = 1_000L,
            provenance = WorkProvenance(identity.token, identity.pid, 5, 1_000L),
        )

        assessor.captureSessionPickup(fullChargeStore.currentSession()!!) shouldBe null
    }

    @Test
    fun `a session pickup across a reboot is adopted with no assessment`() = runTest {
        bootCount = 6
        fullChargeStore.startSession(fixedLimit, startedAtMillis = 1_000L, provenance = deadProvenance(boot = 5))

        assessor.captureSessionPickup(fullChargeStore.currentSession()!!) shouldBe null
        fullChargeStore.currentSession()!!.provenance!!.token shouldBe identity.token
    }

    @Test
    fun `a session pickup across a same-boot death yields an assessment`() = runTest {
        bootCount = 5
        fullChargeStore.startSession(fixedLimit, startedAtMillis = 1_000L, provenance = deadProvenance(boot = 5))

        assessor.captureSessionPickup(fullChargeStore.currentSession()!!).shouldNotBeNull()
    }

    @Test
    fun `onSessionDecision CONTINUE adopts and records nothing`() = runTest {
        bootCount = 5
        fullChargeStore.startSession(fixedLimit, startedAtMillis = 1_000L, provenance = deadProvenance(boot = 5))
        val assessment = assessor.captureSessionPickup(fullChargeStore.currentSession()!!)

        assessor.onSessionDecision(assessment, SessionDecision.CONTINUE)

        interruptionStore.event.first() shouldBe null
        fullChargeStore.currentSession()!!.provenance!!.token shouldBe identity.token
    }

    @Test
    fun `onSessionDecision MARK_CONNECTED flags connected and adopts`() = runTest {
        bootCount = 5
        fullChargeStore.startSession(fixedLimit, startedAtMillis = 1_000L, provenance = deadProvenance(boot = 5))
        val assessment = assessor.captureSessionPickup(fullChargeStore.currentSession()!!)

        assessor.onSessionDecision(assessment, SessionDecision.MARK_CONNECTED)

        val session = fullChargeStore.currentSession()!!
        session.connectedSeen shouldBe true
        session.provenance!!.token shouldBe identity.token
        interruptionStore.event.first() shouldBe null
    }

    @Test
    fun `onSessionRestoreFinished success records a restored-late event with the classified reason`() = runTest {
        bootCount = 5
        exits = listOf(ExitRecord(timestampMillis = 2_000L, pid = 4321, reason = 10))
        fullChargeStore.startSession(
            fixedLimit,
            startedAtMillis = 1_000L,
            workId = "wid-1",
            provenance = deadProvenance(boot = 5),
        )
        val assessment = assessor.captureSessionPickup(fullChargeStore.currentSession()!!)

        assessor.onSessionRestoreFinished(assessment, success = true)

        val event = interruptionStore.event.first()!!
        event.outcome shouldBe InterruptionOutcome.RESTORED_LATE
        // The stable work id — not the dead process's owner token — is the correlation handle.
        event.workId shouldBe "wid-1"
        event.reason shouldBe InterruptionReason.USER_STOPPED
    }

    @Test
    fun `adoption does not break a later restore upgrading the event`() = runTest {
        bootCount = 5
        fullChargeStore.startSession(
            fixedLimit,
            startedAtMillis = 1_000L,
            workId = "wid-1",
            provenance = deadProvenance(boot = 5),
        )
        val assessment = assessor.captureSessionPickup(fullChargeStore.currentSession()!!)

        // A failed restore records STILL_PENDING and adopts the session (owner token → this process),
        // but the work id must survive so a later restore can still correlate.
        assessor.onSessionRestoreFinished(assessment, success = false)
        val session = fullChargeStore.currentSession()!!
        session.provenance!!.token shouldBe identity.token
        session.workId shouldBe "wid-1"

        assessor.onRestoreSucceeded(session.workId)
        interruptionStore.event.first()!!.outcome shouldBe InterruptionOutcome.RESTORED_LATE
    }

    @Test
    fun `onSessionRestoreFinished failure records still-pending and OTHER without a matching exit`() = runTest {
        bootCount = 5
        fullChargeStore.startSession(fixedLimit, startedAtMillis = 1_000L, provenance = deadProvenance(boot = 5))
        val assessment = assessor.captureSessionPickup(fullChargeStore.currentSession()!!)

        assessor.onSessionRestoreFinished(assessment, success = false)

        val event = interruptionStore.event.first()!!
        event.outcome shouldBe InterruptionOutcome.STILL_PENDING
        event.reason shouldBe InterruptionReason.OTHER
    }

    @Test
    fun `an assessment is consumed once`() = runTest {
        bootCount = 5
        fullChargeStore.startSession(fixedLimit, startedAtMillis = 1_000L, provenance = deadProvenance(boot = 5))
        val assessment = assessor.captureSessionPickup(fullChargeStore.currentSession()!!)

        // First handler consumes it; the second must be ignored (no event recorded).
        assessor.onSessionDecision(assessment, SessionDecision.CONTINUE)
        assessor.onSessionRestoreFinished(assessment, success = true)

        interruptionStore.event.first() shouldBe null
    }

    @Test
    fun `a recovery pickup across a same-boot death maps the flow result to an outcome`() = runTest {
        bootCount = 5
        fullChargeStore.setPendingRecoveryTarget(
            ChargePolicy.Unrestricted,
            workId = "wid-r",
            provenance = deadProvenance(boot = 5),
        )
        val pickup = assessor.captureRecoveryPickup()
        pickup.assessment.shouldNotBeNull()

        assessor.onRecoveryFinished(
            pickup,
            BootRecoveryFlow.Result(
                outcome = BootRecoveryFlow.Outcome.GAVE_UP,
                restoreAttempted = false,
                rewrites = 0,
                retryRemaining = false,
            ),
        )

        interruptionStore.event.first()!!.outcome shouldBe InterruptionOutcome.UNCONFIRMED
    }

    @Test
    fun `a recovery pickup without provenance is adopted with no assessment`() = runTest {
        bootCount = 5
        fullChargeStore.setPendingRecoveryTarget(ChargePolicy.Unrestricted, provenance = null)

        assessor.captureRecoveryPickup().assessment shouldBe null
        fullChargeStore.pendingRecoveryProvenance()!!.token shouldBe identity.token
    }

    @Test
    fun `onRecoveryFinished records nothing when the flow did no work`() = runTest {
        bootCount = 5
        fullChargeStore.setPendingRecoveryTarget(
            ChargePolicy.Unrestricted,
            workId = "wid-r",
            provenance = deadProvenance(boot = 5),
        )
        val pickup = assessor.captureRecoveryPickup()

        assessor.onRecoveryFinished(
            pickup,
            BootRecoveryFlow.Result(
                outcome = BootRecoveryFlow.Outcome.CONVERGED,
                restoreAttempted = false,
                rewrites = 0,
                retryRemaining = false,
            ),
        )

        interruptionStore.event.first() shouldBe null
    }

    @Test
    fun `a converged recovery resolves a prior event even without an assessment`() = runTest {
        bootCount = 5
        // A prior still-pending event for this work.
        interruptionStore.record(
            InterruptionEvent(1_000L, InterruptionReason.OTHER, InterruptionOutcome.STILL_PENDING, "wid-r"),
        )
        // A same-process recovery pickup (no death) → no assessment, but it carries the work id.
        fullChargeStore.setPendingRecoveryTarget(
            ChargePolicy.Unrestricted,
            workId = "wid-r",
            provenance = WorkProvenance(identity.token, identity.pid, 5, 5L),
        )
        val pickup = assessor.captureRecoveryPickup()
        pickup.assessment shouldBe null

        assessor.onRecoveryFinished(
            pickup,
            BootRecoveryFlow.Result(
                outcome = BootRecoveryFlow.Outcome.CONVERGED,
                restoreAttempted = true,
                rewrites = 0,
                retryRemaining = false,
            ),
        )

        interruptionStore.event.first()!!.outcome shouldBe InterruptionOutcome.RESTORED_LATE
    }

    @Test
    fun `a throwing interruption store never propagates`() = runTest {
        val throwing = object : InterruptionStore(appDataStore) {
            override suspend fun record(event: InterruptionEvent) {
                throw RuntimeException("boom")
            }
        }
        val assessorWithThrowingStore = InterruptionAssessor(
            fullChargeStore = fullChargeStore,
            interruptionStore = throwing,
            exitSource = ExitSource { exits },
            identity = identity,
            bootCountProvider = BootCountProvider { bootCount },
        )
        bootCount = 5
        fullChargeStore.startSession(fixedLimit, startedAtMillis = 1_000L, provenance = deadProvenance(boot = 5))
        val assessment = assessorWithThrowingStore.captureSessionPickup(fullChargeStore.currentSession()!!)

        // Must not throw despite record() failing.
        assessorWithThrowingStore.onSessionRestoreFinished(assessment, success = true)
    }

    @Test
    fun `onRestoreSucceeded ignores a null token and upgrades a matching event`() = runTest {
        interruptionStore.record(
            InterruptionEvent(1_000L, InterruptionReason.OTHER, InterruptionOutcome.STILL_PENDING, "tok"),
        )

        assessor.onRestoreSucceeded(null)
        interruptionStore.event.first()!!.outcome shouldBe InterruptionOutcome.STILL_PENDING

        assessor.onRestoreSucceeded("tok")
        interruptionStore.event.first()!!.outcome shouldBe InterruptionOutcome.RESTORED_LATE
    }

    @Test
    fun `onExplicitPolicyWrite clears a pending event`() = runTest {
        interruptionStore.record(
            InterruptionEvent(1_000L, InterruptionReason.OTHER, InterruptionOutcome.STILL_PENDING, "tok"),
        )

        assessor.onExplicitPolicyWrite()

        interruptionStore.event.first() shouldBe null
    }
}
