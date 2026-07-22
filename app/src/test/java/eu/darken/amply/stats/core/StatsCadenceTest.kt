package eu.darken.amply.stats.core

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class StatsCadenceTest {

    @Test
    fun `first sample of a session always records`() {
        StatsCadence.shouldRecord(
            lastRecordedElapsedMillis = null,
            nowElapsedMillis = 10_000,
            lastRecordedPercent = null,
            currentPercent = 50,
        ) shouldBe true
    }

    @Test
    fun `within the interval and no percent change does not record`() {
        StatsCadence.shouldRecord(
            lastRecordedElapsedMillis = 100_000,
            nowElapsedMillis = 105_000,
            lastRecordedPercent = 50,
            currentPercent = 50,
        ) shouldBe false
    }

    @Test
    fun `elapsed interval reached records`() {
        StatsCadence.shouldRecord(
            lastRecordedElapsedMillis = 100_000,
            nowElapsedMillis = 100_000 + StatsCadence.CHARGING_MIN_INTERVAL_MILLIS,
            lastRecordedPercent = 50,
            currentPercent = 50,
        ) shouldBe true
    }

    @Test
    fun `percent change records even within the interval`() {
        StatsCadence.shouldRecord(
            lastRecordedElapsedMillis = 100_000,
            nowElapsedMillis = 102_000,
            lastRecordedPercent = 50,
            currentPercent = 51,
        ) shouldBe true
    }

    @Test
    fun `backwards elapsed jump records rather than starving the curve`() {
        StatsCadence.shouldRecord(
            lastRecordedElapsedMillis = 100_000,
            nowElapsedMillis = 90_000,
            lastRecordedPercent = 50,
            currentPercent = 50,
        ) shouldBe true
    }
}
