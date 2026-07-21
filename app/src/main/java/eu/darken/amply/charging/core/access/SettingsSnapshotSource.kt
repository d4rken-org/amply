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
 * A setting is `key=value` per line, but a value can itself contain newlines — real devices carry multi-line JSON
 * blobs (e.g. a search-engine config in `secure`), so `settings list` emits continuation lines that do NOT start
 * with `key=`. A line is treated as a NEW entry only when it starts with a valid settings key followed by `=`;
 * anything else is appended to the previous value as a continuation. This is deterministic, so the same multi-line
 * value parses identically every round and never produces a spurious diff — and it never aborts a capture over a
 * value shape the OEM happens to use. A genuine command failure still surfaces as [NamespaceSnapshot.Failure] at
 * the caller (this function only runs on a successful dump).
 */
internal fun parseSettingsList(raw: String): Map<String, String> {
    val result = LinkedHashMap<String, String>()
    var currentKey: String? = null
    raw.trimEnd('\n').split('\n').forEach { line ->
        val separator = line.indexOf('=')
        val startsNewEntry = separator > 0 && SETTINGS_KEY.matches(line.substring(0, separator))
        if (startsNewEntry) {
            currentKey = line.substring(0, separator)
            result[currentKey] = line.substring(separator + 1)
        } else {
            // Continuation of the previous multi-line value; a leading orphan line (no key yet) is ignored.
            currentKey?.let { result[it] = result.getValue(it) + "\n" + line }
        }
    }
    return result
}

private val SETTINGS_KEY = Regex("[A-Za-z0-9_.:-]+")
