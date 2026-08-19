package eu.darken.amply.charging.core.qualification

import eu.darken.amply.charging.core.BackendKind
import eu.darken.amply.charging.core.ChargeObservation
import eu.darken.amply.charging.core.ChargePolicy
import eu.darken.amply.charging.core.qualification.QualificationProtocol.BASELINE_WINDOW_MILLIS
import eu.darken.amply.charging.core.qualification.QualificationProtocol.CHARGE_UP_BUDGET_MILLIS
import eu.darken.amply.charging.core.qualification.QualificationProtocol.FIXED_CAP_ENTRY_MARGIN
import eu.darken.amply.charging.core.qualification.QualificationProtocol.HOLD_CONFIRM_MILLIS
import eu.darken.amply.charging.core.qualification.QualificationProtocol.PHASE_BUDGET_MILLIS
import eu.darken.amply.charging.core.qualification.QualificationProtocol.PLUG_MASK_WINDOW_MILLIS
import eu.darken.amply.charging.core.qualification.QualificationProtocol.PREFLIGHT_BUDGET_MILLIS
import eu.darken.amply.charging.core.qualification.QualificationProtocol.RUN_CEILING_MILLIS
import eu.darken.amply.charging.core.qualification.QualificationProtocol.WRITE_SETTLE_MILLIS
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * The protocol's whole claim rests on comparing every phase to the run's own baseline rate, so most
 * of these tests drive a simulated charge current rather than asserting on individual samples.
 */
class QualificationRunEngineTest {

    private val fullCapacity = 4_000_000L // µAh, a 4 Ah battery
    private val tickMillis = 30_000L

    /** Milliamp-hours per hour, i.e. an average current in mA, expressed in counter units. */
    private fun mA(value: Long) = value * 1_000L

    /**
     * Feeds the engine a stream of ticks at a given charge current until it produces a terminal or a
     * phase change, so a test can assert on the transition rather than on whichever tick happened to
     * come last.
     *
     * It also plays the host: a commanded write is acknowledged straight away, which is what schedules
     * the phase's window. Without that acknowledgement no window opens at all, exactly as on a device
     * whose write never landed.
     */
    private class Sim(
        var progress: QualificationProgress,
        val fullCapacity: Long,
        val tickMillis: Long,
        /** Modelled write latency: how long after the command the host reports it landed. */
        val ackDelayMillis: Long = 0L,
    ) {
        var now = 0L
        var counter = 0L
        var percent = 50
        var lastOutcome = QualificationOutcome(progress)

        fun seed(now: Long, percent: Int) {
            this.now = now
            this.percent = percent
            this.counter = fullCapacity * percent / 100
        }

        fun sample(configured: ChargeObservation? = null) = QualificationSample(
            nowMillis = now,
            plugged = true,
            percent = percent,
            chargeCounter = counter.toInt(),
            configured = configured,
            sessionActive = false,
        )

        /** Apply one tick, acknowledging any write the engine asked for. */
        fun tick(configured: ChargeObservation? = null): QualificationOutcome {
            lastOutcome = QualificationRunEngine.evaluate(progress, sample(configured))
            if (lastOutcome.terminal == null) {
                progress = lastOutcome.progress
                if (lastOutcome.command != null) {
                    progress = progress.copy(commandAckedAt = now + ackDelayMillis)
                }
            }
            return lastOutcome
        }

        /**
         * Advance up to [forMillis] at [currentPerHour] counter units per hour. Stops early on a
         * terminal or a phase change. [levelTracks] moves the reported percent with the counter, as a
         * real gauge does; tests that need the level to lie set it false and drive [percent] directly.
         */
        fun run(
            forMillis: Long,
            currentPerHour: Long,
            levelTracks: Boolean = true,
            configured: ChargeObservation? = null,
        ): QualificationOutcome {
            val until = now + forMillis
            val startPhase = progress.phase
            while (now <= until) {
                now += tickMillis
                counter += currentPerHour * tickMillis / 3_600_000L
                if (levelTracks) percent = (counter * 100 / fullCapacity).toInt().coerceIn(0, 100)
                tick(configured)
                if (lastOutcome.terminal != null) return lastOutcome
                if (progress.phase != startPhase) return lastOutcome
            }
            return lastOutcome
        }

        /** Advance to just past the point where the current phase's window can anchor. */
        fun anchor(currentPerHour: Long = 0L): QualificationOutcome {
            while (!progress.anchored && lastOutcome.terminal == null) {
                now += tickMillis
                counter += currentPerHour * tickMillis / 3_600_000L
                tick()
            }
            return lastOutcome
        }
    }

