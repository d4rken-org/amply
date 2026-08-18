package eu.darken.amply.charging.core.qualification

import eu.darken.amply.charging.core.BackendKind
import eu.darken.amply.charging.core.ChargeObservation
import eu.darken.amply.charging.core.ChargePolicy
import eu.darken.amply.charging.core.qualification.QualificationProtocol.CHARGE_UP_BUDGET_MILLIS
import eu.darken.amply.charging.core.qualification.QualificationProtocol.HOLD_CONFIRM_MILLIS
import eu.darken.amply.charging.core.qualification.QualificationProtocol.PHASE_BUDGET_MILLIS
import eu.darken.amply.charging.core.qualification.QualificationProtocol.PREFLIGHT_BUDGET_MILLIS
import eu.darken.amply.charging.core.qualification.QualificationProtocol.RUN_CEILING_MILLIS
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test

class QualificationRunEngineTest {

    /** A 4000 mAh battery reporting microamp-hours, the correct case. */
    private fun counterAt(percent: Int, fullMicroAmpHours: Long = 4_000_000L): Int =
        (fullMicroAmpHours * percent / 100).toInt()

    private fun sample(
        now: Long,
        percent: Int,
        counter: Int? = counterAt(percent),
        plugged: Boolean = true,
        configured: ChargeObservation? = null,
        sessionActive: Boolean = false,
        writeFailed: Boolean = false,
        cancelled: Boolean = false,
    ) = QualificationSample(
        nowMillis = now,
        plugged = plugged,
        percent = percent,
        chargeCounter = counter,
        configured = configured,
        sessionActive = sessionActive,
        writeFailed = writeFailed,
        cancelled = cancelled,
    )

    private fun variableRun(now: Long = 0L, candidate: Boolean = false) = QualificationRunEngine.start(
        shape = RunShape.VARIABLE_CAP,
        lowCap = 70,
        releasePolicy = ChargePolicy.FixedLimit(85),
        nowMillis = now,
        candidate = candidate,
    )

    private fun fixedRun(now: Long = 0L, candidate: Boolean = false) = QualificationRunEngine.start(
        shape = RunShape.FIXED_CAP,
        lowCap = 80,
        releasePolicy = ChargePolicy.Unrestricted,
        nowMillis = now,
        candidate = candidate,
    )

    /**
     * Drive a phase forward with samples that never accumulate, i.e. a device holding. Stops at the
     * first terminal *or* phase change, so the returned outcome is the transition itself — otherwise
     * the command that the transition emitted would be lost to the following no-op ticks.
     */
    private fun holdThrough(
        start: QualificationProgress,
        from: Long,
        to: Long,
        percent: Int,
        stepMillis: Long = 30_000L,
    ): QualificationOutcome {
        var progress = start
        var now = from
        var outcome = QualificationOutcome(start)
        while (now <= to) {
            outcome = QualificationRunEngine.evaluate(progress, sample(now, percent))
            if (outcome.terminal != null || outcome.progress.phase != progress.phase) return outcome
            progress = outcome.progress
            now += stepMillis
        }
        return outcome
    }

    @Test
    fun `preflight on a variable-cap adapter writes the low cap immediately`() {
        val outcome = QualificationRunEngine.evaluate(variableRun(), sample(now = 1_000, percent = 80))

        outcome.progress.phase shouldBe RunPhase.CUT_1
        outcome.progress.signal shouldBe FlowSignal.COUNTER
        outcome.command shouldBe RunCommand.Apply(ChargePolicy.FixedLimit(70))
        outcome.terminal shouldBe null
    }

    @Test
    fun `preflight on a fixed-cap adapter charges up first when the battery is too low`() {
        val outcome = QualificationRunEngine.evaluate(fixedRun(), sample(now = 1_000, percent = 55))

        outcome.progress.phase shouldBe RunPhase.CHARGE_UP
        outcome.command shouldBe RunCommand.Apply(ChargePolicy.Unrestricted)
    }

    @Test
    fun `preflight on a fixed-cap adapter starts measuring within the entry margin`() {
        val outcome = QualificationRunEngine.evaluate(fixedRun(), sample(now = 1_000, percent = 78))

        outcome.progress.phase shouldBe RunPhase.CUT_1
        outcome.command shouldBe RunCommand.Apply(ChargePolicy.FixedLimit(80))
    }

    @Test
    fun `preflight without any usable signal ends inconclusive rather than passing`() {
        val outcome = QualificationRunEngine.evaluate(
            variableRun(),
            sample(now = 1_000, percent = -1, counter = null),
        )
        // An unknown level alone only waits; it is the timeout that ends it.
        outcome.terminal shouldBe null

        val timedOut = QualificationRunEngine.evaluate(
            variableRun(),
            sample(now = PREFLIGHT_BUDGET_MILLIS, percent = -1, counter = null),
        )
        timedOut.terminal shouldBe RunTerminal.Inconclusive(InconclusiveReason.PRECONDITION_TIMEOUT)
    }

