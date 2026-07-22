package eu.darken.amply.charging.core.access

import eu.darken.amply.charging.core.BackendKind
import eu.darken.amply.common.ca.CaString

enum class SettingNamespace(val commandName: String) {
    SECURE("secure"),
    GLOBAL("global"),
    SYSTEM("system"),

    /**
     * LineageOS's private settings provider (`content://lineagesettings/system`), which is NOT reachable
     * via `/system/bin/settings`. [commandName] is a sentinel and is never passed to that CLI — the
     * backends branch on this value: reads go through [LineageSettingsClient] (unprivileged ContentResolver)
     * and writes through the dedicated `writeLineageSetting` AIDL op (`/system/bin/content`, Shizuku only).
     * Deliberately excluded from the contribution wizard's capture set ([STANDARD_SETTINGS_NAMESPACES]).
     */
    LINEAGE_SYSTEM("lineage_system"),
}

/** The three real AOSP `settings` namespaces the contribution wizard snapshots — excludes [SettingNamespace.LINEAGE_SYSTEM]. */
val STANDARD_SETTINGS_NAMESPACES: List<SettingNamespace> =
    listOf(SettingNamespace.SECURE, SettingNamespace.GLOBAL, SettingNamespace.SYSTEM)

data class SettingRead(
    val readable: Boolean,
    val value: String? = null,
    val error: CaString? = null,
)

data class SettingMutation(
    val namespace: SettingNamespace,
    val key: String,
    val value: String,
)

data class BackendStatus(
    val available: Boolean,
    val granted: Boolean,
    val detail: CaString,
    val installed: Boolean = available,
) {
    val ready: Boolean get() = available && granted
}

interface AccessBackend {
    val kind: BackendKind
    suspend fun status(): BackendStatus
    suspend fun read(namespace: SettingNamespace, key: String): SettingRead
    suspend fun write(mutation: SettingMutation): Boolean
}