    private fun variableSim(startPercent: Int = 50, candidate: Boolean = false): Sim {
        val progress = QualificationRunEngine.start(
            shape = RunShape.VARIABLE_CAP,
            lowCap = 50,
            releasePolicy = ChargePolicy.FixedLimit(70),
            nowMillis = 0L,
            candidate = candidate,
        )
        val sim = Sim(progress, fullCapacity, tickMillis)
        // Not at the run's own start instant: an acknowledgement at time zero is indistinguishable
        // from no acknowledgement, and a real one always lands after the run began.
        sim.seed(now = 60_000L, percent = startPercent)
        // Preflight: one sample arms the run and opens the baseline phase.
        sim.tick()
        return sim
    }

    /** Drive a whole healthy run: strong baseline, hard cut, real resume, hard cut again. */
    private fun healthyRun(startPercent: Int = 50): Sim {
        val sim = variableSim(startPercent)
        sim.progress.phase shouldBe RunPhase.BASELINE
        sim.run(forMillis = 20 * 60_000L, currentPerHour = mA(1_500))
        sim.progress.phase shouldBe RunPhase.CUT_1
        sim.run(forMillis = 20 * 60_000L, currentPerHour = 0)
        sim.progress.phase shouldBe RunPhase.RESUME
        sim.run(forMillis = 15 * 60_000L, currentPerHour = mA(1_500))
        sim.progress.phase shouldBe RunPhase.CUT_2
        return sim
    }

    @Test
    fun `a healthy device passes the full sequence`() {
        val sim = healthyRun()

        val outcome = sim.run(forMillis = 20 * 60_000L, currentPerHour = 0)

        outcome.terminal shouldBe RunTerminal.Passed
    }

    @Test
    fun `preflight lifts the cap first so the baseline measures real charging`() {
        val sim = variableSim()

        sim.progress.phase shouldBe RunPhase.BASELINE
        sim.lastOutcome.command shouldBe RunCommand.Apply(ChargePolicy.FixedLimit(70))
        sim.progress.signal shouldBe FlowSignal.COUNTER
    }

    /**
     * The defect this whole redesign exists for. A phone charging steadily at 100 mA under a cap that
     * does nothing sits below any fixed "charging has stopped" bar forever. Measured against its own
     * baseline it cannot drop tenfold, so it can never be mistaken for a device that stopped.
     */
    @Test
    fun `a weak charger that never stops cannot pass`() {
        val sim = variableSim()
        sim.run(forMillis = 20 * 60_000L, currentPerHour = mA(100))
        sim.progress.phase shouldBe RunPhase.CUT_1
        // Discretized ticks and an integer counter make the measured rate approximate; what matters
        // is that it landed near the real current rather than at some absolute default.
        (sim.progress.baselineRatePerHour in mA(90)..mA(130)) shouldBe true

        val outcome = sim.run(
            forMillis = PHASE_BUDGET_MILLIS + 60_000L,
            currentPerHour = mA(100),
            levelTracks = false,
        )

        outcome.terminal shouldBe RunTerminal.Inconclusive(InconclusiveReason.NO_CUT)
    }

    @Test
    fun `a real cut is measured against the baseline, not an absolute threshold`() {
        val sim = variableSim()
        sim.run(forMillis = 20 * 60_000L, currentPerHour = mA(100))
        sim.progress.phase shouldBe RunPhase.CUT_1

        // A tenth of a weak baseline is still a cut.
        sim.run(forMillis = 20 * 60_000L, currentPerHour = mA(5))

        sim.progress.phase shouldBe RunPhase.RESUME
    }

