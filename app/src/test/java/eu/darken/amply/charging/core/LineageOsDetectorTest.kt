package eu.darken.amply.charging.core

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class LineageOsDetectorTest {

    @Test
    fun `a present version string means LineageOS`() {
        LineageOsDetector.parse("23.2") shouldBe "23.2"
        LineageOsDetector.parse(" 22.1 ") shouldBe "22.1" // trimmed
    }

    @Test
    fun `null or blank means not LineageOS`() {
        LineageOsDetector.parse(null) shouldBe null
        LineageOsDetector.parse("") shouldBe null
        LineageOsDetector.parse("   ") shouldBe null
    }
}
