package eu.darken.amply.charging.core.qualification

import eu.darken.amply.charging.core.ChargeObservation
import eu.darken.amply.charging.core.ChargePolicy
import eu.darken.amply.charging.core.qualification.QualificationProtocol.CHARGE_UP_BUDGET_MILLIS
import eu.darken.amply.charging.core.qualification.QualificationProtocol.FIXED_CAP_ENTRY_MARGIN
import eu.darken.amply.charging.core.qualification.QualificationProtocol.HOLD_CONFIRM_MILLIS
import eu.darken.amply.charging.core.qualification.QualificationProtocol.MIN_PLAUSIBLE_FULL_MICROAMP_HOURS
import eu.darken.amply.charging.core.qualification.QualificationProtocol.OVERSHOOT_ALLOWANCE
import eu.darken.amply.charging.core.qualification.QualificationProtocol.PHASE_BUDGET_MILLIS
import eu.darken.amply.charging.core.qualification.QualificationProtocol.PREFLIGHT_BUDGET_MILLIS
import eu.darken.amply.charging.core.qualification.QualificationProtocol.RISE_FRACTION_DENOMINATOR
import eu.darken.amply.charging.core.qualification.QualificationProtocol.RUN_CEILING_MILLIS

/**
 * One evaluation tick. Everything the protocol depends on, and nothing Android-specific.
 *
 * [configured] is the adapter's *configured-state* readback and is only meaningful on a
 * `SYNC_READBACK` adapter; it exists to catch the user changing the setting natively mid-run. A null
 * value disables drift detection rather than failing the run, so an adapter without synchronous
 * readback simply does not get that check.
 */
data class QualificationSample(
    val nowMillis: Long,
    val plugged: Boolean,
    /** Battery level in percent, or -1 when unknown. An unknown level moves no state. */
    val percent: Int,
    /** Raw charge counter as the platform reported it; may be milli-scaled on a broken ROM. */
    val chargeCounter: Int?,
    val configured: ChargeObservation?,
    val sessionActive: Boolean,
    /** The host's last commanded write failed. */
    val writeFailed: Boolean = false,
    val cancelled: Boolean = false,
)

/**
 * Rolling run state. Threaded by the caller and persisted between ticks, exactly as
 * `EnforcementProgress` is — the engine itself holds nothing.
 *
 * [anchorPercent]/[anchorCounter] are the baseline the current phase measures accumulation against.
 * They are **reset whenever a rise is confirmed**, which is what lets a slow charge accumulate past
 * the rise threshold over minutes instead of needing to clear it within a single 30 s tick.
 */
data class QualificationProgress(
    val shape: RunShape,
    val phase: RunPhase,
    val runStartedAt: Long,
    val phaseStartedAt: Long,
    /** The protective cap this run commands, as a percent. */
    val lowCap: Int,
    /** What the resume phase writes: a higher cap on a variable adapter, Unrestricted on a fixed one. */
    val releasePolicy: ChargePolicy,
    val commanded: ChargePolicy? = null,
    val commandedAt: Long = 0L,
    val anchorPercent: Int = -1,
    val anchorCounter: Int? = null,
    /** When accumulation was last seen rising; a cut is confirmed once this is old enough. */
    val holdSince: Long = 0L,
    val signal: FlowSignal = FlowSignal.NONE,
    /** True when the adapter's value mapping is a guess (a candidate device), enabling the cap check. */
    val candidate: Boolean = false,
    /** The level at which the first cut was observed, for the report and the cap-mismatch check. */
    val observedHoldPercent: Int? = null,
)

data class QualificationOutcome(
    val progress: QualificationProgress,
    val command: RunCommand? = null,
    val terminal: RunTerminal? = null,
)

/** What the accumulation measurement says about the current phase. */
internal enum class Flow { RISING, STALLED, UNKNOWN }

