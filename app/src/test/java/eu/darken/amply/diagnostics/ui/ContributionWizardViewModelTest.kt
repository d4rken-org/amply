package eu.darken.amply.diagnostics.ui

import eu.darken.amply.charging.core.DeviceInfo
import eu.darken.amply.charging.core.access.BackendStatus
import eu.darken.amply.charging.core.access.SettingNamespace
import eu.darken.amply.common.ca.toCaString
import eu.darken.amply.diagnostics.core.CaptureResult
import eu.darken.amply.diagnostics.core.ContributionRepository
import eu.darken.amply.diagnostics.core.DeviceContext
import eu.darken.amply.diagnostics.core.Disclosure
import eu.darken.amply.diagnostics.core.SettingId
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class ContributionWizardViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private val repo = FakeContributionRepository()

    @BeforeEach fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterEach fun tearDown() = Dispatchers.resetMain()

    private fun vm() = ContributionWizardViewModel(repo)

    /** A VM whose Shizuku status has been refreshed to ready (capture is gated on it). */
    private fun readyVm(): ContributionWizardViewModel = vm().also {
        it.refreshStatus()
        dispatcher.scheduler.advanceUntilIdle()
    }

    private fun global(key: String) = SettingId(SettingNamespace.GLOBAL, key)
    private fun secure(key: String) = SettingId(SettingNamespace.SECURE, key)

    @Test
    fun `blank label is rejected without capturing`() = runTest(dispatcher.scheduler) {
        val vm = vm()
        vm.setPendingLabel("   ")
        vm.captureMode()
        advanceUntilIdle()
        vm.state.value.labelError.shouldNotBeNull()
        vm.state.value.modes.shouldBeEmpty()
    }

    @Test
    fun `duplicate label is rejected`() = runTest(dispatcher.scheduler) {
        val vm = readyVm()
        repo.captureQueue.add(CaptureResult.Success(mapOf(global("protect_battery") to "0")))
        vm.setPendingLabel("Off"); vm.captureMode(); dispatcher.scheduler.advanceUntilIdle()
        vm.setPendingLabel("off"); vm.captureMode(); dispatcher.scheduler.advanceUntilIdle()
        vm.state.value.modes.size shouldBe 1
        vm.state.value.labelError.shouldNotBeNull()
    }

    @Test
    fun `successful capture appends a mode with a change count`() = runTest(dispatcher.scheduler) {
        val vm = readyVm()
        repo.captureQueue.add(CaptureResult.Success(mapOf(global("protect_battery") to "0")))
        repo.captureQueue.add(CaptureResult.Success(mapOf(global("protect_battery") to "1")))
        vm.setPendingLabel("off"); vm.captureMode(); dispatcher.scheduler.advanceUntilIdle()
        vm.setPendingLabel("max"); vm.captureMode(); dispatcher.scheduler.advanceUntilIdle()
        vm.state.value.modes.map { it.label } shouldBe listOf("off", "max")
        vm.state.value.modes[1].changedFromPrevious shouldBe 1
    }

    @Test
    fun `a reset during an in-flight capture discards the stale result`() = runTest(dispatcher.scheduler) {
        val vm = readyVm()
        val gate = CompletableDeferred<Unit>()
        repo.gate = gate
        repo.captureQueue.add(CaptureResult.Success(mapOf(global("protect_battery") to "0")))
        vm.setPendingLabel("off"); vm.captureMode()
        dispatcher.scheduler.advanceUntilIdle() // parked awaiting the gate
        // Sanity: the capture is genuinely in flight (busy), so this proves the discard, not the shizuku guard.
        vm.state.value.busy shouldBe true
        vm.restartSession() // bumps the generation and cancels the job
        gate.complete(Unit)
        dispatcher.scheduler.advanceUntilIdle()
        vm.state.value.modes.shouldBeEmpty()
    }

    @Test
    fun `review classifies known charge keys auto and unknown keys redacted`() = runTest(dispatcher.scheduler) {
        val vm = reachedReviewWith(
            a = mapOf(global("protect_battery") to "0", secure("mystery") to "x"),
            b = mapOf(global("protect_battery") to "1", secure("mystery") to "y"),
        )
        val byId = vm.state.value.review.associateBy { it.id }
        byId.getValue(global("protect_battery")).disclosure shouldBe Disclosure.AUTO
        byId.getValue(secure("mystery")).disclosure shouldBe Disclosure.REDACTED
        // A redacted row starts hidden and excluded.
        byId.getValue(secure("mystery")).revealed shouldBe false
        byId.getValue(secure("mystery")).included shouldBe false
    }

    @Test
    fun `include requires reveal first (two-stage)`() = runTest(dispatcher.scheduler) {
        val vm = reachedReviewWith(
            a = mapOf(secure("mystery") to "x"),
            b = mapOf(secure("mystery") to "y"),
        )
        val id = secure("mystery")
        vm.toggleInclude(id) // before reveal → ignored
        vm.state.value.review.single { it.id == id }.included shouldBe false
        vm.revealRow(id)
        vm.state.value.review.single { it.id == id }.let {
            it.revealed shouldBe true
            it.values.shouldNotBeNull()
        }
        vm.toggleInclude(id)
        vm.state.value.review.single { it.id == id }.included shouldBe true
    }

    @Test
    fun `delivery builds a report and a launchable url`() = runTest(dispatcher.scheduler) {
        val vm = reachedReviewWith(
            a = mapOf(global("protect_battery") to "0"),
            b = mapOf(global("protect_battery") to "1"),
        )
        vm.goNext() // REVIEW -> DELIVER
        advanceUntilIdle()
        vm.state.value.reportText.shouldNotBeNull() shouldContain "protect_battery"
        vm.state.value.issueUrl.shouldNotBeNull()
    }

    /** Advances the wizard to the REVIEW step with two captured modes. */
    private fun reachedReviewWith(
        a: Map<SettingId, String>,
        b: Map<SettingId, String>,
    ): ContributionWizardViewModel = runReview(a, b)

    private fun runReview(a: Map<SettingId, String>, b: Map<SettingId, String>): ContributionWizardViewModel {
        val vm = vm()
        // We are inside runTest already; drive via the scheduler through advanceUntilIdle in callers.
        vm.refreshStatus()
        dispatcher.scheduler.advanceUntilIdle()
        vm.goNext() // INTRO -> DETAILS
        vm.goNext() // DETAILS -> CAPTURE
        repo.captureQueue.add(CaptureResult.Success(a))
        repo.captureQueue.add(CaptureResult.Success(b))
        vm.setPendingLabel("a"); vm.captureMode(); dispatcher.scheduler.advanceUntilIdle()
        vm.setPendingLabel("b"); vm.captureMode(); dispatcher.scheduler.advanceUntilIdle()
        vm.goNext() // CAPTURE -> REVIEW
        return vm
    }

    private class FakeContributionRepository : ContributionRepository {
        val captureQueue = ArrayDeque<CaptureResult>()
        var gate: CompletableDeferred<Unit>? = null
        var status = BackendStatus(available = true, granted = true, detail = "ready".toCaString())
        var device = DeviceContext(
            DeviceInfo(manufacturer = "Samsung", model = "SM-X210", sdk = 36, fingerprint = "fp"),
            adapterId = "samsung-lab",
        )

        override suspend fun status(): BackendStatus = status
        override suspend fun captureSnapshot(): CaptureResult {
            gate?.await()
            return captureQueue.removeFirst()
        }
        override fun deviceContext(): DeviceContext = device
    }
}
