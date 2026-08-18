package eu.darken.amply.charging.core.qualification

import eu.darken.amply.charging.core.ChargeObservation
import eu.darken.amply.charging.core.ChargePolicy
import eu.darken.amply.charging.core.qualification.QualificationProtocol.BASELINE_WINDOW_MILLIS
import eu.darken.amply.charging.core.qualification.QualificationProtocol.CHARGE_UP_BUDGET_MILLIS
import eu.darken.amply.charging.core.qualification.QualificationProtocol.CUT_RATE_DROP_FACTOR
import eu.darken.amply.charging.core.qualification.QualificationProtocol.FIXED_CAP_ENTRY_MARGIN
import eu.darken.amply.charging.core.qualification.QualificationProtocol.HOLD_CONFIRM_MILLIS
import eu.darken.amply.charging.core.qualification.QualificationProtocol.MIN_BASELINE_RATE_CAPACITY_FRACTION_PER_HOUR_DENOMINATOR
import eu.darken.amply.charging.core.qualification.QualificationProtocol.MIN_BASELINE_UPDATES
import eu.darken.amply.charging.core.qualification.QualificationProtocol.MIN_MEASURE_WINDOW_MILLIS
import eu.darken.amply.charging.core.qualification.QualificationProtocol.MIN_PLAUSIBLE_FULL_MICROAMP_HOURS
import eu.darken.amply.charging.core.qualification.QualificationProtocol.NEAR_FULL_PERCENT
import eu.darken.amply.charging.core.qualification.QualificationProtocol.OVERSHOOT_ALLOWANCE
import eu.darken.amply.charging.core.qualification.QualificationProtocol.PHASE_BUDGET_MILLIS
import eu.darken.amply.charging.core.qualification.QualificationProtocol.PREFLIGHT_BUDGET_MILLIS
import eu.darken.amply.charging.core.qualification.QualificationProtocol.RESUME_RATE_RECOVERY_DIVISOR
import eu.darken.amply.charging.core.qualification.QualificationProtocol.RUN_CEILING_MILLIS
import eu.darken.amply.charging.core.qualification.QualificationProtocol.WRITE_SETTLE_MILLIS

/**
 * One evaluation tick. Everything the protocol depends on, and nothing Android-specific.
 *
 * [configured] is the adapter's configured-state readback, only meaningful on a `SYNC_READBACK`
 * adapter; it exists to catch the user changing the setting natively mid-run. Null disables that
 * check rather than failing the run.
 *
 * [nowMillis], [percent] and [chargeCounter] must describe the **same instant** — the host takes one
 * battery snapshot and timestamps it at that read. A tick whose level was captured minutes ago but
 * whose counter is read fresh at processing time produces a rate over the wrong window, which is
 * exactly the kind of arithmetic this engine has to be able to trust.
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
    val writeFailed: Boolean = false,
    val cancelled: Boolean = false,
)

/**
 * Rolling run state, threaded by the caller and persisted between ticks.
 *
 * The measurement window is deliberately split into a **scheduled** opening ([windowEligibleAt]) and
 * the sample that actually **anchored** it ([windowAnchoredAt] and its readings). Nothing is measured
 * from the scheduled time: the readings that go with it do not exist yet, so dividing accumulation
 * since the phase started by the window's length credits the settle period's charging to the window
 * and can refute a working device that gained a point while its cap was engaging. Equally nothing is
 * measured from the phase's start — that is the same error from the other side.
 *
 * Once anchored the window is never re-anchored mid-phase: a rate is only meaningful over a known
 * span, and re-anchoring on activity would reintroduce the "long enough without a big enough jump"
 * logic that made a slow charge look like a stopped one.
 */
