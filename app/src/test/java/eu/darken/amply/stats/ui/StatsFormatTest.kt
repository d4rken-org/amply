package eu.darken.amply.stats.ui

import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import java.time.ZoneId
import java.util.Locale
import org.junit.jupiter.api.Test

class StatsFormatTest {

    // 2021-01-01T00:00:00Z
    private val millis = 1_609_459_200_000L

    @Test
    fun `dateTime honours the zone and locale per call`() {
        val utc = StatsFormat.dateTime(millis, ZoneId.of("UTC"), Locale.US)
        val tokyo = StatsFormat.dateTime(millis, ZoneId.of("Asia/Tokyo"), Locale.US)
        utc shouldContain "2021"
        // Same instant, different zone → different rendered time (Tokyo is +9h).
        utc shouldNotBe tokyo
    }

    @Test
    fun `percentSpan formats a min to max range and is null-safe`() {
        StatsFormat.percentSpan(42, 100) shouldBe "42→100%"
        StatsFormat.percentSpan(null, 100) shouldBe null
        StatsFormat.percentSpan(42, null) shouldBe null
    }

    @Test
    fun `elapsedAxis shows seconds under a minute, minutes above`() {
        StatsFormat.elapsedAxis(0) shouldBe "0s"
        StatsFormat.elapsedAxis(45_000) shouldBe "45s"
        StatsFormat.elapsedAxis(90_000) shouldBe "1m"
    }

    @Test
    fun `power and temperature spans carry units and are null-safe`() {
        StatsFormat.powerSpan(null, 10_000) shouldBe null
        StatsFormat.powerSpan(1_500, 25_000)!! shouldContain "W"
        StatsFormat.temperatureSpan(null, 300) shouldBe null
        StatsFormat.temperatureSpan(280, 340)!! shouldContain "°C"
    }
}
