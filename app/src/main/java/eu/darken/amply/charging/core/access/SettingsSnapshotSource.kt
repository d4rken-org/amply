package eu.darken.amply.charging.core.access

import eu.darken.amply.common.ca.CaString

/**
 * Narrow, read-only view over the settings backend for the contribution wizard's discovery flow.
 *
 * Deliberately exposes only status + full-namespace snapshot — never [AccessBackend.write]. The wizard
 * depends on this type, not the concrete backend, so the read-only guarantee is enforced at compile time
 * (see the wizard rules in `.claude/rules/privileged-access.md`).
 */
interface SettingsSnapshotSource {
    suspend fun status(): BackendStatus

    /** Full dump of one namespace, or a typed failure. Never silently coerces a failure to an empty map. */
    suspend fun snapshot(namespace: SettingNamespace): NamespaceSnapshot
}

/**
 * Result of snapshotting a single settings namespace. A [Failure] must never be treated as "every key
 * deleted" — the wizard aborts the whole capture round unless all requested namespaces report [Success].
 */
sealed interface NamespaceSnapshot {
    data class Success(val values: Map<String, String>) : NamespaceSnapshot
    data class Failure(val reason: CaString) : NamespaceSnapshot
}

/**
 * Parses raw `settings list <namespace>` output into a key→value map.
 *
 * Strict on purpose: a blank line (e.g. the trailing newline) is skipped, but any non-blank line without a
 * `key=value` shape, or a duplicate key, throws. Silent dropping (the old `mapNotNull`) could make a key that
 * failed to parse look deleted in the next round's diff. Callers convert the throw into [NamespaceSnapshot.Failure].
 */
internal fun parseSettingsList(raw: String): Map<String, String> {
    val result = LinkedHashMap<String, String>()
    raw.lineSequence().forEach { line ->
        if (line.isEmpty()) return@forEach
        val separator = line.indexOf('=')
        require(separator > 0) { "Malformed settings line" }
        val key = line.substring(0, separator)
        val value = line.substring(separator + 1)
        require(result.put(key, value) == null) { "Duplicate settings key" }
    }
    return result
}
