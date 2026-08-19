package eu.darken.amply.main.ui.qualification

import android.os.BatteryManager
import eu.darken.amply.battery.core.BatteryReadout
import eu.darken.amply.charging.core.qualification.IneligibleReason
import eu.darken.amply.charging.core.qualification.RunEligibility
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

/**
 * The lifecycle of the live figures: *when* the battery is polled, and for how long.
 *
 * Both properties this pins are cost properties. A poll is a battery read plus — on the pre-check — a
 * full eligibility resolution, which reads the stores and can make a Shizuku binder round trip. The
 * old shape kept that running for the Activity's lifetime and stopped it at the start of a run, which
 * is exactly backwards: it polled where nothing was on screen, and froze the figures on the one screen
 * that shows them for an hour.
 */
class QualificationLiveFiguresTest {

    private val steps = MutableStateFlow(QualificationStep.INTRO)
    private val readings = MutableStateFlow(readout(40))

    /** How many times the battery flow was subscribed, and how many of those are still running. */
    private var subscriptions = 0
    private var running = 0
    private var eligibilityCalls = 0
    private var eligibility: RunEligibility = RunEligibility.Ineligible(IneligibleReason.BATTERY_LEVEL, 73)

    /** Opened by a test that needs the first resolution to be slow. */
    private var eligibilityGate: CompletableDeferred<Unit>? = null

    private fun readouts(): Flow<BatteryReadout> = readings
        .onStart {
            subscriptions++
            running++
        }
        .onCompletion { running-- }

    private suspend fun resolveEligibility(): RunEligibility {
        eligibilityGate?.await()
        eligibilityCalls++
        return eligibility
    }

    private fun figures() = liveFigures(
        steps = steps,
        readouts = ::readouts,
        resolveEligibility = ::resolveEligibility,
    )

    @Test
    fun `only the steps that show figures poll the battery`() = runTest {
        val seen = mutableListOf<LiveFigures>()
        val job = launch { figures().collect { seen += it } }
        runCurrent()
        subscriptions shouldBe 0

        steps.value = QualificationStep.PRECHECK
        runCurrent()
        running shouldBe 1

        steps.value = QualificationStep.RUNNING
        runCurrent()
        running shouldBe 1
        subscriptions shouldBe 2

        steps.value = QualificationStep.RESULT
        runCurrent()
        running shouldBe 0

        job.cancel()
    }

    /**
     * The reason the collection has to be lifecycle-aware rather than rooted at the composition root:
     * the step says nothing about whether the screen is visible, so only the collector's lifetime can
     * stop the polling.
     */
    @Test
    fun `losing the collector stops the polling`() = runTest {
        val job = launch { figures().collect { } }
        steps.value = QualificationStep.PRECHECK
        runCurrent()
        running shouldBe 1

        job.cancel()
        runCurrent()

        running shouldBe 0
        // And the step it was left on cannot restart it on its own.
        readings.value = readout(41)
        runCurrent()
        running shouldBe 0
    }

    /** F16: the running screen renders these figures for the 30-90 minutes a run takes. */
    @Test
    fun `the running step tracks every fresh reading`() = runTest {
        val seen = mutableListOf<LiveFigures>()
        val job = launch { figures().collect { seen += it } }
        steps.value = QualificationStep.RUNNING
        runCurrent()
        seen.last().readout?.levelPercent shouldBe 40

        readings.value = readout(55)
        runCurrent()
        seen.last().readout?.levelPercent shouldBe 55

        readings.value = readout(70)
        runCurrent()
        seen.last().readout?.levelPercent shouldBe 70

        job.cancel()
    }

    /** The block belongs to the pre-check, and resolving eligibility costs what the run screen must not pay. */
    @Test
    fun `the running step neither builds the block nor re-resolves eligibility`() = runTest {
        val seen = mutableListOf<LiveFigures>()
        val job = launch { figures().collect { seen += it } }
        steps.value = QualificationStep.RUNNING
        readings.value = readout(55)
        runCurrent()

        seen.last().precheck shouldBe null
        seen.last().eligibility shouldBe null
        eligibilityCalls shouldBe 0

        job.cancel()
    }

    @Test
    fun `the pre-check re-resolves eligibility on every reading and carries it into the block`() = runTest {
        val seen = mutableListOf<LiveFigures>()
        val job = launch { figures().collect { seen += it } }
        steps.value = QualificationStep.PRECHECK
        runCurrent()
        eligibilityCalls shouldBe 1
        seen.last().precheck?.currentPercent shouldBe 40
        seen.last().precheck?.requiredPercent shouldBe 73
        seen.last().eligibility shouldBe eligibility

        readings.value = readout(73)
        runCurrent()
        eligibilityCalls shouldBe 2
        seen.last().precheck?.currentPercent shouldBe 73

        job.cancel()
    }

    /**
     * The first reading of a polling step waits on a battery read and an eligibility resolution. The
     * step change itself must not wait with it, or the screen would still be rendering the previous
     * step while both land.
     */
    @Test
    fun `a step change is emitted before its first figures are resolved`() = runTest {
        val gate = CompletableDeferred<Unit>()
        eligibilityGate = gate
        val seen = mutableListOf<LiveFigures>()
        val job = launch { figures().collect { seen += it } }
        runCurrent()
        val beforeStepChange = seen.size

        steps.value = QualificationStep.PRECHECK
        runCurrent()

        // The step change landed on its own, with the resolution still in flight behind the gate.
        seen.size shouldBe beforeStepChange + 1
        seen.last() shouldBe LiveFigures()

        gate.complete(Unit)
        runCurrent()
        seen.last().precheck?.currentPercent shouldBe 40

        job.cancel()
    }

    private companion object {
        fun readout(level: Int) = BatteryReadout(
            levelPercent = level,
            status = BatteryManager.BATTERY_STATUS_CHARGING,
            plugged = BatteryManager.BATTERY_PLUGGED_AC,
        )
    }
}
