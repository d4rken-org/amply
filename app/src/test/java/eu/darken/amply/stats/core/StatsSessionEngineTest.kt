package eu.darken.amply.stats.core

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class StatsSessionEngineTest {

    private fun sample(
        elapsed: Long,
        wall: Long = elapsed,
        plugged: Boolean = true,
        percent: Int? = 50,
        power: Int? = null,
        temp: Int? = null,
        full: Boolean = false,
        override: Boolean = false,
        limit: Boolean = false,
    ) = StatsSample(
        elapsedRealtimeMillis = elapsed,
        wallMillis = wall,
        bootId = 1,
        plugged = plugged,
        pluggedRaw = if (plugged) 1 else 0,
        percent = percent,
        batteryStatus = null,
        chargingStatus = null,
        temperatureTenthsC = temp,
        voltageMillivolts = null,
        currentNowMicroamps = null,
        powerMilliwatts = power,
        full = full,
        overrideActive = override,
        limitHeldNow = limit,
    )

    @Test
    fun `unplugged with no open session is ignored`() {
        StatsSessionEngine.decide(false, previousPlugged = false, plugged = false, recordDue = true) shouldBe
            StatsTransition.Ignore
    }

    @Test
    fun `clean start after an unplugged tick is not partial`() {
        StatsSessionEngine.decide(false, previousPlugged = false, plugged = true, recordDue = true) shouldBe
            StatsTransition.Open(partial = false)
    }

    @Test
    fun `first-ever tick already plugged is a partial start`() {
        StatsSessionEngine.decide(false, previousPlugged = null, plugged = true, recordDue = true) shouldBe
            StatsTransition.Open(partial = true)
    }

    @Test
    fun `plugged with an open session appends, gated by record`() {
        StatsSessionEngine.decide(true, previousPlugged = true, plugged = true, recordDue = false) shouldBe
            StatsTransition.Append(record = false)
        StatsSessionEngine.decide(true, previousPlugged = true, plugged = true, recordDue = true) shouldBe
            StatsTransition.Append(record = true)
    }

    @Test
    fun `unplugging an open session seals it`() {
        StatsSessionEngine.decide(true, previousPlugged = true, plugged = false, recordDue = true) shouldBe
            StatsTransition.Seal(StatsSealReason.UNPLUGGED)
    }

    @Test
    fun `starting already full marks the session partial`() {
        val opened = StatsSessionEngine.open(sample(elapsed = 0, percent = 100, full = true), partial = false)
        opened.partial shouldBe true
        opened.fullReachedAtWallMillis shouldBe 0L
    }

    @Test
    fun `time-weighted average reflects step durations, not sample count`() {
        // 1000 mW for 10s, 2000 mW for 10s, 3000 mW for 10s → time-weighted mean = 2000 mW.
        var s = StatsSessionEngine.open(sample(elapsed = 0, power = 1000), partial = false)
        s = StatsSessionEngine.fold(s, sample(elapsed = 10_000, power = 2000))
        s = StatsSessionEngine.fold(s, sample(elapsed = 20_000, power = 3000))
        val sealed = StatsSessionEngine.seal(
            s,
            StatsSealReason.UNPLUGGED,
            endWallMillis = 30_000,
            endElapsedMillis = 30_000,
            endPercent = 90,
        )
        sealed.avgPowerMilliwatts shouldBe 2000
        sealed.runningPeakPowerMilliwatts shouldBe 3000
        sealed.endPercent shouldBe 90
        sealed.endReason shouldBe StatsSealReason.UNPLUGGED.name
    }

    @Test
    fun `a single-sample session averages to that sample`() {
        val s = StatsSessionEngine.open(sample(elapsed = 0, power = 1500, temp = 250), partial = false)
        val sealed = StatsSessionEngine.seal(
            s,
            StatsSealReason.UNPLUGGED,
            endWallMillis = 0,
            endElapsedMillis = 0,
            endPercent = 42,
        )
        sealed.avgPowerMilliwatts shouldBe 1500
        sealed.avgTemperatureTenthsC shouldBe 250
    }

    @Test
    fun `absent power leaves that interval uncredited`() {
        // 2000 mW for 10s, then a gap with no power reading, then unplug.
        var s = StatsSessionEngine.open(sample(elapsed = 0, power = 2000), partial = false)
        s = StatsSessionEngine.fold(s, sample(elapsed = 10_000, power = null))
        val sealed = StatsSessionEngine.seal(
            s,
            StatsSealReason.UNPLUGGED,
            endWallMillis = 20_000,
            endElapsedMillis = 20_000,
            endPercent = 60,
        )
        // Only the first 10s (at 2000 mW) is credited; the null-power tail contributes nothing.
        sealed.avgPowerMilliwatts shouldBe 2000
    }

    @Test
    fun `recovery seal marks the session partial`() {
        val s = StatsSessionEngine.open(sample(elapsed = 0, power = 1000), partial = false)
        val sealed = StatsSessionEngine.seal(
            s,
            StatsSealReason.INTERRUPTED,
            endWallMillis = 5_000,
            endElapsedMillis = 5_000,
            endPercent = 55,
        )
        sealed.partial shouldBe true
    }

    @Test
    fun `override and limit evidence latch across folds`() {
        var s = StatsSessionEngine.open(sample(elapsed = 0, limit = true), partial = false)
        s = StatsSessionEngine.fold(s, sample(elapsed = 10_000, limit = false, override = true))
        s = StatsSessionEngine.fold(s, sample(elapsed = 20_000))
        s.limitHitEvidence shouldBe true
        s.overrideSeen shouldBe true
    }

    @Test
    fun `latchEvidence captures a hold seen only in a below-cadence sample`() {
        val opened = StatsSessionEngine.open(sample(elapsed = 0, limit = false), partial = false)
        opened.limitHitEvidence shouldBe false
        val updated = StatsSessionEngine.latchEvidence(opened, sample(elapsed = 5_000, limit = true))
        updated?.limitHitEvidence shouldBe true
    }

    @Test
    fun `latchEvidence captures an override seen only in a below-cadence sample`() {
        val opened = StatsSessionEngine.open(sample(elapsed = 0), partial = false)
        val updated = StatsSessionEngine.latchEvidence(opened, sample(elapsed = 5_000, override = true))
        updated?.overrideSeen shouldBe true
    }

    @Test
    fun `latchEvidence returns null when no sticky flag changes`() {
        val opened = StatsSessionEngine.open(sample(elapsed = 0, limit = true), partial = false)
        // Already latched → no write needed.
        StatsSessionEngine.latchEvidence(opened, sample(elapsed = 5_000, limit = true)) shouldBe null
        // No new evidence at all → no write needed.
        StatsSessionEngine.latchEvidence(
            StatsSessionEngine.open(sample(elapsed = 0), partial = false),
            sample(elapsed = 5_000),
        ) shouldBe null
    }

    @Test
    fun `latchEvidence leaves aggregates and the curve untouched`() {
        val opened = StatsSessionEngine.open(sample(elapsed = 0, power = 1000), partial = false)
        val updated = StatsSessionEngine.latchEvidence(opened, sample(elapsed = 5_000, power = 9999, limit = true))
        // Only the sticky flag moved — sample count / running power endpoint / timestamps unchanged.
        updated?.runningSampleCount shouldBe opened.runningSampleCount
        updated?.runningLastPowerMilliwatts shouldBe opened.runningLastPowerMilliwatts
        updated?.runningLastElapsedRealtimeMillis shouldBe opened.runningLastElapsedRealtimeMillis
    }

    @Test
    fun `isDiscardable drops a contentless immediate seal (the toggle-on-off artifact)`() {
        val opened = StatsSessionEngine.open(sample(elapsed = 0, percent = 50), partial = true)
        val sealed = StatsSessionEngine.seal(
            opened, StatsSealReason.DISABLED, endWallMillis = 0, endElapsedMillis = 0, endPercent = 50,
        )
        StatsSessionEngine.isDiscardable(sealed) shouldBe true
    }

    @Test
    fun `isDiscardable retains a short but nonzero-duration single-sample unplug`() {
        // A genuine plug pulled within one cadence window: one sample, but real elapsed time.
        val opened = StatsSessionEngine.open(sample(elapsed = 0, percent = 50), partial = false)
        val sealed = StatsSessionEngine.seal(
            opened, StatsSealReason.UNPLUGGED, endWallMillis = 10_000, endElapsedMillis = 10_000, endPercent = 50,
        )
        StatsSessionEngine.isDiscardable(sealed) shouldBe false
    }

    @Test
    fun `isDiscardable retains a zero-duration session that gained charge`() {
        val opened = StatsSessionEngine.open(sample(elapsed = 0, percent = 50), partial = false)
        val sealed = StatsSessionEngine.seal(
            opened, StatsSealReason.DISABLED, endWallMillis = 0, endElapsedMillis = 0, endPercent = 60,
        )
        StatsSessionEngine.isDiscardable(sealed) shouldBe false
    }

    @Test
    fun `isDiscardable still drops a contentless artifact that latched limit evidence at an OEM hold`() {
        // Toggling capture on/off while the device is being held at an OEM limit latches
        // limitHitEvidence on the single opening sample, but the row still spans no time and has no
        // curve — it is the same empty artifact and must not survive.
        val opened = StatsSessionEngine.open(sample(elapsed = 0, percent = 50, limit = true), partial = false)
        val sealed = StatsSessionEngine.seal(
            opened, StatsSealReason.DISABLED, endWallMillis = 0, endElapsedMillis = 0, endPercent = 50,
        )
        StatsSessionEngine.isDiscardable(sealed) shouldBe true
    }

    @Test
    fun `isDiscardable retains a real hold session that accrued elapsed time`() {
        // A genuine limit-hold session accrues duration and samples, so it is kept.
        var s = StatsSessionEngine.open(sample(elapsed = 0, percent = 80, limit = true), partial = false)
        s = StatsSessionEngine.fold(s, sample(elapsed = 30_000, percent = 80, limit = true))
        val sealed = StatsSessionEngine.seal(
            s, StatsSealReason.UNPLUGGED, endWallMillis = 30_000, endElapsedMillis = 30_000, endPercent = 80,
        )
        StatsSessionEngine.isDiscardable(sealed) shouldBe false
    }

    @Test
    fun `isDiscardable retains a multi-sample session`() {
        var s = StatsSessionEngine.open(sample(elapsed = 0, percent = 50), partial = false)
        s = StatsSessionEngine.fold(s, sample(elapsed = 10_000, percent = 50))
        val sealed = StatsSessionEngine.seal(
            s, StatsSealReason.UNPLUGGED, endWallMillis = 10_000, endElapsedMillis = 10_000, endPercent = 50,
        )
        StatsSessionEngine.isDiscardable(sealed) shouldBe false
    }

    @Test
    fun `a doze gap is clamped so it cannot overweight one reading`() {
        // 1000 mW then a 1-hour gap: only MAX_WEIGHT_GAP_MILLIS of it is credited.
        var s = StatsSessionEngine.open(sample(elapsed = 0, power = 1000), partial = false)
        s = StatsSessionEngine.fold(s, sample(elapsed = 3_600_000, power = 1000))
        s.runningPowerWeightedDurationMillis shouldBe StatsSessionEngine.MAX_WEIGHT_GAP_MILLIS
    }

    // --- evaluateResume: reconciling a session left open by a process death ---

    /** An open row as it would be found at process start: opened at t=0, last seen at t=30s at 50%. */
    private fun openRow(
        bootId: Long = 1,
        lastElapsed: Long? = 30_000,
        lastPercent: Int? = 50,
        partial: Boolean = false,
        ended: Long? = null,
    ) = StatsSessionEngine.open(sample(elapsed = 0, percent = 40, power = 1000, temp = 250), partial = partial)
        .copy(
            id = 7,
            bootId = bootId,
            endedAtWallMillis = ended,
            runningLastElapsedRealtimeMillis = lastElapsed,
            runningLastPercent = lastPercent,
        )

    private fun probe(
        elapsed: Long = 60_000,
        bootId: Long = 1,
        plugged: Boolean = true,
        percent: Int? = 55,
    ) = ResumeProbe(
        elapsedRealtimeMillis = elapsed,
        bootId = bootId,
        plugged = plugged,
        percent = percent,
    )

    private fun rejectReason(decision: ResumeDecision) = (decision as ResumeDecision.Reject).reason

    private fun resumed(decision: ResumeDecision) = (decision as ResumeDecision.Resume).session

    @Test
    fun `still plugged on the same boot at an equal or higher level resumes`() {
        listOf(50, 55, 100).forEach { level ->
            val decision = StatsSessionEngine.evaluateResume(openRow(), probe(percent = level))
            resumed(decision).id shouldBe 7
        }
    }

    @Test
    fun `a resumed session keeps its start, so the card still shows the real plug-in time`() {
        val row = openRow()
        val session = resumed(StatsSessionEngine.evaluateResume(row, probe()))
        session.startedAtWallMillis shouldBe row.startedAtWallMillis
        session.startedElapsedRealtimeMillis shouldBe row.startedElapsedRealtimeMillis
        session.startPercent shouldBe row.startPercent
        session.runningSampleCount shouldBe row.runningSampleCount
        session.runningPowerWeightedSum shouldBe row.runningPowerWeightedSum
    }

    @Test
    fun `a resumed session is always partial - the gap is inferred, not observed`() {
        resumed(StatsSessionEngine.evaluateResume(openRow(), probe())).partial shouldBe true
    }

    @Test
    fun `resuming drops the last readings so the unobserved gap is never credited`() {
        val row = openRow()
        val session = resumed(StatsSessionEngine.evaluateResume(row, probe()))
        session.runningLastPowerMilliwatts shouldBe null
        session.runningLastTemperatureTenthsC shouldBe null

        // Folding the first post-restart sample credits nothing for the dead-process gap...
        val tick = sample(elapsed = 60_000, power = 2000, temp = 300)
        val folded = StatsSessionEngine.fold(session, tick)
        folded.runningPowerWeightedDurationMillis shouldBe 0
        folded.runningTemperatureWeightedDurationMillis shouldBe 0

        // ...whereas folding the row as-found would invent 30s of pre-death power/temperature across
        // a window nothing observed. That is the difference the null-out buys.
        val naive = StatsSessionEngine.fold(row, tick)
        naive.runningPowerWeightedDurationMillis shouldBe 30_000
        naive.runningPowerWeightedSum shouldBe 1000.0 * 30_000
    }

    @Test
    fun `an unplugged probe refuses - the charge that was running has ended`() {
        rejectReason(StatsSessionEngine.evaluateResume(openRow(), probe(plugged = false))) shouldBe
            ResumeDecision.Reason.UNPLUGGED
    }

    @Test
    fun `a different boot refuses`() {
        rejectReason(StatsSessionEngine.evaluateResume(openRow(bootId = 1), probe(bootId = 2))) shouldBe
            ResumeDecision.Reason.BOOT_MISMATCH
    }

    @Test
    fun `an unknown boot id refuses even though the sentinel compares equal to itself`() {
        val unknown = BootIdSource.UNAVAILABLE
        // Both sides carry the sentinel, so an equality check alone would wave a real reboot through
        // and splice two boots' elapsed-realtime readings into one bogus duration.
        rejectReason(StatsSessionEngine.evaluateResume(openRow(bootId = unknown), probe(bootId = unknown))) shouldBe
            ResumeDecision.Reason.BOOT_UNKNOWN
    }

    @Test
    fun `a probe older than the last recorded sample refuses`() {
        rejectReason(StatsSessionEngine.evaluateResume(openRow(lastElapsed = 30_000), probe(elapsed = 10_000))) shouldBe
            ResumeDecision.Reason.TIME_WENT_BACKWARDS
    }

    @Test
    fun `a dropped level refuses - the device discharged while we were gone`() {
        rejectReason(StatsSessionEngine.evaluateResume(openRow(lastPercent = 50), probe(percent = 49))) shouldBe
            ResumeDecision.Reason.LEVEL_DROPPED
    }

    @Test
    fun `an absent level on either side is not treated as a drop`() {
        resumed(StatsSessionEngine.evaluateResume(openRow(lastPercent = null), probe(percent = 10))).id shouldBe 7
        resumed(StatsSessionEngine.evaluateResume(openRow(lastPercent = 90), probe(percent = null))).id shouldBe 7
    }

    @Test
    fun `an already-sealed row is never resumed`() {
        rejectReason(StatsSessionEngine.evaluateResume(openRow(ended = 99_000), probe())) shouldBe
            ResumeDecision.Reason.CLOSED
    }

    @Test
    fun `a row with no recorded sample falls back to its start time for the ordering check`() {
        rejectReason(StatsSessionEngine.evaluateResume(openRow(lastElapsed = null), probe(elapsed = -1))) shouldBe
            ResumeDecision.Reason.TIME_WENT_BACKWARDS
        resumed(StatsSessionEngine.evaluateResume(openRow(lastElapsed = null), probe(elapsed = 0))).id shouldBe 7
    }
}