    @Test
    fun `a milli-reporting counter falls back to level rather than being trusted`() {
        // MagicOS: charge counter 6978 on a ~7100 mAh cell, i.e. scaled by 1000.
        QualificationRunEngine.resolveSignal(sample(now = 0, percent = 100, counter = 6978)) shouldBe
            FlowSignal.LEVEL
        QualificationRunEngine.resolveSignal(sample(now = 0, percent = 80, counter = counterAt(80))) shouldBe
            FlowSignal.COUNTER
        QualificationRunEngine.resolveSignal(sample(now = 0, percent = -1, counter = null)) shouldBe
            FlowSignal.NONE
    }

    @Test
    fun `implied capacity normalizes out the charge level`() {
        QualificationRunEngine.impliedFullCapacity(counterAt(20), 20) shouldBe 4_000_000L
        QualificationRunEngine.impliedFullCapacity(counterAt(90), 90) shouldBe 4_000_000L
        QualificationRunEngine.impliedFullCapacity(null, 50) shouldBe null
        QualificationRunEngine.impliedFullCapacity(1_000, 0) shouldBe null
    }

    @Test
    fun `a sustained hold in the first cut moves to the resume phase`() {
        val armed = QualificationRunEngine.evaluate(variableRun(), sample(1_000, 80)).progress

        val outcome = holdThrough(armed, from = 31_000, to = 1_000 + HOLD_CONFIRM_MILLIS + 60_000, percent = 80)

        outcome.progress.phase shouldBe RunPhase.RESUME
        outcome.command shouldBe RunCommand.Apply(ChargePolicy.FixedLimit(85))
        outcome.progress.observedHoldPercent shouldBe 80
    }

    @Test
    fun `charging past the commanded cap refutes`() {
        val armed = QualificationRunEngine.evaluate(fixedRun(), sample(1_000, 78)).progress

        val outcome = QualificationRunEngine.evaluate(armed, sample(60_000, 83))

        outcome.terminal shouldBe RunTerminal.Refuted
    }

    @Test
    fun `a rise that stays under the overshoot allowance does not refute`() {
        val armed = QualificationRunEngine.evaluate(fixedRun(), sample(1_000, 78)).progress

        val outcome = QualificationRunEngine.evaluate(armed, sample(60_000, 82))

        outcome.terminal shouldBe null
    }

    @Test
    fun `a variable-cap run starting above its cap does not refute just for sitting there`() {
        // lowCap 70 with the battery at 80: every sample is already above cap + overshoot.
        val armed = QualificationRunEngine.evaluate(variableRun(), sample(1_000, 80)).progress

        QualificationRunEngine.evaluate(armed, sample(60_000, 80)).terminal shouldBe null
        QualificationRunEngine.evaluate(armed, sample(60_000, 79)).terminal shouldBe null
    }

    @Test
    fun `a variable-cap run still refutes when the level climbs further`() {
        val armed = QualificationRunEngine.evaluate(variableRun(), sample(1_000, 80)).progress

        QualificationRunEngine.evaluate(armed, sample(60_000, 81)).terminal shouldBe RunTerminal.Refuted
    }

    @Test
    fun `charging that keeps accumulating never confirms a cut and times out inconclusive`() {
        var progress = QualificationRunEngine.evaluate(variableRun(), sample(1_000, 60)).progress
        var now = 31_000L
        var outcome = QualificationOutcome(progress)
        var counter = counterAt(60)

        // A steady ~1 A charge: the counter climbs every tick, so the hold clock keeps resetting.
        while (now <= PHASE_BUDGET_MILLIS + 60_000) {
            counter += 8_000
            outcome = QualificationRunEngine.evaluate(progress, sample(now, 60, counter = counter))
            outcome.terminal?.let { break }
            progress = outcome.progress
            now += 30_000
        }

        outcome.terminal shouldBe RunTerminal.Inconclusive(InconclusiveReason.NO_CUT)
    }

    @Test
    fun `a resume that never arrives is inconclusive and never a refutation`() {
        val armed = QualificationRunEngine.evaluate(variableRun(), sample(1_000, 80)).progress
        val resuming = holdThrough(armed, 31_000, 1_000 + HOLD_CONFIRM_MILLIS + 60_000, 80).progress
        resuming.phase shouldBe RunPhase.RESUME

        val outcome = holdThrough(
            resuming,
            from = resuming.phaseStartedAt + 30_000,
            to = resuming.phaseStartedAt + PHASE_BUDGET_MILLIS + 60_000,
            percent = 80,
        )

        outcome.terminal shouldBe RunTerminal.Inconclusive(InconclusiveReason.NO_RESUME)
    }