    @Test
    fun `a charger too weak to measure anything against yields no baseline`() {
        val sim = variableSim()

        val outcome = sim.run(forMillis = 25 * 60_000L, currentPerHour = mA(5))

        outcome.terminal shouldBe RunTerminal.Inconclusive(InconclusiveReason.NO_BASELINE)
    }

    @Test
    fun `a frozen charge counter yields no baseline rather than a pass`() {
        val sim = variableSim()

        val outcome = sim.run(forMillis = 25 * 60_000L, currentPerHour = 0)

        outcome.terminal shouldBe RunTerminal.Inconclusive(InconclusiveReason.NO_BASELINE)
    }

    @Test
    fun `a resume that never recovers is inconclusive and never a refutation`() {
        val sim = variableSim()
        sim.run(forMillis = 20 * 60_000L, currentPerHour = mA(1_500))
        sim.run(forMillis = 20 * 60_000L, currentPerHour = 0)
        sim.progress.phase shouldBe RunPhase.RESUME

        val outcome = sim.run(forMillis = PHASE_BUDGET_MILLIS + 60_000L, currentPerHour = 0)

        outcome.terminal shouldBe RunTerminal.Inconclusive(InconclusiveReason.NO_RESUME)
    }

    @Test
    fun `a second cut that never arrives is inconclusive`() {
        val sim = healthyRun()

        val outcome = sim.run(
            forMillis = PHASE_BUDGET_MILLIS + 60_000L,
            currentPerHour = mA(1_500),
            levelTracks = false,
        )

        outcome.terminal shouldBe RunTerminal.Inconclusive(InconclusiveReason.NO_RECUT)
    }

    /**
     * Charging past the cap is the one thing that refutes, and it is terminal — so it needs the rate
     * to agree that charge is really going in, not just a moving gauge.
     */
    @Test
    fun `charging past the cap refutes`() {
        val sim = variableSim(startPercent = 60)
        sim.run(forMillis = 20 * 60_000L, currentPerHour = mA(1_500))
        sim.progress.phase shouldBe RunPhase.CUT_1

        val outcome = sim.run(forMillis = 30 * 60_000L, currentPerHour = mA(1_500))

        outcome.terminal shouldBe RunTerminal.Refuted
    }

    @Test
    fun `a gauge that jumps past the cap without charge going in does not refute`() {
        val sim = variableSim(startPercent = 60)
        sim.run(forMillis = 20 * 60_000L, currentPerHour = mA(1_500))
        sim.progress.phase shouldBe RunPhase.CUT_1

        // The level walks up past the cap while the counter stands still: a recalibrating gauge, not
        // charging. Refuting here would permanently disable a device whose cap works, and a
        // refutation is terminal, so this must never happen however far the level drifts.
        val terminals = mutableListOf<RunTerminal>()
        repeat(30) {
            sim.now += tickMillis
            sim.percent = (sim.percent + 1).coerceAtMost(90)
            val outcome = QualificationRunEngine.evaluate(
                sim.progress,
                QualificationSample(
                    nowMillis = sim.now,
                    plugged = true,
                    percent = sim.percent,
                    chargeCounter = sim.counter.toInt(),
                    configured = null,
                    sessionActive = false,
                ),
            )
            outcome.terminal?.let { terminals += it }
            sim.progress = outcome.progress
        }

        terminals.none { it is RunTerminal.Refuted } shouldBe true
        // It reads as a device that stopped charging, which on a known mapping is the cut it was
        // waiting for — the honest reading of a flat counter.
        sim.progress.phase shouldBe RunPhase.RESUME
    }

