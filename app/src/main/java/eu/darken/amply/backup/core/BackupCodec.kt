package eu.darken.amply.backup.core

import eu.darken.amply.alarm.core.ChargeAlarmConfig
import eu.darken.amply.backup.core.AmplyBackup.Companion.CURRENT_VERSION
import eu.darken.amply.backup.core.AmplyBackup.Companion.FORMAT
import eu.darken.amply.backup.core.AmplyBackup.Companion.MAX_BACKUP_BYTES
import eu.darken.amply.common.theming.ThemeState
import eu.darken.amply.rules.core.ChargeRule
import eu.darken.amply.rules.core.RuleCondition
import eu.darken.amply.rules.core.normalizeBtAddress
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import javax.inject.Inject

sealed interface BackupDecodeResult {
    data class Success(
        val backup: AmplyBackup,
        /** Wire names of settings present but unreadable; `"rules"` if the rules key is not an array. */
        val skippedFields: Set<String>,
        /** Rules dropped by decode failure, semantic check, or duplicate id. */
        val skippedRules: Int,
    ) : BackupDecodeResult

    data object NotABackup : BackupDecodeResult

    data class NewerVersion(val version: Int) : BackupDecodeResult

    data object TooLarge : BackupDecodeResult
}

/**
 * Decoding is field by field rather than one typed `decodeFromString`: a typed decode fails on the
 * first bad field, so one unreadable setting would lose every other setting and all rules with it.
 * An explicit JSON `null` is treated like an absent key.
 */
class BackupCodec @Inject constructor(private val json: Json) {

    fun encode(backup: AmplyBackup): String = json.encodeToString(AmplyBackup.serializer(), backup)

    fun read(input: InputStream): BackupDecodeResult {
        val bytes = readBounded(input, MAX_BACKUP_BYTES) ?: return BackupDecodeResult.TooLarge
        return decode(bytes)
    }

    fun decode(bytes: ByteArray): BackupDecodeResult {
        val text = decodeStrictUtf8(bytes) ?: return BackupDecodeResult.NotABackup
        val root = runCatching { json.parseToJsonElement(text) }.getOrNull() as? JsonObject
            ?: return BackupDecodeResult.NotABackup

        if (root.stringOrNull("format") != FORMAT) return BackupDecodeResult.NotABackup
        val version = root.intOrNull("version")
        if (version == null || version < 1) return BackupDecodeResult.NotABackup
        if (version > CURRENT_VERSION) return BackupDecodeResult.NewerVersion(version)

        val skippedFields = mutableSetOf<String>()
        val settings = decodeSettings(root.valueOrNull("settings") as? JsonObject, skippedFields)
        var skippedRules = 0
        val rules = when (val raw = root.valueOrNull("rules")) {
            null -> null
            is JsonArray -> decodeRules(raw).also { skippedRules = it.second }.first
            else -> {
                skippedFields += "rules"
                null
            }
        }

        return BackupDecodeResult.Success(
            backup = AmplyBackup(
                version = version,
                createdAt = root.longOrNull("createdAt") ?: 0L,
                appVersion = root.stringOrNull("appVersion") ?: "",
                settings = settings,
                rules = rules,
            ),
            skippedFields = skippedFields,
            skippedRules = skippedRules,
        )
    }

    private fun decodeSettings(obj: JsonObject?, skipped: MutableSet<String>): BackupSettings {
        if (obj == null) return BackupSettings()

        fun <T : Any> field(name: String, read: (JsonElement) -> T?): T? {
            val raw = obj.valueOrNull(name) ?: return null
            return runCatching { read(raw) }.getOrNull().also { if (it == null) skipped += name }
        }

        return BackupSettings(
            theme = field("theme") { json.decodeFromJsonElement(ThemeState.serializer(), it) },
            chargeAlarm = field("chargeAlarm") { json.decodeFromJsonElement(ChargeAlarmConfig.serializer(), it) },
            reconnectGestureEnabled = field("reconnectGestureEnabled") { it.booleanOrNull() },
            reconnectGestureAnyLevel = field("reconnectGestureAnyLevel") { it.booleanOrNull() },
            reconnectNotificationPolicies = field("reconnectNotificationPolicies") { element ->
                (element as? JsonArray)?.map { id ->
                    (id as? JsonPrimitive)?.takeIf { it.isString && it.content.isNotBlank() }?.content
                        ?: return@field null
                }
            },
            historyRetentionDays = field("historyRetentionDays") { it.intOrNull() },
        )
    }

