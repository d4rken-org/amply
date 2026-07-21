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
    fun `rejects a non-blank line without a separator`() {
        assertThrows<IllegalArgumentException> { parseSettingsList("a=1\ngarbage\n") }
    }

    @Test
    fun `rejects an empty key`() {
        assertThrows<IllegalArgumentException> { parseSettingsList("=1") }
    }

    @Test
    fun `rejects duplicate keys`() {
        assertThrows<IllegalArgumentException> { parseSettingsList("a=1\na=2") }
    }
}