data class QualificationProgress(
    val shape: RunShape,
    val phase: RunPhase,
    val runStartedAt: Long,
    val phaseStartedAt: Long,
    val lowCap: Int,
    val releasePolicy: ChargePolicy,
    val commanded: ChargePolicy? = null,
    /** When the engine *emitted* the phase's command, which is before the write completed. */
    val commandedAt: Long = 0L,
    /** When the host confirmed that command's write landed. Zero while it is unacknowledged. */
    val commandAckedAt: Long = 0L,
    /** The sample that opened the measurement window, and the readings taken from it. */
    val windowAnchoredAt: Long = 0L,
    val windowStartPercent: Int = -1,
    val windowStartCounter: Int? = null,
    /** How often the [signal] was observed *changing* since the window anchored. */
    val windowSignalChanges: Int = 0,
    /** The last [signal] reading seen, so the next one can be told apart from a repeat. */
    val lastSignalValue: Long? = null,
    val signal: FlowSignal = FlowSignal.NONE,
    /**
     * The control: accumulation per hour measured in [RunPhase.BASELINE], in whichever unit [signal]
     * selected. Zero until measured, and a run cannot leave the baseline phase without it.
     */
    val baselineRatePerHour: Long = 0L,
    /** The battery's implied full capacity at baseline, used to bound what counts as a usable rate. */
    val impliedFullCapacity: Long = 0L,
    val candidate: Boolean = false,
    val observedHoldPercent: Int? = null,
) {
    /**
     * When this phase's window becomes eligible to open: the acknowledged write plus the settle time.
     * Zero while the write is unacknowledged, which is what keeps a phase from being judged — or even
     * anchored — on charging that happened under the previous configuration.
     */
    val windowEligibleAt: Long get() = if (commandAckedAt > 0L) commandAckedAt + WRITE_SETTLE_MILLIS else 0L

    val anchored: Boolean get() = windowAnchoredAt > 0L
}

data class QualificationOutcome(
    val progress: QualificationProgress,
    val command: RunCommand? = null,
    val terminal: RunTerminal? = null,
)

/**
 * Drives the baseline → cut → resume → cut challenge. Pure and JVM-testable: no clock, no Android;
 * the caller threads [QualificationProgress] and executes the emitted [RunCommand].
 *
 * **Everything is judged against the run's own baseline rate, never an absolute threshold.** That is
 * the difference between this and a plausible-looking measurement that quietly passes bad devices: a
 * phone charging steadily at 100 mA under a cap that does nothing sits below any fixed "stopped" bar
 * forever, but it cannot drop tenfold below *its own* 100 mA.
 *
 * **A phase timeout is never a refutation.** [RunTerminal.Refuted] needs an observed climb past the
 * cap that is corroborated by the measured rate, taken from a window that opened after the write was
 * acknowledged and settled. A cold room, a weak charger, or a device that never reaches the cap all
 * end [RunTerminal.Inconclusive], because a run that could not measure must never permanently disable
 * control on a working device.
 *
 * **Nor may a run refute a mapping it guessed**: on a candidate adapter the same observation ends
 * [InconclusiveReason.CAP_MISMATCH] — see [cut].
 */
object QualificationRunEngine {

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
        // Ordered after the aborts on purpose: cancellation and a failed write are facts about the
        // record rather than about this observation, and a backwards clock jump must not be able to
        // wedge a run that the user is trying to stop.
        if (isStale(previous, sample)) return QualificationOutcome(previous)
        if (previous.phase == RunPhase.PREFLIGHT) return preflight(previous, sample)
        if (!previous.anchored) return anchorOrWait(previous, sample)