    /** Returns the kept rules and how many were dropped. */
    private fun decodeRules(array: JsonArray): Pair<List<ChargeRule>, Int> {
        val kept = mutableListOf<ChargeRule>()
        val seenIds = mutableSetOf<String>()
        var dropped = 0
        for (element in array) {
            val rule = decodeRuleOrNull(element)
            if (rule == null || !seenIds.add(rule.id)) {
                dropped++
            } else {
                kept += rule
            }
        }
        return kept to dropped
    }

    /**
     * A non-blank [ChargeRule.policyId] this build cannot parse is kept, so a newer build's policy
     * survives a round trip through an older one; [countUnsupportedRules] reports it instead.
     */
    private fun decodeRuleOrNull(element: JsonElement): ChargeRule? {
        val decoded = runCatching { json.decodeFromJsonElement(ChargeRule.serializer(), element) }.getOrNull()
            ?: return null
        val rule = when (val condition = decoded.condition) {
            is RuleCondition.BluetoothDevice -> decoded.copy(
                condition = condition.copy(address = normalizeBtAddress(condition.address)),
            )
            is RuleCondition.ChargerType -> decoded
        }
        val conditionValid = when (val condition = rule.condition) {
            is RuleCondition.BluetoothDevice -> condition.address.isNotBlank()
            is RuleCondition.ChargerType -> condition.types.isNotEmpty()
        }
        return rule.takeIf { conditionValid && it.id.isNotBlank() && it.policyId.isNotBlank() }
    }

    private fun decodeStrictUtf8(bytes: ByteArray): String? = try {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    } catch (_: CharacterCodingException) {
        null
    }
}

/**
 * Reads at most [maxBytes] + 1 bytes and returns null if more than [maxBytes] were available. The
 * file comes from a user-picked URI, so its size is unknown and untrusted; it is never read whole.
 */
internal fun readBounded(input: InputStream, maxBytes: Int): ByteArray? {
    require(maxBytes >= 0) { "maxBytes must not be negative" }
    val limit = maxBytes.toLong() + 1
    val out = ByteArrayOutputStream()
    val buffer = ByteArray(8 * 1024)
    var total = 0L
    while (total < limit) {
        val count = input.read(buffer, 0, minOf(buffer.size.toLong(), limit - total).toInt())
        if (count < 0) break
        out.write(buffer, 0, count)
        total += count
    }
    return if (total > maxBytes) null else out.toByteArray()
}

private fun JsonObject.valueOrNull(name: String): JsonElement? = this[name]?.takeUnless { it is JsonNull }

private fun JsonElement.nonStringPrimitiveOrNull(): JsonPrimitive? =
    (this as? JsonPrimitive)?.takeUnless { it is JsonNull || it.isString }

private fun JsonElement.booleanOrNull(): Boolean? = nonStringPrimitiveOrNull()?.content?.toBooleanStrictOrNull()

private fun JsonElement.intOrNull(): Int? = nonStringPrimitiveOrNull()?.content?.toIntOrNull()

private fun JsonObject.stringOrNull(name: String): String? =
    (valueOrNull(name) as? JsonPrimitive)?.takeIf { it.isString }?.content

private fun JsonObject.intOrNull(name: String): Int? = valueOrNull(name)?.intOrNull()

private fun JsonObject.longOrNull(name: String): Long? =
    valueOrNull(name)?.nonStringPrimitiveOrNull()?.content?.toLongOrNull()
