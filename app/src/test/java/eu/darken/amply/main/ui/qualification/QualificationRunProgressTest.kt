package eu.darken.amply.main.ui.qualification

import eu.darken.amply.R
import eu.darken.amply.charging.core.ChargePolicy
import eu.darken.amply.charging.core.qualification.RunPhase
import eu.darken.amply.charging.core.qualification.RunShape
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class QualificationRunProgressTest {

    @Test
    fun `a variable-cap run is four steps`() {
        runStep(RunPhase.BASELINE, RunShape.VARIABLE_CAP) shouldBe
            RunStepUi(index = 1, total = 4, nameRes = R.string.qualification_phase_step_baseline)
        runStep(RunPhase.CUT_1, RunShape.VARIABLE_CAP) shouldBe
            RunStepUi(index = 2, total = 4, nameRes = R.string.qualification_phase_step_cut_1)
        runStep(RunPhase.RESUME, RunShape.VARIABLE_CAP) shouldBe
            RunStepUi(index = 3, total = 4, nameRes = R.string.qualification_phase_step_resume)
        runStep(RunPhase.CUT_2, RunShape.VARIABLE_CAP) shouldBe
            RunStepUi(index = 4, total = 4, nameRes = R.string.qualification_phase_step_cut_2)
    }

    /** The charge-up shifts every later step by one, which is the whole reason the total is derived. */
    @Test
    fun `a fixed-cap run is five steps, led by the charge-up`() {
        runStep(RunPhase.CHARGE_UP, RunShape.FIXED_CAP) shouldBe
            RunStepUi(index = 1, total = 5, nameRes = R.string.qualification_phase_step_charge_up)
        runStep(RunPhase.BASELINE, RunShape.FIXED_CAP) shouldBe
            RunStepUi(index = 2, total = 5, nameRes = R.string.qualification_phase_step_baseline)
        runStep(RunPhase.CUT_1, RunShape.FIXED_CAP) shouldBe
            RunStepUi(index = 3, total = 5, nameRes = R.string.qualification_phase_step_cut_1)
        runStep(RunPhase.RESUME, RunShape.FIXED_CAP) shouldBe
            RunStepUi(index = 4, total = 5, nameRes = R.string.qualification_phase_step_resume)
        runStep(RunPhase.CUT_2, RunShape.FIXED_CAP) shouldBe
            RunStepUi(index = 5, total = 5, nameRes = R.string.qualification_phase_step_cut_2)
    }

    /**
     * Preflight is not a step in either shape: nothing has been written yet, so counting it would
     * promise the user a step the run may never take.
     */
    @Test
    fun `preflight is not a step`() {
        runStep(RunPhase.PREFLIGHT, RunShape.VARIABLE_CAP) shouldBe null
        runStep(RunPhase.PREFLIGHT, RunShape.FIXED_CAP) shouldBe null
    }

    /** A variable-cap run never charges up, so the phase has no position there. */
    @Test
    fun `charge-up is not a step of a variable-cap run`() {
        runStep(RunPhase.CHARGE_UP, RunShape.VARIABLE_CAP) shouldBe null
    }

    /** Every phase is accounted for in both shapes — either a step, or explicitly not one. */
    @Test
    fun `every phase resolves in both shapes`() {
        RunShape.entries.forEach { shape ->
            RunPhase.entries.forEach { phase ->
                val step = runStep(phase, shape)
                when {
                    phase == RunPhase.PREFLIGHT -> step shouldBe null
                    phase == RunPhase.CHARGE_UP && shape == RunShape.VARIABLE_CAP -> step shouldBe null
                    else -> (step != null && step.index in 1..step.total) shouldBe true
                }
            }
        }
    }

    private fun progress(
        phase: RunPhase = RunPhase.CUT_1,
        commanded: ChargePolicy? = ChargePolicy.FixedLimit(70),
        commandAcked: Boolean = false,
    ) = RunProgressUi(
        phase = phase,
        shape = RunShape.VARIABLE_CAP,
        lowCap = 70,
        elapsedMillis = 0,
        phaseElapsedMillis = 0,
        phaseBudgetMillis = 1,
        percent = 80,
        charging = true,
        commanded = commanded,
        commandAcked = commandAcked,
    )

    /**
     * The phase is recorded before its write is dispatched, so until the write is acknowledged the
     * screen may not render the phase sentence, which says the limit *is* set.
     */
    @Test
    fun `an unacknowledged limit write says it is being set`() {
        runMessage(progress(commanded = ChargePolicy.FixedLimit(70))) shouldBe
            RunMessageUi(R.string.qualification_running_applying_limit, 70)
    }

    /** The pending sentence follows what was commanded, not which phase commanded it. */
    @Test
    fun `an unacknowledged release says the limit is being removed`() {
        runMessage(progress(phase = RunPhase.RESUME, commanded = ChargePolicy.Unrestricted)) shouldBe
            RunMessageUi(R.string.qualification_running_removing_limit, null)
    }

    /** A raised cap is still a limit being set, which is why the argument comes from the command. */
    @Test
    fun `an unacknowledged raise names the commanded cap, not the run's low cap`() {
        runMessage(progress(phase = RunPhase.RESUME, commanded = ChargePolicy.FixedLimit(90))) shouldBe
            RunMessageUi(R.string.qualification_running_applying_limit, 90)
    }

    /** Once the write has landed the phase's own sentence is what the user gets, unchanged. */
    @Test
    fun `an acknowledged write falls through to the phase message`() {
        runMessage(progress(commandAcked = true)) shouldBe
            RunMessageUi(R.string.qualification_phase_cut_1, 70)
    }

    /** Preflight commands nothing, so there is no write to be pending on. */
    @Test
    fun `a phase that commanded nothing keeps its phase message`() {
        runMessage(progress(phase = RunPhase.PREFLIGHT, commanded = null)) shouldBe
            RunMessageUi(R.string.qualification_phase_preflight, 70)
    }
}
