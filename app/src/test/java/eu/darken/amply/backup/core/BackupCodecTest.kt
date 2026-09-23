package eu.darken.amply.backup.core

import eu.darken.amply.alarm.core.ChargeAlarmConfig
import eu.darken.amply.backup.core.AmplyBackup.Companion.MAX_BACKUP_BYTES
import eu.darken.amply.common.serialization.SerializationModule
import eu.darken.amply.common.theming.ThemeColor
import eu.darken.amply.common.theming.ThemeMode
import eu.darken.amply.common.theming.ThemeState
import eu.darken.amply.common.theming.ThemeStyle
import eu.darken.amply.rules.core.ChargeRule
import eu.darken.amply.rules.core.PlugKind
import eu.darken.amply.rules.core.RuleCondition
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.InputStream

class BackupCodecTest {

    private val json = SerializationModule.json()
    private val codec = BackupCodec(json)

    private val btRule = ChargeRule(
        id = "r1",
        enabled = true,
        label = "Car",
        condition = RuleCondition.BluetoothDevice(address = "AA:BB:CC:DD:EE:FF", name = "Car kit"),
        policyId = "unrestricted",
    )

    private val chargerRule = ChargeRule(
        id = "r2",
        enabled = false,
        label = "Desk",
        condition = RuleCondition.ChargerType(types = setOf(PlugKind.USB, PlugKind.WIRELESS)),
        policyId = "fixed:80",
    )

    private val fullSettings = BackupSettings(
        theme = ThemeState(mode = ThemeMode.DARK, style = ThemeStyle.MATERIAL_YOU, color = ThemeColor.BLUE),
        chargeAlarm = ChargeAlarmConfig(enabled = true, targetPercent = 85),
        reconnectGestureEnabled = true,
        reconnectGestureAnyLevel = false,
        reconnectNotificationPolicies = listOf("fixed:80", "unrestricted"),
        historyRetentionDays = 7,
    )

    private val fullBackup = AmplyBackup(
        createdAt = 1_700_000_000_000L,
        appVersion = "0.4.0-beta1",
        settings = fullSettings,
        rules = listOf(btRule, chargerRule),
    )

    private val v1Fixture = """
        {
          "format": "eu.darken.amply.backup",
          "version": 1,
          "createdAt": 1700000000000,
          "appVersion": "0.4.0-beta1",
          "settings": {
            "theme": {"mode": "DARK", "style": "MATERIAL_YOU", "color": "BLUE"},
            "chargeAlarm": {"enabled": true, "targetPercent": 85},
            "reconnectGestureEnabled": true,
            "reconnectGestureAnyLevel": false,
            "reconnectNotificationPolicies": ["fixed:80", "unrestricted"],
            "historyRetentionDays": 7
          },
          "rules": [
            {
              "id": "r1",
              "enabled": true,
              "label": "Car",
              "condition": {"type": "bluetooth", "address": "AA:BB:CC:DD:EE:FF", "name": "Car kit"},
              "policyId": "unrestricted"
            },
            {
              "id": "r2",
              "enabled": false,
              "label": "Desk",
              "condition": {"type": "charger", "types": ["USB", "WIRELESS"]},
              "policyId": "fixed:80"
            }
          ]
        }
    """.trimIndent()

    private fun backupJson(
        version: String = "1",
        settings: String = "{}",
        rules: String? = null,
    ): String = buildString {
        append("""{"format": "eu.darken.amply.backup", "version": $version, "settings": $settings""")
        if (rules != null) append(""", "rules": $rules""")
        append("}")
    }

    private fun decodeText(text: String) = codec.decode(text.toByteArray())

    private fun decodeSuccess(text: String): BackupDecodeResult.Success =
        decodeText(text).shouldBeInstanceOf<BackupDecodeResult.Success>()

    private fun ruleJson(
        id: String = "a",
        policyId: String = "fixed:80",
        condition: String = """{"type": "charger", "types": ["AC"]}""",
    ) = """{"id": "$id", "condition": $condition, "policyId": "$policyId"}"""