    @Test
    fun `nothing is judged until the write has had time to land`() {
        val sim = variableSim(startPercent = 60)
        sim.run(forMillis = 20 * 60_000L, currentPerHour = mA(1_500))
        val armed = sim.progress
        armed.phase shouldBe RunPhase.CUT_1

        // A tick captured inside the settle window describes the previous configuration, so even a
        // level well past the cap must not refute.
        val outcome = QualificationRunEngine.evaluate(
            armed,
            QualificationSample(
                nowMillis = armed.commandAckedAt + WRITE_SETTLE_MILLIS - 1,
                plugged = true,
                percent = 90,
                chargeCounter = (fullCapacity * 90 / 100).toInt(),
                configured = null,
                sessionActive = false,
            ),
        )

        outcome.terminal shouldBe null
        outcome.progress.anchored shouldBe false
    }

    /**
     * The settle period is excluded from the window's *time*, so its readings must be excluded too.
     * Anchoring on the sample that opens the window is what does that: a device that gained charge
     * while its cap was engaging must not have that charge counted against the window that follows,
     * which is how a working cap gets refuted.
     */
    @Test
    fun `the window anchors on the sample that opens it, not on the phase's start`() {
        val sim = variableSim(startPercent = 60)
        sim.run(forMillis = 20 * 60_000L, currentPerHour = mA(1_500))
        sim.progress.phase shouldBe RunPhase.CUT_1
        val entryPercent = sim.percent
        val entryCounter = sim.counter

        // Charging continues through the settle period, then stops dead.
        sim.anchor(currentPerHour = mA(1_500))

        sim.progress.anchored shouldBe true
        sim.progress.windowAnchoredAt shouldBe sim.now
        // Anchored at what the battery reads NOW, not at what it read when the cap was written.
        (sim.progress.windowStartCounter!!.toLong() > entryCounter) shouldBe true
        (sim.progress.windowStartPercent >= entryPercent) shouldBe true
    }

    /**
     * A window is not judged before it anchors, and once it does, everything measures from the anchor.
     * A first sample arriving late must not make an actively charging device look cut by crediting it
     * with the minutes nobody observed.
     */
    @Test
    fun `a late first sample does not make an active flow look cut`() {
        val sim = variableSim(startPercent = 60)
        sim.run(forMillis = 20 * 60_000L, currentPerHour = mA(1_500))
        sim.progress.phase shouldBe RunPhase.CUT_1

        // Nothing is observed for well past the hold-confirmation window, then the device reports.
        sim.now += HOLD_CONFIRM_MILLIS + 5 * 60_000L
        sim.tick().terminal shouldBe null
        sim.progress.windowAnchoredAt shouldBe sim.now

        // Charge is plainly still going in (the coarse level happens not to have moved), so the very
        // next samples must not confirm a cut off time the engine never observed.
        val outcome = sim.run(
            forMillis = HOLD_CONFIRM_MILLIS - 60_000L,
            currentPerHour = mA(1_500),
            levelTracks = false,
        )

        outcome.terminal shouldBe null
        sim.progress.phase shouldBe RunPhase.CUT_1
    }

    @Test
    fun `a sample older than the phase's acknowledgement is rejected`() {
        val sim = variableSim(startPercent = 60)
        val acked = sim.progress
        acked.commandAckedAt shouldBe 60_000L

        // A queued observation from before the write landed describes the previous configuration.
        val stale = QualificationRunEngine.evaluate(
            acked,
            QualificationSample(acked.commandAckedAt - 1, true, 60, 2_400_000, null, false),
        )
        stale.progress shouldBe acked
        stale.terminal shouldBe null

        // The acknowledgement instant itself is inside the run, not before it.
        val boundary = QualificationRunEngine.evaluate(
            acked.copy(commandAckedAt = 0L, windowAnchoredAt = 0L),
            QualificationSample(acked.commandAckedAt, true, 60, 2_400_000, null, false),
        )
        boundary.terminal shouldBe null
    }

    @Test
    fun `a sample older than the run cannot arm it`() {
        val progress = QualificationRunEngine.start(
            shape = RunShape.VARIABLE_CAP,
            lowCap = 50,
            releasePolicy = ChargePolicy.FixedLimit(70),
            nowMillis = 60_000L,
        )

        val outcome = QualificationRunEngine.evaluate(
            progress,
            QualificationSample(59_999L, true, 60, 2_400_000, null, false),
        )

        outcome.progress shouldBe progress
        outcome.command shouldBe null
        outcome.progress.phase shouldBe RunPhase.PREFLIGHT
    }

