package eu.darken.amply.charging.core.access

import io.kotest.matchers.maps.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class SettingsListParseTest {

    @Test
    fun `parses key=value lines and skips the trailing blank`() {
        parseSettingsList("a=1\nb=2\n") shouldContainExactly mapOf("a" to "1", "b" to "2")
    }

    @Test
    fun `a value may contain equals signs`() {
        parseSettingsList("k=a=b=c") shouldBe mapOf("k" to "a=b=c")
    }

    @Test
    fun `an empty value is preserved`() {
        parseSettingsList("k=") shouldBe mapOf("k" to "")
    }

    @Test
    fun `a multi-line value's continuation lines are appended, not treated as new entries`() {
        // Real devices carry multi-line JSON values (e.g. a search-engine config in secure).
        val raw = "a=1\n" +
            "json={\n" +
            "  \"name\": \"Google\",\n" +
            "  \"keyword\": \"google.com\"\n" +
            "}\n" +
            "b=2\n"
        parseSettingsList(raw) shouldBe mapOf(
            "a" to "1",
            "json" to "{\n  \"name\": \"Google\",\n  \"keyword\": \"google.com\"\n}",
            "b" to "2",
        )
    }

    @Test
    fun `a leading orphan line without a key is ignored`() {
        parseSettingsList("=1\na=2") shouldBe mapOf("a" to "2")
    }

    @Test
    fun `a duplicate key keeps the last value`() {
        parseSettingsList("a=1\na=2") shouldBe mapOf("a" to "2")
    }
}
