package eu.darken.amply.charging.core.access

import eu.darken.amply.charging.core.BackendKind

enum class SettingNamespace(val commandName: String) {
    SECURE("secure"),
    GLOBAL("global"),
    SYSTEM("system"),
}

data class SettingRead(
    val readable: Boolean,
    val value: String? = null,
    val error: String? = null,
)

data class SettingMutation(
    val namespace: SettingNamespace,
    val key: String,
    val value: String,
)

data class BackendStatus(
    val available: Boolean,
    val granted: Boolean,
    val detail: String,
    val installed: Boolean = available,
) {
    val ready: Boolean get() = available && granted
}

interface AccessBackend {
    val kind: BackendKind
    suspend fun status(): BackendStatus
    suspend fun read(namespace: SettingNamespace, key: String): SettingRead
    suspend fun write(mutation: SettingMutation): Boolean
    suspend fun snapshot(namespace: SettingNamespace): Map<String, String>
}