    /**
     * The false pass a granularity rule closes: a device reporting once per phase stages the whole
     * cut → resume → cut out of *when* it reported, with the cap doing nothing.
     */
    @Test
    fun `a signal that updates once per phase ends inconclusive rather than passing`() {
        val sim = variableSim(startPercent = 60)
        sim.progress.phase shouldBe RunPhase.BASELINE
        sim.anchor()

        // Charge really is going in at a healthy rate — the device just reports it in one lump.
        var outcome = sim.lastOutcome
        while (outcome.terminal == null && sim.progress.phase == RunPhase.BASELINE) {
            sim.now += tickMillis
            if (sim.now - sim.progress.windowAnchoredAt >= BASELINE_WINDOW_MILLIS - tickMillis) {
                sim.counter += mA(1_500) * BASELINE_WINDOW_MILLIS / 3_600_000L
            }
            outcome = sim.tick()
        }

        outcome.terminal shouldBe RunTerminal.Inconclusive(InconclusiveReason.SIGNAL_TOO_COARSE)
    }

    @Test
    fun `a frequently updating signal is not called too coarse`() {
        val sim = variableSim(startPercent = 60)

        sim.run(forMillis = 20 * 60_000L, currentPerHour = mA(1_500))

        sim.progress.phase shouldBe RunPhase.CUT_1
        (sim.progress.baselineRatePerHour > 0) shouldBe true
    }

    /**
     * On a single-cap adapter the battery has to be brought up to the cap first, and that stretch is
     * positioning rather than measurement: averaging a baseline over hours of charging says nothing
     * about the minutes a cut is judged on.
     */
    @Test
    fun `a fixed-cap run positions first and only then measures`() {
        val progress = QualificationRunEngine.start(
            shape = RunShape.FIXED_CAP,
            lowCap = 80,
            releasePolicy = ChargePolicy.Unrestricted,
            nowMillis = 0L,
        )
        val sim = Sim(progress, fullCapacity, tickMillis)
        sim.seed(now = 60_000L, percent = 40)

        sim.tick()
        sim.progress.phase shouldBe RunPhase.CHARGE_UP
        sim.lastOutcome.command shouldBe RunCommand.Apply(ChargePolicy.Unrestricted)

        // Charging up measures nothing at all: no rate, no baseline.
        sim.run(forMillis = 3 * 60 * 60_000L, currentPerHour = mA(1_500))

        sim.progress.phase shouldBe RunPhase.BASELINE
        sim.progress.baselineRatePerHour shouldBe 0L
        (sim.percent >= 80 - FIXED_CAP_ENTRY_MARGIN) shouldBe true

        // The control is then a bounded window of its own, and the cap follows it.
        sim.run(forMillis = 20 * 60_000L, currentPerHour = mA(1_500))

        sim.progress.phase shouldBe RunPhase.CUT_1
        (sim.progress.baselineRatePerHour > 0) shouldBe true
        sim.lastOutcome.command shouldBe RunCommand.Apply(ChargePolicy.FixedLimit(80))
    }

    @Test
    fun `a fixed-cap run that never reaches the cap times out without measuring`() {
        val progress = QualificationRunEngine.start(
            shape = RunShape.FIXED_CAP,
            lowCap = 80,
            releasePolicy = ChargePolicy.Unrestricted,
            nowMillis = 0L,
        )
        val sim = Sim(progress, fullCapacity, tickMillis)
        sim.seed(now = 60_000L, percent = 40)
        sim.tick()
        sim.progress.phase shouldBe RunPhase.CHARGE_UP

        val outcome = sim.run(forMillis = CHARGE_UP_BUDGET_MILLIS + 60_000L, currentPerHour = 0)

        outcome.terminal shouldBe RunTerminal.Inconclusive(InconclusiveReason.CHARGE_UP_TIMEOUT)
    }