    @Test
    fun `fully populated backup round-trips without skips`() {
        codec.decode(codec.encode(fullBackup).toByteArray()) shouldBe
            BackupDecodeResult.Success(fullBackup, skippedFields = emptySet(), skippedRules = 0)
    }

    @Test
    fun `encoded backup carries exactly the v1 keys`() {
        // Structural guard: nothing beyond these keys is ever exported.
        val root = json.parseToJsonElement(codec.encode(fullBackup)).jsonObject
        root.keys shouldBe setOf("format", "version", "createdAt", "appVersion", "settings", "rules")
        root.getValue("settings").jsonObject.keys shouldBe setOf(
            "theme",
            "chargeAlarm",
            "reconnectGestureEnabled",
            "reconnectGestureAnyLevel",
            "reconnectNotificationPolicies",
            "historyRetentionDays",
        )
    }

    @Test
    fun `hand-written v1 file decodes to the expected backup`() {
        decodeText(v1Fixture) shouldBe
            BackupDecodeResult.Success(fullBackup, skippedFields = emptySet(), skippedRules = 0)
    }

    @Test
    fun `current encoder writes the v1 fixture shape`() {
        json.parseToJsonElement(codec.encode(fullBackup)) shouldBe json.parseToJsonElement(v1Fixture)
    }

    @Test
    fun `default notification selection is written as an empty array`() {
        val backup = AmplyBackup(settings = BackupSettings(reconnectNotificationPolicies = emptyList()))
        val encoded = codec.encode(backup)

        val settings = json.parseToJsonElement(encoded).jsonObject.getValue("settings").jsonObject
        settings["reconnectNotificationPolicies"] shouldBe JsonArray(emptyList())
        decodeSuccess(encoded).backup.settings.reconnectNotificationPolicies shouldBe emptyList()
    }

    @Test
    fun `missing notification selection decodes to null`() {
        decodeSuccess(backupJson()).backup.settings.reconnectNotificationPolicies shouldBe null
    }

    @Test
    fun `null settings and rules are omitted on encode`() {
        val root = json.parseToJsonElement(codec.encode(AmplyBackup())).jsonObject

        root.getValue("settings") shouldBe JsonObject(emptyMap())
        root.containsKey("rules") shouldBe false
    }

    @Test
    fun `non-object json is not a backup`() {
        decodeText("""[1, 2, 3]""") shouldBe BackupDecodeResult.NotABackup
        decodeText("\"eu.darken.amply.backup\"") shouldBe BackupDecodeResult.NotABackup
    }

    @Test
    fun `garbage text is not a backup`() {
        decodeText("this is not json {") shouldBe BackupDecodeResult.NotABackup
        decodeText("") shouldBe BackupDecodeResult.NotABackup
    }

    @Test
    fun `wrong format is not a backup`() {
        decodeText("""{"format": "eu.darken.other", "version": 1}""") shouldBe BackupDecodeResult.NotABackup
        decodeText("""{"format": 1, "version": 1}""") shouldBe BackupDecodeResult.NotABackup
        decodeText("""{"version": 1}""") shouldBe BackupDecodeResult.NotABackup
    }

    @Test
    fun `missing or invalid version is not a backup`() {
        decodeText("""{"format": "eu.darken.amply.backup"}""") shouldBe BackupDecodeResult.NotABackup
        decodeText(backupJson(version = "0")) shouldBe BackupDecodeResult.NotABackup
        decodeText(backupJson(version = "\"1\"")) shouldBe BackupDecodeResult.NotABackup
        decodeText(backupJson(version = "1.5")) shouldBe BackupDecodeResult.NotABackup
    }

    @Test
    fun `malformed utf-8 is not a backup`() {
        val (prefix, suffix) = v1Fixture.split("0.4.0-beta1", limit = 2)
        val bytes = prefix.toByteArray() + byteArrayOf(0xC3.toByte(), 0x28) + suffix.toByteArray()

        codec.decode(bytes) shouldBe BackupDecodeResult.NotABackup
    }

