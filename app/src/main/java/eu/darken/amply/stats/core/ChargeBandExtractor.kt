package eu.darken.amply.stats.core

import android.os.BatteryManager

/**
 * One recorded sample, reduced to what step extraction needs. Deliberately not [ChargeCurvePoint]:
 * the curve carries no battery status, and the status is what separates a real 1% step from a
 * protection hold, a thermal pause or a supply too weak to charge.
 */
data class ChargeStepSample(
    val elapsedMillis: Long,
    val percent: Int?,
    val batteryStatus: Int?,
)

/**
 * How long one completed 1% step took during one session, with the identity needed to weigh it:
 * [sessionId] because a rate must be a median across *sessions*, and [chargingType] because a
 * wireless charge and a 65 W wired one describe different devices.
 */
data class BandObservation(
    val sessionId: Long,
    val chargingType: ChargingType,
    /** The level the step started at, so 80 means "the 80 → 81 step". */
    val percentFrom: Int,
    val millis: Long,
)

/** A batch of observations plus each contributing session's recorded average power. */
data class BandObservationBatch(
    val observations: List<BandObservation>,
    val sessionPowerMilliwatts: Map<Long, Int>,
)

/**
 * Turns one session's samples into completed 1% step durations.
 *
 * A step is the interval from the first sample at level L to the first sample at L+1, and it counts
 * only under a strict state machine:
 *
 * - **The first step of a run is discarded.** Recording starts partway through whatever level the
 *   session begins at, so that step is systematically too short. The same argument applies after any
 *   break in the run, where the level's true start was equally unobserved — so a step counts only
 *   when its start is a transition this extractor actually saw.
 * - **A step spanning a non-charging sample is discarded.** This is the hold filter: a device held at
 *   an OEM limit, paused for heat, or on a supply too weak to charge reports something other than
 *   `BATTERY_STATUS_CHARGING`, and folding those minutes into a rate would describe a charger nobody
 *   owns. A duration cutoff cannot do this job — it both admits holds shorter than the cutoff and
 *   throws away genuinely slow trickle charging, which would bias the estimate optimistic in exactly
 *   the 80-100% stretch this feature exists to describe.
 * - [MIN_STEP_MILLIS] / [MAX_STEP_MILLIS] are backstops for readings no charger produces (a real 1%
 *   step on a 65 W charger is roughly 35 seconds).
 * - A null percent, a multi-percent jump, a level decrease or time running backwards breaks the run.
 * - A step already recorded for this session is never recorded twice, so a 79 → 80 → 79 → 80 wobble
 *   contributes one observation rather than two.
 *
 * A session held at a limit therefore contributes nothing structurally: it never completes the step
 * out of the level it is held at.
 */
object ChargeBandExtractor {

    /** Below this a "step" is a reporting glitch, not a charge. */
    const val MIN_STEP_MILLIS = 5_000L

    /** Above this the device was not meaningfully charging, whatever it reported. */
    const val MAX_STEP_MILLIS = 3_600_000L

    fun extract(
        sessionId: Long,
        chargingType: ChargingType,
        samples: List<ChargeStepSample>,
    ): List<BandObservation> {
        val observations = mutableListOf<BandObservation>()
        val recorded = mutableSetOf<Int>()

        var lastElapsed: Long? = null
        var lastLevel: Int? = null
        // The level whose start we observed as a real transition, and when. Null means "no step is
        // open" — a run start, or a break.
        var openLevel: Int? = null
        var openStartMillis = 0L
        // False once a non-charging sample has been seen inside the currently open step.
        var openCharging = true

        samples.forEach { sample ->
            val previousElapsed = lastElapsed
            lastElapsed = sample.elapsedMillis
            if (previousElapsed != null && sample.elapsedMillis < previousElapsed) {
                // The clock base changed under us, so nothing before this is comparable to it. The
                // new stamp becomes the base rather than being ignored, or every later sample would
                // keep failing against a timeline that no longer exists.
                lastLevel = null
                openLevel = null
                return@forEach
            }

            val percent = sample.percent
            if (percent == null) {
                lastLevel = null
                openLevel = null
                return@forEach
            }
            val charging = sample.batteryStatus == BatteryManager.BATTERY_STATUS_CHARGING
            val previousLevel = lastLevel

            when {
                previousLevel == null -> Unit // Run start: no transition observed yet.

                percent == previousLevel -> if (!charging) openCharging = false

                percent == previousLevel + 1 -> {
                    if (openLevel == previousLevel) {
                        val millis = sample.elapsedMillis - openStartMillis
                        val clean = openCharging && charging
                        if (clean && millis in MIN_STEP_MILLIS..MAX_STEP_MILLIS && recorded.add(previousLevel)) {
                            observations += BandObservation(
                                sessionId = sessionId,
                                chargingType = chargingType,
                                percentFrom = previousLevel,
                                millis = millis,
                            )
                        }
                    }
                    // This transition is the observed start of the next step.
                    openLevel = percent
                    openStartMillis = sample.elapsedMillis
                    openCharging = charging
                }

                // A decrease, or a jump across levels we never saw the boundaries of.
                else -> openLevel = null
            }
            lastLevel = percent
        }
        return observations
    }
}
