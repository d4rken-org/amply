package eu.darken.amply.main.ui.qualification

import eu.darken.amply.R
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
}