    @Test
    fun `newer version is reported`() {
        decodeText(backupJson(version = "2")) shouldBe BackupDecodeResult.NewerVersion(2)
    }

    @Test
    fun `display-only fields fall back to defaults`() {
        val text = """{"format": "eu.darken.amply.backup", "version": 1, "createdAt": "x", "appVersion": 3}"""
        decodeSuccess(text) shouldBe BackupDecodeResult.Success(AmplyBackup(), emptySet(), 0)
    }

    @Test
    fun `missing or non-object settings leave every setting null without skips`() {
        decodeSuccess("""{"format": "eu.darken.amply.backup", "version": 1}""") shouldBe
            BackupDecodeResult.Success(AmplyBackup(), emptySet(), 0)
        decodeSuccess(backupJson(settings = "\"x\"")) shouldBe
            BackupDecodeResult.Success(AmplyBackup(), emptySet(), 0)
    }

    @Test
    fun `read bounded returns the bytes at exactly the limit`() {
        val bytes = ByteArray(16) { it.toByte() }
        readBounded(ByteArrayInputStream(bytes), maxBytes = 16) shouldBe bytes
    }

    @Test
    fun `read bounded returns null one byte past the limit`() {
        readBounded(ByteArrayInputStream(ByteArray(17)), maxBytes = 16) shouldBe null
    }

    @Test
    fun `read bounded stops reading an endless stream`() {
        var served = 0L
        val endless = object : InputStream() {
            override fun read(): Int {
                served++
                return 'a'.code
            }
        }

        readBounded(endless, maxBytes = 16) shouldBe null
        served shouldBe 17L
    }

    @Test
    fun `oversized input is too large`() {
        codec.read(ByteArrayInputStream(ByteArray(MAX_BACKUP_BYTES + 1))) shouldBe BackupDecodeResult.TooLarge
    }

    @Test
    fun `read decodes a file within the limit`() {
        codec.read(ByteArrayInputStream(v1Fixture.toByteArray())) shouldBe
            BackupDecodeResult.Success(fullBackup, emptySet(), 0)
    }

    @Test
    fun `one unreadable setting is skipped while the others survive`() {
        val settings = """
            {
              "theme": {"mode": "DARK", "style": "MATERIAL_YOU", "color": "BLUE"},
              "chargeAlarm": "x",
              "reconnectGestureEnabled": true,
              "reconnectGestureAnyLevel": false,
              "reconnectNotificationPolicies": ["fixed:80", "unrestricted"],
              "historyRetentionDays": 7
            }
        """.trimIndent()

        val result = decodeSuccess(backupJson(settings = settings))

        result.skippedFields shouldBe setOf("chargeAlarm")
        result.backup.settings shouldBe fullSettings.copy(chargeAlarm = null)
    }

    @Test
    fun `every wrongly typed setting is skipped by its wire name`() {
        val settings = """
            {
              "theme": {"mode": "NEON"},
              "chargeAlarm": [],
              "reconnectGestureEnabled": "true",
              "reconnectGestureAnyLevel": 1,
              "reconnectNotificationPolicies": "fixed:80",
              "historyRetentionDays": "7"
            }
        """.trimIndent()

        val result = decodeSuccess(backupJson(settings = settings))

        result.backup.settings shouldBe BackupSettings()
        result.skippedFields shouldBe setOf(
            "theme",
            "chargeAlarm",
            "reconnectGestureEnabled",
            "reconnectGestureAnyLevel",
            "reconnectNotificationPolicies",
            "historyRetentionDays",
        )
    }

    @Test
    fun `rules that are not an array are skipped`() {
        val result = decodeSuccess(backupJson(rules = """{"id": "a"}"""))

        result.backup.rules shouldBe null
        result.skippedFields shouldBe setOf("rules")
    }

    @Test
    fun `missing rules decode to null and an empty array to an empty list`() {
        decodeSuccess(backupJson()).backup.rules shouldBe null
        decodeSuccess(backupJson(rules = "[]")).backup.rules shouldBe emptyList()
    }

