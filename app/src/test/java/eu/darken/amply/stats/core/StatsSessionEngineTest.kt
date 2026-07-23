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
    fun `a doze gap is clamped so it cannot overweight one reading`() {
        // 1000 mW then a 1-hour gap: only MAX_WEIGHT_GAP_MILLIS of it is credited.
        var s = StatsSessionEngine.open(sample(elapsed = 0, power = 1000), partial = false)
        s = StatsSessionEngine.fold(s, sample(elapsed = 3_600_000, power = 1000))
        s.runningPowerWeightedDurationMillis shouldBe StatsSessionEngine.MAX_WEIGHT_GAP_MILLIS
    }
}