/**
 * Drives the cut → resume → cut challenge. Pure and JVM-testable: no clock, no Android, the caller
 * threads [QualificationProgress] and executes the emitted [RunCommand].
 *
 * **A phase timeout is never a refutation.** [RunTerminal.Refuted] is reachable only from an observed
 * climb past the commanded cap — the same signal, and the same [OVERSHOOT_ALLOWANCE], the passive
 * `EnforcementVerdictEngine` uses. A cold room, a 500 mA charger or a device that simply never
 * reaches the cap all end [RunTerminal.Inconclusive], because a run that could not measure must never
 * be able to permanently disable control on a working device.
 *
 * **Nor may a run refute a mapping it guessed.** A refutation claims something about the hardware, so
 * it is only available where the adapter's value semantics are known. On a candidate device the same
 * observation ends [InconclusiveReason.CAP_MISMATCH] — see [cut].
 */
object QualificationRunEngine {

    /**
     * Open a run. The caller resolves [shape], [lowCap] and [releasePolicy] from the adapter's
     * `supportedPolicies` and the battery's current level, because only it knows the adapter.
     */
    fun start(
        shape: RunShape,
        lowCap: Int,
        releasePolicy: ChargePolicy,
        nowMillis: Long,
        candidate: Boolean = false,
    ): QualificationProgress = QualificationProgress(
        shape = shape,
        phase = RunPhase.PREFLIGHT,
        runStartedAt = nowMillis,
        phaseStartedAt = nowMillis,
        lowCap = lowCap,
        releasePolicy = releasePolicy,
        candidate = candidate,
    )

    fun evaluate(previous: QualificationProgress, sample: QualificationSample): QualificationOutcome {
        abortReasonFor(previous, sample)?.let {
            return QualificationOutcome(previous, terminal = RunTerminal.Aborted(it))
        }
        return when (previous.phase) {
            RunPhase.PREFLIGHT -> preflight(previous, sample)
            RunPhase.CHARGE_UP -> chargeUp(previous, sample)
            RunPhase.CUT_1, RunPhase.CUT_2 -> cut(previous, sample)
            RunPhase.RESUME -> resume(previous, sample)
        }
    }

    /**
     * Conditions that end a run wherever it is. Unplugging is an abort rather than an inconclusive
     * result because the protocol's one instruction to the user is to leave the cable in: it is a
     * run that did not happen, not a run that failed to measure.
     */
    private fun abortReasonFor(progress: QualificationProgress, sample: QualificationSample): AbortReason? = when {
        sample.cancelled -> AbortReason.USER_CANCELLED
        sample.writeFailed -> AbortReason.WRITE_FAILED
        sample.sessionActive -> AbortReason.SESSION_STARTED
        !sample.plugged -> AbortReason.UNPLUGGED
        sample.nowMillis - progress.runStartedAt >= RUN_CEILING_MILLIS -> AbortReason.RUN_CEILING
        driftedAway(progress, sample) -> AbortReason.CONFIGURATION_DRIFT
        else -> null
    }

    /**
     * The configured state disagrees with what the run commanded. Only consulted after a grace
     * window, because a settings write and its readback are not simultaneous on every adapter, and
     * only for a *verified* observation — a merely last-requested or unreadable state proves nothing.
     */
    private fun driftedAway(progress: QualificationProgress, sample: QualificationSample): Boolean {
        val commanded = progress.commanded ?: return false
        if (sample.nowMillis - progress.commandedAt < DRIFT_GRACE_MILLIS) return false
        val verified = sample.configured as? ChargeObservation.Verified ?: return false
        return verified.policy != commanded
    }

    private fun preflight(progress: QualificationProgress, sample: QualificationSample): QualificationOutcome {
        if (sample.percent !in 0..100) return waitOrTimeout(progress, sample)
        val signal = resolveSignal(sample)
        if (signal == FlowSignal.NONE) {
            return QualificationOutcome(progress, terminal = RunTerminal.Inconclusive(InconclusiveReason.NO_SIGNAL))
        }
        val ready = progress.shape == RunShape.VARIABLE_CAP ||
            sample.percent >= progress.lowCap - FIXED_CAP_ENTRY_MARGIN
        val started = progress.copy(signal = signal)
        return if (ready) {
            enterPhase(started, sample, RunPhase.CUT_1, ChargePolicy.FixedLimit(progress.lowCap))
        } else {
            enterPhase(started, sample, RunPhase.CHARGE_UP, ChargePolicy.Unrestricted)
        }
    }

