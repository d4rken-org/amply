package eu.darken.amply.common.datastore

import androidx.datastore.preferences.core.stringPreferencesKey
import eu.darken.amply.common.AppDataStore
import eu.darken.amply.common.debug.logging.Logging
import eu.darken.amply.common.debug.logging.log
import eu.darken.amply.common.debug.logging.logTag
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer
import kotlin.reflect.typeOf

/**
 * JSON-backed [DataStoreValue] reader. Used for settings that are read and written as a *unit* — a
 * charge session and its provenance, the interruption event — so one key holds the whole record and
 * a partially-written state cannot exist.
 *
 * [fallbackToDefault] decides what a corrupt record means, and the choice is per-record, not global:
 * where the current decode is already all-or-nothing (an unparseable session is no session) falling
 * back is right, but a record whose fields degrade *independently* must validate field by field
 * instead — otherwise one bad field silently resets the others to defaults.
 *
 * Ported from SD Maid SE's `DataStoreValueKotlinx`.
 */
inline fun <reified T> kotlinxReader(
    json: Json,
    defaultValue: T,
    fallbackToDefault: Boolean = false,
): (Any?) -> T {
    @Suppress("UNCHECKED_CAST")
    val serializer = json.serializersModule.serializer(typeOf<T>()) as KSerializer<T>
    return { rawValue ->
        // `as?`, not `as`: a key name reused for a non-String type would otherwise throw a
        // ClassCastException that the catch below does not cover, permanently killing the flow.
        val stored = rawValue as? String
        if (stored == null) {
            defaultValue
        } else if (fallbackToDefault) {
            try {
                json.decodeFromString(serializer, stored) ?: defaultValue
            } catch (e: SerializationException) {
                log(KOTLINX_TAG, Logging.Priority.ERROR) { "Failed to parse JSON, using default: ${e.message}" }
                defaultValue
            } catch (e: IllegalArgumentException) {
                log(KOTLINX_TAG, Logging.Priority.ERROR) { "Failed to read JSON, using default: ${e.message}" }
                defaultValue
            }
        } else {
            json.decodeFromString(serializer, stored)
        }
    }
}

inline fun <reified T> kotlinxWriter(json: Json): (T) -> Any? {
    @Suppress("UNCHECKED_CAST")
    val serializer = json.serializersModule.serializer(typeOf<T>()) as KSerializer<T>
    return { newValue: T -> newValue?.let { json.encodeToString(serializer, it) } }
}

inline fun <reified T : Any?> AppDataStore.createValue(
    key: String,
    defaultValue: T = null as T,
    json: Json,
    fallbackToDefault: Boolean = false,
) = createValue(
    key = stringPreferencesKey(key),
    reader = kotlinxReader(json, defaultValue, fallbackToDefault),
    writer = kotlinxWriter(json),
)

val KOTLINX_TAG = logTag("DataStore", "Value", "Kotlinx")