        val tracked = trackSignalChange(previous, sample)
        return when (tracked.phase) {
            RunPhase.CHARGE_UP -> chargeUp(tracked, sample)
            RunPhase.BASELINE -> baseline(tracked, sample)
            RunPhase.CUT_1, RunPhase.CUT_2 -> cut(tracked, sample)
            RunPhase.RESUME -> resume(tracked, sample)
            // Handled above; a phase with no write has no window to anchor.
            RunPhase.PREFLIGHT -> QualificationOutcome(tracked)
        }
    }

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
     * An observation that predates the run, or the acknowledgement of the phase's own write, describes
     * a configuration this phase is not measuring. It moves no state at all: arming a run from a tick
     * older than the run itself would anchor the whole measurement to something nobody commanded.
     */
    private fun isStale(progress: QualificationProgress, sample: QualificationSample): Boolean =
        sample.nowMillis < progress.runStartedAt ||
            (progress.commandAckedAt > 0L && sample.nowMillis < progress.commandAckedAt)

    /**
     * The configured state disagrees with what the run commanded, i.e. the user changed it natively.
     * Consulted only once the phase's write has been acknowledged *and* settled, and only for a
     * *verified* observation — a merely last-requested or unreadable state proves nothing. Keying it
     * on the acknowledgement rather than on [QualificationProgress.commandedAt] matters: a readback
     * taken while the write is still in flight legitimately still reports the previous phase's policy,
     * and aborting there ends the run on `CONFIGURATION_DRIFT`, which deliberately does not restore.
     */
    private fun driftedAway(progress: QualificationProgress, sample: QualificationSample): Boolean {
        val commanded = progress.commanded ?: return false
        val eligible = progress.windowEligibleAt
        if (eligible <= 0L || sample.nowMillis < eligible) return false
        val verified = sample.configured as? ChargeObservation.Verified ?: return false
        return verified.policy != commanded
    }

    /**
     * Open the phase's measurement window on *this* sample, or keep waiting.
     *
     * The anchor carries the readings as well as the time, so every rate divides charge that went in
     * *during* the window by the length of that window. Nothing else may happen on the anchoring tick:
     * no phase completes, refutes or times out before it, because there is nothing yet to measure.
     */
    private fun anchorOrWait(
        progress: QualificationProgress,
        sample: QualificationSample,
    ): QualificationOutcome {
        val eligible = progress.windowEligibleAt
        if (eligible <= 0L || sample.nowMillis < eligible) return QualificationOutcome(progress)
        return QualificationOutcome(
            progress.copy(
                windowAnchoredAt = sample.nowMillis,
                windowStartPercent = sample.percent,
                windowStartCounter = sample.chargeCounter,
                windowSignalChanges = 0,
                lastSignalValue = signalValue(progress.signal, sample),
            ),
        )
    }

    /** Count how often the device actually reports a *different* reading inside the current window. */
    private fun trackSignalChange(
        progress: QualificationProgress,
        sample: QualificationSample,
    ): QualificationProgress {
        val value = signalValue(progress.signal, sample) ?: return progress
        val last = progress.lastSignalValue ?: return progress.copy(lastSignalValue = value)
        if (value == last) return progress
        return progress.copy(
            lastSignalValue = value,
            windowSignalChanges = progress.windowSignalChanges + 1,
        )
    }

    private fun signalValue(signal: FlowSignal, sample: QualificationSample): Long? = when (signal) {
        FlowSignal.COUNTER -> sample.chargeCounter?.toLong()
        FlowSignal.LEVEL -> sample.percent.takeIf { it in 0..100 }?.toLong()
        FlowSignal.NONE -> null
    }

    private fun preflight(progress: QualificationProgress, sample: QualificationSample): QualificationOutcome {
        if (sample.percent !in 0..100) {
            return if (sample.nowMillis - progress.phaseStartedAt >= PREFLIGHT_BUDGET_MILLIS) {
                QualificationOutcome(
                    progress,
                    terminal = RunTerminal.Inconclusive(InconclusiveReason.PRECONDITION_TIMEOUT),
                )
            } else {
                QualificationOutcome(progress)
            }
        }
        val signal = resolveSignal(sample)
        if (signal == FlowSignal.NONE) {
            return QualificationOutcome(progress, terminal = RunTerminal.Inconclusive(InconclusiveReason.NO_SIGNAL))
        }
        val capacity = impliedFullCapacity(sample.chargeCounter, sample.percent) ?: 0L
        val armed = progress.copy(signal = signal, impliedFullCapacity = capacity)
        // The cap comes off first: the control has to measure this charger against this battery, and
        // it cannot do that through whatever limit the user already had configured. A fixed-cap run
        // has to get the battery up to its one cap before anything is observable, and that positioning
        // is a phase of its own — folding it into the control would average the baseline rate over a
        // window up to twenty-four times longer than the control is meant to be.
        val next = if (progress.shape == RunShape.FIXED_CAP) RunPhase.CHARGE_UP else RunPhase.BASELINE
        return enterPhase(armed, sample, next, progress.releasePolicy)
    }

    /**
     * Positioning only: charge up to just under the cap. Deliberately measures nothing — no rate, no
     * update count — because this window is bounded by a charger and a battery rather than by the
     * protocol, and anything averaged over it says nothing about the minutes the verdict rests on.
     */
    private fun chargeUp(progress: QualificationProgress, sample: QualificationSample): QualificationOutcome {
        val nearCap = sample.percent in 0..100 && sample.percent >= progress.lowCap - FIXED_CAP_ENTRY_MARGIN
        if (nearCap) return enterPhase(progress, sample, RunPhase.BASELINE, progress.releasePolicy)
        if (sample.nowMillis - progress.windowAnchoredAt >= CHARGE_UP_BUDGET_MILLIS) {
            return QualificationOutcome(
                progress,
                terminal = RunTerminal.Inconclusive(InconclusiveReason.CHARGE_UP_TIMEOUT),
            )
        }
        return QualificationOutcome(progress)
    }

    /**
     * Measure the control over a bounded window: how fast charge goes in with the cap lifted, and how
     * often this device is willing to say so.
     */
    private fun baseline(progress: QualificationProgress, sample: QualificationSample): QualificationOutcome {
        val elapsed = sample.nowMillis - progress.windowAnchoredAt
        if (elapsed >= BASELINE_WINDOW_MILLIS) {
            val rate = ratePerHour(progress, sample)
            if (rate == null || rate < minimumBaselineRate(progress)) {
                return QualificationOutcome(
                    progress,
                    terminal = RunTerminal.Inconclusive(InconclusiveReason.NO_BASELINE),
                )
            }
            // A rate alone is not enough. Averaged over ten minutes a single update looks identical
            // whether the device reports every thirty seconds or once per phase, and only the first
            // makes a quiet cut window mean anything.
            if (progress.windowSignalChanges < MIN_BASELINE_UPDATES) {
                return QualificationOutcome(
                    progress,
                    terminal = RunTerminal.Inconclusive(InconclusiveReason.SIGNAL_TOO_COARSE),
                )
            }
            val measured = progress.copy(baselineRatePerHour = rate)
            return enterPhase(measured, sample, RunPhase.CUT_1, ChargePolicy.FixedLimit(progress.lowCap))
        }
        if (elapsed >= PHASE_BUDGET_MILLIS) {
            return QualificationOutcome(
                progress,
                terminal = RunTerminal.Inconclusive(InconclusiveReason.NO_BASELINE),
            )
        }
        return QualificationOutcome(progress)
    }

    private fun cut(progress: QualificationProgress, sample: QualificationSample): QualificationOutcome {
        // Near full first: up here a stopped battery is not evidence of a cap, and neither is a
        // moving one evidence against it. Ordering matters — checking the climb first would refute a
        // run whose battery simply topped off.
        if (sample.percent >= NEAR_FULL_PERCENT) {
            return QualificationOutcome(
                progress.copy(observedHoldPercent = sample.percent),
                terminal = RunTerminal.Inconclusive(InconclusiveReason.NEAR_FULL),
            )
        }
        if (climbedPastCap(progress, sample)) {
            // A refutation claims something about the hardware, so it is only available where the
            // value mapping is known. On a candidate adapter the commanded value is an assumption: a
            // One UI 6/7 phone whose `protect_battery` means "cap at 85" charges past a commanded 80
            // while enforcing perfectly, and refuting there would disable a device that works.
            val terminal = if (progress.candidate) {
                RunTerminal.Inconclusive(InconclusiveReason.CAP_MISMATCH)
            } else {
                RunTerminal.Refuted
            }
            return QualificationOutcome(progress.copy(observedHoldPercent = sample.percent), terminal = terminal)
        }

        val elapsed = sample.nowMillis - progress.windowAnchoredAt
        if (elapsed >= HOLD_CONFIRM_MILLIS) {
            val rate = ratePerHour(progress, sample)
            if (rate != null && rate <= progress.baselineRatePerHour / CUT_RATE_DROP_FACTOR) {
                if (progress.candidate &&
                    progress.shape == RunShape.FIXED_CAP &&
                    sample.percent in 0..100 &&
                    kotlin.math.abs(sample.percent - progress.lowCap) > OVERSHOOT_ALLOWANCE
                ) {
                    return QualificationOutcome(
                        progress.copy(observedHoldPercent = sample.percent),
                        terminal = RunTerminal.Inconclusive(InconclusiveReason.CAP_MISMATCH),
                    )
                }
                val recorded = progress.copy(
                    observedHoldPercent = progress.observedHoldPercent
                        ?: sample.percent.takeIf { it in 0..100 },
                )
                return if (progress.phase == RunPhase.CUT_1) {
                    enterPhase(recorded, sample, RunPhase.RESUME, progress.releasePolicy)
                } else {
                    QualificationOutcome(recorded, terminal = RunTerminal.Passed)
                }
            }
        }
        if (elapsed >= PHASE_BUDGET_MILLIS) {
            val reason = if (progress.phase == RunPhase.CUT_1) {
                InconclusiveReason.NO_CUT
            } else {
                InconclusiveReason.NO_RECUT
            }
            return QualificationOutcome(progress, terminal = RunTerminal.Inconclusive(reason))
        }
        return QualificationOutcome(progress)
    }

    private fun resume(progress: QualificationProgress, sample: QualificationSample): QualificationOutcome {
        val elapsed = sample.nowMillis - progress.windowAnchoredAt
        if (elapsed >= MIN_MEASURE_WINDOW_MILLIS) {
            val rate = ratePerHour(progress, sample)
            if (rate != null && rate >= progress.baselineRatePerHour / RESUME_RATE_RECOVERY_DIVISOR) {
                return enterPhase(progress, sample, RunPhase.CUT_2, ChargePolicy.FixedLimit(progress.lowCap))
            }
        }
        if (elapsed >= PHASE_BUDGET_MILLIS) {
            return QualificationOutcome(progress, terminal = RunTerminal.Inconclusive(InconclusiveReason.NO_RESUME))
        }
        return QualificationOutcome(progress)
    }

    /**
     * A rise that carries the level past the commanded cap, corroborated by the rate.
     *
     * Three guards, each closing a way a good device could be permanently disabled: the rise is keyed
     * on the level the window *anchored* at rather than an absolute level, because a variable-cap run
     * deliberately begins above its cap and is expected to stop where it stands; the measured rate
     * must agree that charge is actually going in, so a gauge recalibration cannot refute on its own;
     * and nothing counts before the window anchors, which is [WRITE_SETTLE_MILLIS] after the write was
     * acknowledged, because everything before that describes the previous configuration.
     */
    private fun climbedPastCap(progress: QualificationProgress, sample: QualificationSample): Boolean {
        if (!progress.anchored) return false
        if (sample.percent !in 0..100 || progress.windowStartPercent !in 0..100) return false
        val climbed = sample.percent > progress.windowStartPercent &&
            sample.percent >= progress.lowCap + OVERSHOOT_ALLOWANCE
        if (!climbed) return false
        val rate = ratePerHour(progress, sample) ?: return false
        return rate > progress.baselineRatePerHour / CUT_RATE_DROP_FACTOR
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
            // Everything about the new phase's window is unknown until the host acknowledges this
            // write: when it may open, and — because the readings must come from the sample that
            // opens it — what it opened at.
            commandAckedAt = 0L,
            windowAnchoredAt = 0L,
            windowStartPercent = -1,
            windowStartCounter = null,
            windowSignalChanges = 0,
            lastSignalValue = null,
        )
        return QualificationOutcome(next, command = RunCommand.Apply(policy))
    }

    /**
     * Accumulation since the window anchored, per hour, in [FlowSignal] units. Null when the window
     * has not anchored yet, is too short to divide by, or the readings needed are missing.
     *
     * A falling reading clamps to zero rather than going negative: discharging is not evidence of
     * charging, and a negative rate would sail under every "is it charging" bar as if it were a hold —
     * which it is, but the caller should not have to reason about signs to get that right.
     */
    internal fun ratePerHour(progress: QualificationProgress, sample: QualificationSample): Long? {
        if (!progress.anchored) return null
        val elapsed = sample.nowMillis - progress.windowAnchoredAt
        if (elapsed < MIN_MEASURE_WINDOW_MILLIS) return null
        val delta = when (progress.signal) {
            FlowSignal.COUNTER -> {
                val start = progress.windowStartCounter ?: return null
                val now = sample.chargeCounter ?: return null
                now.toLong() - start.toLong()
            }

            FlowSignal.LEVEL -> {
                if (sample.percent !in 0..100 || progress.windowStartPercent !in 0..100) return null
                // Scaled so integer division against the drop factor keeps meaningful resolution:
                // a single percent point would otherwise round to nothing.
                (sample.percent.toLong() - progress.windowStartPercent.toLong()) * LEVEL_RATE_SCALE
            }

            FlowSignal.NONE -> return null
        }
        return (delta.coerceAtLeast(0) * MILLIS_PER_HOUR / elapsed)
    }

    /**
     * The slowest control the run will work from. Expressed against the battery's implied full
     * capacity so it holds for any cell size and survives a ROM that reports milli-units where
     * Android documents micro-units — both sides of the ratio scale together.
     */
    internal fun minimumBaselineRate(progress: QualificationProgress): Long = when (progress.signal) {
        FlowSignal.COUNTER ->
            progress.impliedFullCapacity / MIN_BASELINE_RATE_CAPACITY_FRACTION_PER_HOUR_DENOMINATOR

        // One percent per hour, in the same scaled units ratePerHour produces.
        FlowSignal.LEVEL -> LEVEL_RATE_SCALE
        FlowSignal.NONE -> Long.MAX_VALUE
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
     * charge level, which is what separates a correctly-reporting device from a milli-reporting one
     * by an order of magnitude at any state of charge.
     */
    internal fun impliedFullCapacity(counter: Int?, percent: Int): Long? {
        if (counter == null || counter <= 0 || percent !in 1..100) return null
        return counter.toLong() * 100 / percent
    }

    internal const val MILLIS_PER_HOUR = 3_600_000L

    /** Percent points are scaled before rate arithmetic so integer division keeps resolution. */
    internal const val LEVEL_RATE_SCALE = 1_000L
}