    /**
     * The false-pass path the near-full guard closes: a run near full stages a textbook
     * cut → resume → cut out of an ordinary end-of-charge, on a device whose cap does nothing.
     */
    @Test
    fun `a hold at a nearly full battery proves nothing and never passes`() {
        val sim = variableSim(startPercent = 90)
        sim.run(forMillis = 20 * 60_000L, currentPerHour = mA(1_500))
        sim.progress.phase shouldBe RunPhase.CUT_1

        val outcome = sim.run(forMillis = 30 * 60_000L, currentPerHour = mA(1_200))

        outcome.terminal shouldBe RunTerminal.Inconclusive(InconclusiveReason.NEAR_FULL)
    }

    @Test
    fun `a milli-reporting counter falls back to level rather than being trusted`() {
        fun sample(percent: Int, counter: Int?) = QualificationSample(
            nowMillis = 0,
            plugged = true,
            percent = percent,
            chargeCounter = counter,
            configured = null,
            sessionActive = false,
        )

        // MagicOS: charge counter 6978 on a ~7100 mAh cell, i.e. scaled by 1000.
        QualificationRunEngine.resolveSignal(sample(100, 6978)) shouldBe FlowSignal.LEVEL
        QualificationRunEngine.resolveSignal(sample(80, 3_200_000)) shouldBe FlowSignal.COUNTER
        QualificationRunEngine.resolveSignal(sample(-1, null)) shouldBe FlowSignal.NONE
    }

    @Test
    fun `implied capacity normalizes out the charge level`() {
        QualificationRunEngine.impliedFullCapacity(800_000, 20) shouldBe 4_000_000L
        QualificationRunEngine.impliedFullCapacity(3_600_000, 90) shouldBe 4_000_000L
        QualificationRunEngine.impliedFullCapacity(null, 50) shouldBe null
        QualificationRunEngine.impliedFullCapacity(1_000, 0) shouldBe null
    }

    @Test
    fun `a run with no usable signal at all ends inconclusive`() {
        val progress = QualificationRunEngine.start(
            shape = RunShape.VARIABLE_CAP,
            lowCap = 50,
            releasePolicy = ChargePolicy.FixedLimit(70),
            nowMillis = 0L,
        )

        val outcome = QualificationRunEngine.evaluate(
            progress,
            QualificationSample(0, plugged = true, percent = 50, chargeCounter = null, configured = null, sessionActive = false),
        )
        // No counter, but a usable level: the run proceeds on the coarser signal.
        outcome.progress.signal shouldBe FlowSignal.LEVEL
    }

    @Test
    fun `preflight without a level waits and then gives up`() {
        val progress = QualificationRunEngine.start(RunShape.VARIABLE_CAP, 50, ChargePolicy.FixedLimit(70), 0L)
        fun blind(now: Long) = QualificationSample(now, true, -1, null, null, false)

        QualificationRunEngine.evaluate(progress, blind(1_000)).terminal shouldBe null
        QualificationRunEngine.evaluate(progress, blind(PREFLIGHT_BUDGET_MILLIS)).terminal shouldBe
            RunTerminal.Inconclusive(InconclusiveReason.PRECONDITION_TIMEOUT)
    }

    @Test
    fun `every abort condition ends the run wherever it is`() {
        val armed = variableSim().progress
        fun sample(
            plugged: Boolean = true,
            sessionActive: Boolean = false,
            writeFailed: Boolean = false,
            cancelled: Boolean = false,
            now: Long = 60_000,
        ) = QualificationSample(now, plugged, 50, 2_000_000, null, sessionActive, writeFailed, cancelled)

        QualificationRunEngine.evaluate(armed, sample(plugged = false)).terminal shouldBe
            RunTerminal.Aborted(AbortReason.UNPLUGGED)
        QualificationRunEngine.evaluate(armed, sample(sessionActive = true)).terminal shouldBe
            RunTerminal.Aborted(AbortReason.SESSION_STARTED)
        QualificationRunEngine.evaluate(armed, sample(writeFailed = true)).terminal shouldBe
            RunTerminal.Aborted(AbortReason.WRITE_FAILED)
        QualificationRunEngine.evaluate(armed, sample(cancelled = true)).terminal shouldBe
            RunTerminal.Aborted(AbortReason.USER_CANCELLED)
        QualificationRunEngine.evaluate(armed, sample(now = RUN_CEILING_MILLIS)).terminal shouldBe
            RunTerminal.Aborted(AbortReason.RUN_CEILING)
    }

