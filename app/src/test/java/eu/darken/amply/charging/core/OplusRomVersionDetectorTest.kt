package eu.darken.amply.charging.core

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class OplusRomVersionDetectorTest {

    @Test
    fun `versioned rom strings parse to the major version`() {
        OplusRomVersionDetector.parse("V15.0.0") shouldBe 15
        OplusRomVersionDetector.parse("v14.0.1") shouldBe 14
        OplusRomVersionDetector.parse(" V15.0.0 ") shouldBe 15
        OplusRomVersionDetector.parse("15") shouldBe 15
    }

    @Test
    fun `absent or malformed values yield null`() {
        OplusRomVersionDetector.parse(null) shouldBe null
        OplusRomVersionDetector.parse("") shouldBe null
        OplusRomVersionDetector.parse("V") shouldBe null
        OplusRomVersionDetector.parse("OS2.0") shouldBe null
        OplusRomVersionDetector.parse("0") shouldBe null
    }
}