    private fun waitOrTimeout(
        progress: QualificationProgress,
        sample: QualificationSample,
    ): QualificationOutcome = if (sample.nowMillis - progress.phaseStartedAt >= PREFLIGHT_BUDGET_MILLIS) {
        QualificationOutcome(progress, terminal = RunTerminal.Inconclusive(InconclusiveReason.PRECONDITION_TIMEOUT))
    } else {
        QualificationOutcome(progress)
    }

    private fun chargeUp(progress: QualificationProgress, sample: QualificationSample): QualificationOutcome = when {
        sample.percent in 0..100 && sample.percent >= progress.lowCap - FIXED_CAP_ENTRY_MARGIN ->
            enterPhase(progress, sample, RunPhase.CUT_1, ChargePolicy.FixedLimit(progress.lowCap))

        sample.nowMillis - progress.phaseStartedAt >= CHARGE_UP_BUDGET_MILLIS ->
            QualificationOutcome(
                progress,
                terminal = RunTerminal.Inconclusive(InconclusiveReason.CHARGE_UP_TIMEOUT),
            )

        else -> QualificationOutcome(progress)
    }

    private fun cut(progress: QualificationProgress, sample: QualificationSample): QualificationOutcome {
        if (climbedPastCap(progress, sample)) {
            // A refutation is a claim about the *hardware*, so it may only be made where the value
            // mapping is known. On a candidate device the commanded value is a guess: a One UI 6/7
            // phone whose `protect_battery = 1` means "cap at 85" charges straight past a commanded
            // 80 while enforcing perfectly. Recording that as a refutation would permanently disable
            // control on a device that works. It is a mapping result, and the report carries the
            // level so the mapping can be corrected.
            val terminal = if (progress.candidate) {
                RunTerminal.Inconclusive(InconclusiveReason.CAP_MISMATCH)
            } else {
                RunTerminal.Refuted
            }
            val observed = if (sample.percent in 0..100) sample.percent else progress.observedHoldPercent
            return QualificationOutcome(progress.copy(observedHoldPercent = observed), terminal = terminal)
        }
        val advanced = advanceFlow(progress, sample)
        val held = sample.nowMillis - advanced.holdSince >= HOLD_CONFIRM_MILLIS
        if (held) {
            // Same reasoning from the other side: a candidate device that stops *below* the commanded
            // cap never triggers the climb check above, but its mapping is just as wrong.
            if (progress.candidate &&
                progress.shape == RunShape.FIXED_CAP &&
                sample.percent in 0..100 &&
                kotlin.math.abs(sample.percent - progress.lowCap) > OVERSHOOT_ALLOWANCE
            ) {
                return QualificationOutcome(
                    advanced.copy(observedHoldPercent = sample.percent),
                    terminal = RunTerminal.Inconclusive(InconclusiveReason.CAP_MISMATCH),
                )
            }
            val recorded = if (advanced.observedHoldPercent == null && sample.percent in 0..100) {
                advanced.copy(observedHoldPercent = sample.percent)
            } else {
                advanced
            }
            return if (progress.phase == RunPhase.CUT_1) {
                enterPhase(recorded, sample, RunPhase.RESUME, progress.releasePolicy)
            } else {
                QualificationOutcome(recorded, terminal = RunTerminal.Passed)
            }
        }
        if (sample.nowMillis - progress.phaseStartedAt >= PHASE_BUDGET_MILLIS) {
            val reason = if (progress.phase == RunPhase.CUT_1) {
                InconclusiveReason.NO_CUT
            } else {
                InconclusiveReason.NO_RECUT
            }
            return QualificationOutcome(advanced, terminal = RunTerminal.Inconclusive(reason))
        }
        return QualificationOutcome(advanced)
    }

    private fun resume(progress: QualificationProgress, sample: QualificationSample): QualificationOutcome {
        val flow = measureFlow(progress, sample)
        if (flow == Flow.RISING) {
            return enterPhase(progress, sample, RunPhase.CUT_2, ChargePolicy.FixedLimit(progress.lowCap))
        }
        if (sample.nowMillis - progress.phaseStartedAt >= PHASE_BUDGET_MILLIS) {
            return QualificationOutcome(
                progress,
                terminal = RunTerminal.Inconclusive(InconclusiveReason.NO_RESUME),
            )
        }
        return QualificationOutcome(progress)
    }