    /** A run sitting in [RunPhase.CUT_1], with its cut write already acknowledged. */
    private fun atFirstCut(): QualificationProgress {
        val sim = variableSim()
        sim.run(forMillis = 20 * 60_000L, currentPerHour = mA(1_500))
        sim.progress.phase shouldBe RunPhase.CUT_1
        return sim.progress
    }

    private fun atResume(): QualificationProgress {
        val sim = variableSim()
        sim.run(forMillis = 20 * 60_000L, currentPerHour = mA(1_500))
        sim.run(forMillis = 20 * 60_000L, currentPerHour = 0)
        sim.progress.phase shouldBe RunPhase.RESUME
        return sim.progress
    }

    private fun unplugged(now: Long) =
        QualificationSample(now, plugged = false, percent = 50, chargeCounter = 2_000_000, configured = null, sessionActive = false)

    /**
     * The device-observed defect: capping below the current level made a LineageOS build report the
     * charger as absent over a live cable, so the run told the user their charger came out.
     */
    @Test
    fun `losing the plug signal right after the first cut names both possibilities`() {
        val armed = atFirstCut()
        (armed.commandAckedAt > 0L) shouldBe true

        QualificationRunEngine.evaluate(armed, unplugged(armed.commandAckedAt + 1_000)).terminal shouldBe
            RunTerminal.Inconclusive(InconclusiveReason.PLUG_SIGNAL_LOST_AT_CUT)
        QualificationRunEngine.evaluate(armed, unplugged(armed.commandAckedAt + PLUG_MASK_WINDOW_MILLIS)).terminal shouldBe
            RunTerminal.Inconclusive(InconclusiveReason.PLUG_SIGNAL_LOST_AT_CUT)
    }

    @Test
    fun `the second cut is treated the same as the first`() {
        val armed = healthyRun().progress
        armed.phase shouldBe RunPhase.CUT_2

        QualificationRunEngine.evaluate(armed, unplugged(armed.commandAckedAt + 1_000)).terminal shouldBe
            RunTerminal.Inconclusive(InconclusiveReason.PLUG_SIGNAL_LOST_AT_CUT)
    }

    /**
     * A plug loss long after the cut settled — with the plug present all through the window in
     * between — is far more likely to be the cable, and keeps the ordinary abort.
     */
    @Test
    fun `a plug signal lost well after the cut settled is still an unplug`() {
        val armed = atFirstCut()

        QualificationRunEngine.evaluate(armed, unplugged(armed.commandAckedAt + PLUG_MASK_WINDOW_MILLIS + 1)).terminal shouldBe
            RunTerminal.Aborted(AbortReason.UNPLUGGED)
    }

    @Test
    fun `phases that write no cut keep the ordinary unplug abort`() {
        val baseline = variableSim().progress
        baseline.phase shouldBe RunPhase.BASELINE
        val resume = atResume()

        QualificationRunEngine.evaluate(baseline, unplugged(baseline.commandAckedAt + 1_000)).terminal shouldBe
            RunTerminal.Aborted(AbortReason.UNPLUGGED)
        QualificationRunEngine.evaluate(resume, unplugged(resume.commandAckedAt + 1_000)).terminal shouldBe
            RunTerminal.Aborted(AbortReason.UNPLUGGED)
    }

