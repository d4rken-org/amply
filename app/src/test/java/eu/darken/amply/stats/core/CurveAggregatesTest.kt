package eu.darken.amply.stats.core

import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class CurveAggregatesTest {

    private fun point(
        elapsed: Long,
        percent: Int? = null,
        power: Int? = null,
        voltage: Int? = null,
        current: Int? = null,
        temperature: Int? = null,
    ) = ChargeCurvePoint(
        elapsedFromStartMillis = elapsed,
        percent = percent,
        powerMilliwatts = power,
        temperatureTenthsC = temperature,
        voltageMillivolts = voltage,
        currentNowMicroamps = current,
    )

    @Test
    fun `an extreme the decimator would drop is still the maximum`() {
        // Decimation pins the first and last point and thins the interior, so a one-sample spike in
        // between simply isn't in the plotted curve. Aggregating the raw list must still see it.
        val points = listOf(
            point(elapsed = 0L, temperature = 300),
            point(elapsed = 60_000L, temperature = 480),
            point(elapsed = 120_000L, temperature = 300),
        )
        StatsDownsampler.decimate(points, 2).mapNotNull { it.temperatureTenthsC }.max() shouldBe 300

        val stats = CurveAggregates.of(points).temperature!!
        stats.max shouldBe 480
        stats.min shouldBe 300
        stats.sampleCount shouldBe 3
    }

    @Test
    fun `the average is time-weighted, not a plain mean`() {
        // Three dense samples while charging fast at 10 W, then a sparse 2 W tail — exactly what the
        // recorder's level-change cadence produces. The plain mean of the five samples is 6.8 W; the
        // time-weighted answer over the same 12 minutes is 4.0 W.
        val points = listOf(
            point(elapsed = 0L, power = 10_000),
            point(elapsed = 60_000L, power = 10_000),
            point(elapsed = 120_000L, power = 10_000),
            point(elapsed = 180_000L, power = 2_000),
            point(elapsed = 720_000L, power = 2_000),
        )
        val stats = CurveAggregates.of(points).power!!
        stats.avg shouldBe 4_000
        stats.min shouldBe 2_000
        stats.max shouldBe 10_000
        stats.sampleCount shouldBe 5
    }

    @Test
    fun `a gap longer than the weight cap cannot dominate the average`() {
        // A 10h stall on 1 mA followed by a normal 5 min interval at 2 A. Uncapped, the stale
        // reading would own 99% of the weight; the cap credits it at most 10 minutes.
        val points = listOf(
            point(elapsed = 0L, current = 1_000),
            point(elapsed = 36_000_000L, current = 2_000_000),
            point(elapsed = 36_300_000L, current = 2_000_000),
        )
        val stats = CurveAggregates.of(points).current!!
        // (1_000 * 600_000 + 2_000_000 * 300_000) / 900_000
        stats.avg shouldBe 667_333
    }

    @Test
    fun `current sums accumulate beyond the range of an Int`() {
        // 1000 samples at 2.1 A: the raw sum is 2.1e9, past Int.MAX_VALUE. A naive Int accumulator
        // would wrap negative here.
        val points = (0 until 1_000).map { i -> point(elapsed = i * 20_000L, current = 2_100_000) }
        val stats = CurveAggregates.of(points).current!!
        stats.avg shouldBe 2_100_000
        stats.min shouldBe 2_100_000
        stats.max shouldBe 2_100_000
        stats.sampleCount shouldBe 1_000
    }

    @Test
    fun `a metric nothing reported has no statistics at all`() {
        val points = (0..5).map { i -> point(elapsed = i * 20_000L, percent = 40 + i) }
        val aggregates = CurveAggregates.of(points)
        aggregates.voltage.shouldBeNull()
        aggregates.current.shouldBeNull()
        aggregates.power.shouldBeNull()
        aggregates.level!!.sampleCount shouldBe 6
    }

    @Test
    fun `a single sample reports itself as min, avg and max`() {
        val stats = CurveAggregates.of(listOf(point(elapsed = 0L, voltage = 4_185))).voltage!!
        stats shouldBe MetricStats(min = 4_185, avg = 4_185, max = 4_185, sampleCount = 1)
    }

    @Test
    fun `an empty curve aggregates to nothing`() {
        CurveAggregates.of(emptyList()) shouldBe CurveAggregates.EMPTY
    }

    @Test
    fun `an interval whose leading sample is missing the metric is not credited`() {
        // The 2 A reading is only ever the *trailing* sample of an interval, so it carries no weight
        // — a null reading must not have its neighbour's value carried forward across the gap.
        val points = listOf(
            point(elapsed = 0L, current = 1_000_000),
            point(elapsed = 60_000L, current = null),
            point(elapsed = 120_000L, current = 2_000_000),
        )
        val stats = CurveAggregates.of(points).current!!
        stats.avg shouldBe 1_000_000
        stats.sampleCount shouldBe 2
    }
}