    /**
     * A rise that carries the level past the commanded cap. Keyed on a rise from the phase anchor
     * rather than an absolute level, because a variable-cap run deliberately starts *above* its cap
     * — the device is expected to stop where it stands, and only further climbing refutes.
     */
    private fun climbedPastCap(progress: QualificationProgress, sample: QualificationSample): Boolean {
        if (sample.percent !in 0..100 || progress.anchorPercent !in 0..100) return false
        return sample.percent > progress.anchorPercent &&
            sample.percent >= progress.lowCap + OVERSHOOT_ALLOWANCE
    }

    /** Re-anchor and restart the hold clock when charging is seen accumulating. */
    private fun advanceFlow(
        progress: QualificationProgress,
        sample: QualificationSample,
    ): QualificationProgress = when (measureFlow(progress, sample)) {
        Flow.RISING -> progress.copy(
            anchorPercent = sample.percent,
            anchorCounter = sample.chargeCounter,
            holdSince = sample.nowMillis,
        )

        Flow.STALLED, Flow.UNKNOWN -> progress
    }

    private fun enterPhase(
        progress: QualificationProgress,
        sample: QualificationSample,
        phase: RunPhase,
        policy: ChargePolicy,
    ): QualificationOutcome {
        val next = progress.copy(
            phase = phase,
            phaseStartedAt = sample.nowMillis,
            commanded = policy,
            commandedAt = sample.nowMillis,
            anchorPercent = sample.percent,
            anchorCounter = sample.chargeCounter,
            holdSince = sample.nowMillis,
        )
        return QualificationOutcome(next, command = RunCommand.Apply(policy))
    }

    /**
     * Which measurement this device can be trusted on. The counter wins when its implied full
     * capacity is plausible, because a ROM can report a synthetic level while the counter still
     * tracks real charge.
     */
    internal fun resolveSignal(sample: QualificationSample): FlowSignal {
        val impliedFull = impliedFullCapacity(sample.chargeCounter, sample.percent)
        return when {
            impliedFull != null && impliedFull >= MIN_PLAUSIBLE_FULL_MICROAMP_HOURS -> FlowSignal.COUNTER
            sample.percent in 0..100 -> FlowSignal.LEVEL
            else -> FlowSignal.NONE
        }
    }

    /**
     * The battery's full capacity implied by a counter reading at a known level. Normalizes out the
     * charge level so a phone at 20% and the same phone at 90% imply the same capacity, which is what
     * separates a correctly-reporting device from a milli-reporting one by an order of magnitude.
     */
    internal fun impliedFullCapacity(counter: Int?, percent: Int): Long? {
        if (counter == null || counter <= 0 || percent !in 1..100) return null
        return counter.toLong() * 100 / percent
    }

    internal fun measureFlow(progress: QualificationProgress, sample: QualificationSample): Flow = when (progress.signal) {
        FlowSignal.COUNTER -> {
            val anchor = progress.anchorCounter
            val current = sample.chargeCounter
            val full = impliedFullCapacity(anchor, progress.anchorPercent)
            if (anchor == null || current == null || full == null) {
                Flow.UNKNOWN
            } else {
                val threshold = full / RISE_FRACTION_DENOMINATOR
                if (current.toLong() - anchor.toLong() >= threshold) Flow.RISING else Flow.STALLED
            }
        }

        FlowSignal.LEVEL -> when {
            sample.percent !in 0..100 || progress.anchorPercent !in 0..100 -> Flow.UNKNOWN
            sample.percent > progress.anchorPercent -> Flow.RISING
            else -> Flow.STALLED
        }

        FlowSignal.NONE -> Flow.UNKNOWN
    }

    /**
     * How long after a commanded write the configured state is allowed to disagree before it counts
     * as the user having changed it. Comfortably longer than a settings write plus its readback.
     */
    internal const val DRIFT_GRACE_MILLIS = 60_000L
}
