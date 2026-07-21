package eu.darken.amply.charging.core

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class HyperOsVersionDetectorTest {

    @Test
    fun `valid property values parse`() {
        HyperOsVersionDetector.parse("2") shouldBe 2
        HyperOsVersionDetector.parse(" 3 ") shouldBe 3
    }

    @Test
    fun `absent or malformed values yield null`() {
        HyperOsVersionDetector.parse(null) shouldBe null
        HyperOsVersionDetector.parse("") shouldBe null
        HyperOsVersionDetector.parse("OS2.0") shouldBe null
        HyperOsVersionDetector.parse("-1") shouldBe null
        HyperOsVersionDetector.parse("0") shouldBe null
    }
}
