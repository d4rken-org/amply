package eu.darken.amply.stats.core

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class StatsRetentionTest {

    @Test
    fun `stored values normalize onto a preset`() {
        listOf(
            // A zero/negative value must not purge everything up to (or past) now.
            0 to StatsRetention.MIN_DAYS,
            -7 to StatsRetention.MIN_DAYS,
            3 to 3,
            // Between presets snaps up, never narrowing a window the user already had.
            4 to 7,
            10 to 14,
            14 to 14,
            15 to 30,
            365 to 365,
            // An oversized finite value must not turn into "keep forever".
            366 to StatsRetention.MAX_DAYS,
            StatsRetention.FOREVER to StatsRetention.FOREVER,
        ).forEach { (stored, expected) -> StatsRetention.normalize(stored) shouldBe expected }
    }

    @Test
    fun `every preset normalizes to itself`() {
        StatsRetention.PRESETS.forEach { StatsRetention.normalize(it) shouldBe it }
    }

    @Test
    fun `cutoff for a finite preset is the window subtracted from now`() {
        StatsRetention.cutoffWallMillis(NOW, 3) shouldBe NOW - 3 * DAY
        StatsRetention.cutoffWallMillis(NOW, 14) shouldBe NOW - 14 * DAY
        StatsRetention.cutoffWallMillis(NOW, 365) shouldBe NOW - 365 * DAY
    }

    @Test
    fun `cutoff uses the normalized window`() {
        StatsRetention.cutoffWallMillis(NOW, 10) shouldBe NOW - 14 * DAY
        StatsRetention.cutoffWallMillis(NOW, 0) shouldBe NOW - StatsRetention.MIN_DAYS * DAY
        StatsRetention.cutoffWallMillis(NOW, -30) shouldBe NOW - StatsRetention.MIN_DAYS * DAY
    }

    @Test
    fun `cutoff for forever keeps every wall stamp`() {
        StatsRetention.cutoffWallMillis(NOW, StatsRetention.FOREVER) shouldBe Long.MIN_VALUE
    }

    @Test
    fun `forever is recognized only for the sentinel`() {
        StatsRetention.isForever(StatsRetention.FOREVER) shouldBe true
        StatsRetention.isForever(StatsRetention.MAX_DAYS) shouldBe false
    }

    @Test
    fun `preset index follows the normalized value`() {
        StatsRetention.PRESETS.forEachIndexed { index, preset ->
            StatsRetention.presetIndexOf(preset) shouldBe index
        }
        StatsRetention.presetIndexOf(10) shouldBe StatsRetention.PRESETS.indexOf(14)
    }

    private companion object {
        const val DAY = 24L * 60 * 60 * 1000
        const val NOW = 1_700_000_000_000L
    }
}
