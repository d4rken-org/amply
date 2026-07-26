package eu.darken.amply.stats.core

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class StatsRetentionTest {

    @Test
    fun `days are clamped into the offered range`() {
        StatsRetention.clampDays(StatsRetention.MIN_DAYS - 1) shouldBe StatsRetention.MIN_DAYS
        StatsRetention.clampDays(0) shouldBe StatsRetention.MIN_DAYS
        StatsRetention.clampDays(-7) shouldBe StatsRetention.MIN_DAYS
        StatsRetention.clampDays(StatsRetention.MAX_DAYS + 1) shouldBe StatsRetention.MAX_DAYS
        StatsRetention.clampDays(365) shouldBe StatsRetention.MAX_DAYS
    }

    @Test
    fun `days inside the range pass through`() {
        (StatsRetention.MIN_DAYS..StatsRetention.MAX_DAYS).forEach { StatsRetention.clampDays(it) shouldBe it }
    }

    @Test
    fun `cutoff is the window subtracted from now`() {
        StatsRetention.cutoffWallMillis(NOW, 3) shouldBe NOW - 3 * DAY
        StatsRetention.cutoffWallMillis(NOW, 14) shouldBe NOW - 14 * DAY
    }

    @Test
    fun `an out of range stored value can neither widen nor collapse the window`() {
        // A corrupted/oversized value must not turn retention into "keep a year"…
        StatsRetention.cutoffWallMillis(NOW, 365) shouldBe NOW - StatsRetention.MAX_DAYS * DAY
        // …and a zero/negative one must not purge everything up to (or past) now.
        StatsRetention.cutoffWallMillis(NOW, 0) shouldBe NOW - StatsRetention.MIN_DAYS * DAY
        StatsRetention.cutoffWallMillis(NOW, -30) shouldBe NOW - StatsRetention.MIN_DAYS * DAY
    }

    private companion object {
        const val DAY = 24L * 60 * 60 * 1000
        const val NOW = 1_700_000_000_000L
    }
}