    @Test
    fun `the full cut resume cut sequence passes`() {
        val armed = QualificationRunEngine.evaluate(variableRun(), sample(1_000, 80)).progress
        val resuming = holdThrough(armed, 31_000, 1_000 + HOLD_CONFIRM_MILLIS + 60_000, 80).progress

        // Charging resumes: enough accumulation to clear the rise threshold.
        val recut = QualificationRunEngine.evaluate(
            resuming,
            sample(resuming.phaseStartedAt + 120_000, 80, counter = counterAt(80) + 30_000),
        )
        recut.progress.phase shouldBe RunPhase.CUT_2
        recut.command shouldBe RunCommand.Apply(ChargePolicy.FixedLimit(70))

        val done = holdThrough(
            recut.progress,
            from = recut.progress.phaseStartedAt + 30_000,
            to = recut.progress.phaseStartedAt + HOLD_CONFIRM_MILLIS + 60_000,
            percent = 80,
        )

        done.terminal shouldBe RunTerminal.Passed
    }

    @Test
    fun `a second cut that never holds is inconclusive`() {
        val armed = QualificationRunEngine.evaluate(variableRun(), sample(1_000, 80)).progress
        val resuming = holdThrough(armed, 31_000, 1_000 + HOLD_CONFIRM_MILLIS + 60_000, 80).progress
        val recut = QualificationRunEngine.evaluate(
            resuming,
            sample(resuming.phaseStartedAt + 120_000, 80, counter = counterAt(80) + 30_000),
        ).progress

        var progress = recut
        var now = recut.phaseStartedAt + 30_000
        var counter = counterAt(80) + 30_000
        var outcome = QualificationOutcome(progress)
        while (now <= recut.phaseStartedAt + PHASE_BUDGET_MILLIS + 60_000) {
            counter += 8_000
            outcome = QualificationRunEngine.evaluate(progress, sample(now, 80, counter = counter))
            outcome.terminal?.let { break }
            progress = outcome.progress
            now += 30_000
        }

        // The level never moves, so this is a stalled counter climb rather than a refutation.
        outcome.terminal shouldBe RunTerminal.Inconclusive(InconclusiveReason.NO_RECUT)
    }

    @Test
    fun `charge-up gives up after its own budget`() {
        val charging = QualificationRunEngine.evaluate(fixedRun(), sample(1_000, 55)).progress

        QualificationRunEngine.evaluate(charging, sample(30_000, 60)).terminal shouldBe null
        QualificationRunEngine.evaluate(
            charging,
            sample(1_000 + CHARGE_UP_BUDGET_MILLIS, 60),
        ).terminal shouldBe RunTerminal.Inconclusive(InconclusiveReason.CHARGE_UP_TIMEOUT)
    }

    @Test
    fun `charge-up hands over once the battery reaches the entry margin`() {
        val charging = QualificationRunEngine.evaluate(fixedRun(), sample(1_000, 55)).progress

        val outcome = QualificationRunEngine.evaluate(charging, sample(600_000, 78))

        outcome.progress.phase shouldBe RunPhase.CUT_1
        outcome.command shouldBe RunCommand.Apply(ChargePolicy.FixedLimit(80))
    }

    @Test
    fun `unplugging aborts wherever the run is`() {
        val armed = QualificationRunEngine.evaluate(variableRun(), sample(1_000, 80)).progress

        val outcome = QualificationRunEngine.evaluate(armed, sample(60_000, 80, plugged = false))

        outcome.terminal shouldBe RunTerminal.Aborted(AbortReason.UNPLUGGED)
    }

    @Test
    fun `a failed write aborts instead of being read as a hold`() {
        val armed = QualificationRunEngine.evaluate(variableRun(), sample(1_000, 80)).progress

        QualificationRunEngine.evaluate(armed, sample(60_000, 80, writeFailed = true)).terminal shouldBe
            RunTerminal.Aborted(AbortReason.WRITE_FAILED)
    }

    @Test
    fun `a full-charge session starting aborts the run`() {
        val armed = QualificationRunEngine.evaluate(variableRun(), sample(1_000, 80)).progress

        QualificationRunEngine.evaluate(armed, sample(60_000, 80, sessionActive = true)).terminal shouldBe
            RunTerminal.Aborted(AbortReason.SESSION_STARTED)
    }

    @Test
    fun `cancelling aborts the run`() {
        val armed = QualificationRunEngine.evaluate(variableRun(), sample(1_000, 80)).progress

        QualificationRunEngine.evaluate(armed, sample(60_000, 80, cancelled = true)).terminal shouldBe
            RunTerminal.Aborted(AbortReason.USER_CANCELLED)
    }