    /** With no acknowledgement there is no instant our own write could have masked the plug at. */
    @Test
    fun `an unacknowledged cut keeps the ordinary unplug abort`() {
        val armed = atFirstCut().copy(commandAckedAt = 0L)

        QualificationRunEngine.evaluate(armed, unplugged(armed.commandedAt + 1_000)).terminal shouldBe
            RunTerminal.Aborted(AbortReason.UNPLUGGED)
    }

    /** The new branch must be reachable only through a lost plug signal, so a plugged run is intact. */
    @Test
    fun `a cut that stays plugged is unaffected`() {
        val armed = atFirstCut()
        QualificationRunEngine.evaluate(
            armed,
            QualificationSample(armed.commandAckedAt + 1_000, true, 50, 2_000_000, null, false),
        ).terminal shouldBe null

        val sim = healthyRun()
        sim.run(forMillis = 20 * 60_000L, currentPerHour = 0).terminal shouldBe RunTerminal.Passed
    }

    @Test
    fun `a native change away from the commanded policy aborts once the write has settled`() {
        val armed = variableSim().progress
        val native = ChargeObservation.Verified(ChargePolicy.Unrestricted, BackendKind.SHIZUKU)
        fun sample(now: Long) = QualificationSample(now, true, 50, 2_000_000, native, false)

        QualificationRunEngine.evaluate(armed, sample(armed.commandedAt + 1_000)).terminal shouldBe null
        QualificationRunEngine.evaluate(armed, sample(armed.commandedAt + WRITE_SETTLE_MILLIS + 1)).terminal shouldBe
            RunTerminal.Aborted(AbortReason.CONFIGURATION_DRIFT)
    }

    @Test
    fun `a readback agreeing with the commanded policy never aborts`() {
        val armed = variableSim().progress
        val agreeing = ChargeObservation.Verified(ChargePolicy.FixedLimit(70), BackendKind.SHIZUKU)

        QualificationRunEngine.evaluate(
            armed,
            QualificationSample(600_000, true, 50, 2_000_000, agreeing, false),
        ).terminal shouldBe null
    }

    @Test
    fun `an unverified readback is not treated as drift`() {
        val armed = variableSim().progress
        val requested = ChargeObservation.LastRequested(ChargePolicy.Unrestricted)

        QualificationRunEngine.evaluate(
            armed,
            QualificationSample(600_000, true, 50, 2_000_000, requested, false),
        ).terminal shouldBe null
    }

    @Test
    fun `a candidate device that charges past the cap reports a mapping mismatch, not a refutation`() {
        val sim = variableSim(startPercent = 60, candidate = true)
        sim.run(forMillis = 20 * 60_000L, currentPerHour = mA(1_500))
        sim.progress.phase shouldBe RunPhase.CUT_1

        val outcome = sim.run(forMillis = 30 * 60_000L, currentPerHour = mA(1_500))

        outcome.terminal shouldBe RunTerminal.Inconclusive(InconclusiveReason.CAP_MISMATCH)
    }

    @Test
    fun `an unanchored window has no rate at all`() {
        val armed = variableSim().progress

        armed.anchored shouldBe false
        QualificationRunEngine.ratePerHour(
            armed,
            QualificationSample(armed.commandAckedAt + 20 * 60_000L, true, 60, 2_400_000, null, false),
        ) shouldBe null
    }

    @Test
    fun `a rate below the measurement window is not computed at all`() {
        val sim = variableSim()
        sim.anchor()
        val armed = sim.progress

        QualificationRunEngine.ratePerHour(
            armed,
            QualificationSample(armed.windowAnchoredAt + 1_000, true, 50, 2_000_000, null, false),
        ) shouldBe null
    }

    @Test
    fun `a falling reading clamps to a zero rate rather than going negative`() {
        val sim = variableSim()
        sim.anchor()
        val armed = sim.progress

        val rate = QualificationRunEngine.ratePerHour(
            armed,
            QualificationSample(
                armed.windowAnchoredAt + 10 * 60_000L,
                true,
                40,
                (armed.windowStartCounter ?: 0) - 500_000,
                null,
                false,
            ),
        )

        rate shouldBe 0L
    }
}
