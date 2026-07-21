package eu.darken.amply.charging.core

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class MiuiVersionDetectorTest {

    @Test
    fun `valid property values parse`() {
        MiuiVersionDetector.parse("816") shouldBe 816
        MiuiVersionDetector.parse(" 815 ") shouldBe 815
    }

    @Test
    fun `absent or malformed values yield null`() {
        MiuiVersionDetector.parse(null) shouldBe null
        MiuiVersionDetector.parse("") shouldBe null
        MiuiVersionDetector.parse("V816") shouldBe null
        MiuiVersionDetector.parse("-1") shouldBe null
        MiuiVersionDetector.parse("0") shouldBe null
    }
}
