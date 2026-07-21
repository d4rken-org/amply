package eu.darken.amply.charging.core

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class OneUiVersionDetectorTest {

    @Test
    fun `valid property values parse`() {
        OneUiVersionDetector.parse("80000") shouldBe 80000
        OneUiVersionDetector.parse("40100") shouldBe 40100
        OneUiVersionDetector.parse(" 50101 ") shouldBe 50101
    }

    @Test
    fun `absent or malformed values yield null`() {
        OneUiVersionDetector.parse(null) shouldBe null
        OneUiVersionDetector.parse("") shouldBe null
        OneUiVersionDetector.parse("   ") shouldBe null
        OneUiVersionDetector.parse("eight") shouldBe null
        OneUiVersionDetector.parse("-1") shouldBe null
        OneUiVersionDetector.parse("0") shouldBe null
    }
}