    @Test
    fun `invalid rules are dropped and counted while valid neighbours keep their order`() {
        val rules = listOf(
            ruleJson(id = "a"),
            ruleJson(id = "  "),
            ruleJson(id = "b", policyId = "adaptive"),
            ruleJson(id = "blank-policy", policyId = " "),
            ruleJson(id = "blank-address", condition = """{"type": "bluetooth", "address": "   "}"""),
            ruleJson(id = "no-types", condition = """{"type": "charger", "types": []}"""),
            ruleJson(id = "a", policyId = "unrestricted"),
            "42",
            """{"id": "no-condition", "policyId": "fixed:80"}""",
            ruleJson(id = "unknown-condition", condition = """{"type": "wifi"}"""),
            ruleJson(id = "c", policyId = "unrestricted"),
        ).joinToString(prefix = "[", postfix = "]")

        val result = decodeSuccess(backupJson(rules = rules))

        result.backup.rules!!.map { it.id } shouldBe listOf("a", "b", "c")
        result.backup.rules!!.first().policyId shouldBe "fixed:80"
        result.skippedRules shouldBe 8
        result.skippedFields shouldBe emptySet()
    }

    @Test
    fun `unparseable non-blank policy id is kept`() {
        val rules = listOf(
            ruleJson(id = "a", policyId = "fixed:999"),
            ruleJson(id = "b", policyId = "future_mode"),
        ).joinToString(prefix = "[", postfix = "]")

        val result = decodeSuccess(backupJson(rules = rules))

        result.backup.rules!!.map { it.policyId } shouldBe listOf("fixed:999", "future_mode")
        result.skippedRules shouldBe 0
    }

    @Test
    fun `bluetooth address is normalized`() {
        val rules = "[" + ruleJson(condition = """{"type": "bluetooth", "address": " aa:bb:cc:dd:ee:ff "}""") + "]"

        val rule = decodeSuccess(backupJson(rules = rules)).backup.rules!!.single()

        rule.condition shouldBe RuleCondition.BluetoothDevice(address = "AA:BB:CC:DD:EE:FF")
    }

    @Test
    fun `notification selection with a non-string id is skipped`() {
        val settings = """{"reconnectNotificationPolicies": ["fixed:80", 42]}"""

        val result = decodeSuccess(backupJson(settings = settings))

        result.backup.settings.reconnectNotificationPolicies shouldBe null
        result.skippedFields shouldBe setOf("reconnectNotificationPolicies")
    }

    @Test
    fun `notification selection with a blank id is skipped`() {
        val settings = """{"reconnectNotificationPolicies": ["fixed:80", "  "]}"""

        val result = decodeSuccess(backupJson(settings = settings))

        result.backup.settings.reconnectNotificationPolicies shouldBe null
        result.skippedFields shouldBe setOf("reconnectNotificationPolicies")
    }

    @Test
    fun `notification selection keeps unparseable ids verbatim`() {
        val settings = """{"reconnectNotificationPolicies": ["fixed:80", "future_mode"]}"""

        val result = decodeSuccess(backupJson(settings = settings))

        result.backup.settings.reconnectNotificationPolicies shouldBe listOf("fixed:80", "future_mode")
        result.skippedFields shouldBe emptySet()
    }

    @Test
    fun `empty notification selection is kept without a skip`() {
        val result = decodeSuccess(backupJson(settings = """{"reconnectNotificationPolicies": []}"""))

        result.backup.settings.reconnectNotificationPolicies shouldBe emptyList()
        result.skippedFields shouldBe emptySet()
    }

    @Test
    fun `malformed notification selection is skipped instead of resetting the default`() {
        val settings = """{"reconnectNotificationPolicies": [42, null], "historyRetentionDays": 7}"""

        val success = decodeSuccess(backupJson(settings = settings))

        success.backup.settings.reconnectNotificationPolicies shouldBe null
        success.skippedFields shouldContain "reconnectNotificationPolicies"
        success.backup.settings.historyRetentionDays shouldBe 7
    }
}