    @Test
    fun `the run ceiling aborts even mid-phase`() {
        val armed = QualificationRunEngine.evaluate(variableRun(), sample(1_000, 80)).progress

        QualificationRunEngine.evaluate(armed, sample(RUN_CEILING_MILLIS, 80)).terminal shouldBe
            RunTerminal.Aborted(AbortReason.RUN_CEILING)
    }

    @Test
    fun `a native change away from the commanded policy aborts once the grace window passes`() {
        val armed = QualificationRunEngine.evaluate(variableRun(), sample(1_000, 80)).progress
        val native = ChargeObservation.Verified(ChargePolicy.Unrestricted, BackendKind.SHIZUKU)

        // Inside the grace window a disagreeing readback is just a write that has not settled.
        QualificationRunEngine.evaluate(armed, sample(30_000, 80, configured = native)).terminal shouldBe null

        QualificationRunEngine.evaluate(armed, sample(120_000, 80, configured = native)).terminal shouldBe
            RunTerminal.Aborted(AbortReason.CONFIGURATION_DRIFT)
    }

    @Test
    fun `a readback that agrees with the commanded policy never aborts`() {
        val armed = QualificationRunEngine.evaluate(variableRun(), sample(1_000, 80)).progress
        val agreeing = ChargeObservation.Verified(ChargePolicy.FixedLimit(70), BackendKind.SHIZUKU)

        QualificationRunEngine.evaluate(armed, sample(600_000, 80, configured = agreeing)).terminal shouldBe null
    }

    @Test
    fun `an unverified readback is not treated as drift`() {
        val armed = QualificationRunEngine.evaluate(variableRun(), sample(1_000, 80)).progress
        val requested = ChargeObservation.LastRequested(ChargePolicy.Unrestricted)

        QualificationRunEngine.evaluate(armed, sample(600_000, 80, configured = requested)).terminal shouldBe null
    }

    @Test
    fun `a candidate device that charges past the commanded cap reports a mapping mismatch, not a refutation`() {
        val armed = QualificationRunEngine.evaluate(fixedRun(candidate = true), sample(1_000, 78)).progress

        // Commanded 80, but this One UI 6/7 device's value means "cap at 85", so it charges past 80
        // while enforcing perfectly. Refuting here would permanently disable control on a good device.
        val outcome = QualificationRunEngine.evaluate(armed, sample(600_000, 85))

        outcome.terminal shouldBe RunTerminal.Inconclusive(InconclusiveReason.CAP_MISMATCH)
        outcome.progress.observedHoldPercent shouldBe 85
    }

    @Test
    fun `the same climb on a known mapping is a refutation`() {
        val armed = QualificationRunEngine.evaluate(fixedRun(candidate = false), sample(1_000, 78)).progress

        QualificationRunEngine.evaluate(armed, sample(600_000, 85)).terminal shouldBe RunTerminal.Refuted
    }

    @Test
    fun `a candidate device holding below the commanded cap also reports a mapping mismatch`() {
        val armed = QualificationRunEngine.evaluate(fixedRun(candidate = true), sample(1_000, 78)).progress

        // Never climbs, so the refutation path is untouched — but it settles at 74 under a
        // commanded 80, which the cap-match check catches.
        val outcome = holdThrough(armed, 31_000, 1_000 + HOLD_CONFIRM_MILLIS + 60_000, percent = 74)

        outcome.terminal shouldBe RunTerminal.Inconclusive(InconclusiveReason.CAP_MISMATCH)
        outcome.progress.observedHoldPercent shouldBe 74
    }

    @Test
    fun `a candidate device holding at the commanded cap proceeds normally`() {
        val armed = QualificationRunEngine.evaluate(fixedRun(candidate = true), sample(1_000, 78)).progress

        val outcome = holdThrough(armed, 31_000, 1_000 + HOLD_CONFIRM_MILLIS + 60_000, percent = 79)

        outcome.terminal shouldBe null
        outcome.progress.phase shouldBe RunPhase.RESUME
    }

    @Test
    fun `a hold confirmed exactly at the boundary counts`() {
        val armed = QualificationRunEngine.evaluate(variableRun(), sample(0, 80)).progress
        armed.holdSince shouldBe 0

        QualificationRunEngine.evaluate(armed, sample(HOLD_CONFIRM_MILLIS - 1, 80)).terminal shouldBe null
        QualificationRunEngine.evaluate(armed, sample(HOLD_CONFIRM_MILLIS, 80)).progress.phase shouldBe
            RunPhase.RESUME
    }

    @Test
    fun `terminal outcomes are distinguishable types`() {
        RunTerminal.Passed.shouldBeInstanceOf<RunTerminal>()
        RunTerminal.Inconclusive(InconclusiveReason.NO_CUT).reason shouldBe InconclusiveReason.NO_CUT
        RunTerminal.Aborted(AbortReason.UNPLUGGED).reason shouldBe AbortReason.UNPLUGGED
    }
}
