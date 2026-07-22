package eu.darken.amply.stats.core

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class StatsDownsamplerTest {

    @Test
    fun `lists at or below the cap are returned unchanged`() {
        val points = (1..10).toList()
        StatsDownsampler.decimate(points, 10) shouldBe points
        StatsDownsampler.decimate(points, 20) shouldBe points
    }

    @Test
    fun `decimation keeps first and last and respects the cap`() {
        val points = (0..999).toList()
        val result = StatsDownsampler.decimate(points, 100)
        result.size shouldBe 100
        result.first() shouldBe 0
        result.last() shouldBe 999
    }

    @Test
    fun `degenerate caps are handled`() {
        val points = (0..50).toList()
        StatsDownsampler.decimate(points, 1) shouldBe points
        StatsDownsampler.decimate(points, 0) shouldBe points
    }
}
