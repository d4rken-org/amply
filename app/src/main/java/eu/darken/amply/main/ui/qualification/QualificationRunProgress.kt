package eu.darken.amply.main.ui.qualification

import androidx.annotation.StringRes
import eu.darken.amply.R
import eu.darken.amply.charging.core.qualification.RunPhase
import eu.darken.amply.charging.core.qualification.RunShape

/**
 * Where the current phase sits in the run: "step [index] of [total]", plus a short name for it.
 *
 * A run takes half an hour to an hour and a half, so the one thing the screen has to answer is how
 * far along it is. A progress bar alone can't: it fills once per phase and starts over, which reads
 * as no progress at all.
 */
data class RunStepUi(
    val index: Int,
    val total: Int,
    @get:StringRes val nameRes: Int,
)

/**
 * The phases a run of this shape actually walks, in order.
 *
 * [RunPhase.PREFLIGHT] is deliberately not one of them: nothing has been written yet, so counting it
 * would promise a step the run may never take. [RunPhase.CHARGE_UP] only exists on a fixed-cap run,
 * which is why the total differs by shape rather than being a constant.
 */
private fun runPhases(shape: RunShape): List<RunPhase> = when (shape) {
    RunShape.VARIABLE_CAP -> listOf(RunPhase.BASELINE, RunPhase.CUT_1, RunPhase.RESUME, RunPhase.CUT_2)
    RunShape.FIXED_CAP -> listOf(
        RunPhase.CHARGE_UP,
        RunPhase.BASELINE,
        RunPhase.CUT_1,
        RunPhase.RESUME,
        RunPhase.CUT_2,
    )
}

/**
 * The step caption for [phase] in a run of [shape], or null when the phase is not a step of that run
 * — [RunPhase.PREFLIGHT] in both shapes, and [RunPhase.CHARGE_UP] in a variable-cap run, which never
 * charges up.
 *
 * Pure, and unit-tested for both shapes, because getting the total wrong is the kind of error that
 * only shows up on a device an hour into a run.
 */
internal fun runStep(phase: RunPhase, shape: RunShape): RunStepUi? {
    val phases = runPhases(shape)
    val index = phases.indexOf(phase)
    if (index < 0) return null
    val nameRes = phase.stepNameRes() ?: return null
    return RunStepUi(index = index + 1, total = phases.size, nameRes = nameRes)
}

/**
 * A few words naming what the run is doing, for the step caption. Deliberately separate from
 * [RunPhase.messageRes], which is the full explanatory sentence the paragraph and the notification
 * render.
 */
@StringRes
private fun RunPhase.stepNameRes(): Int? = when (this) {
    RunPhase.PREFLIGHT -> null
    RunPhase.CHARGE_UP -> R.string.qualification_phase_step_charge_up
    RunPhase.BASELINE -> R.string.qualification_phase_step_baseline
    RunPhase.CUT_1 -> R.string.qualification_phase_step_cut_1
    RunPhase.RESUME -> R.string.qualification_phase_step_resume
    RunPhase.CUT_2 -> R.string.qualification_phase_step_cut_2
}
