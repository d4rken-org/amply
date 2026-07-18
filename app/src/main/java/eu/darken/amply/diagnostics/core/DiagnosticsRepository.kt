package eu.darken.amply.diagnostics.core

import android.os.Build
import eu.darken.amply.charging.core.access.SettingNamespace
import eu.darken.amply.charging.core.access.ShizukuSettingsBackend
import eu.darken.amply.charging.core.adapter.AdapterRegistry
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DiagnosticsRepository @Inject constructor(
    private val shizuku: ShizukuSettingsBackend,
    private val registry: AdapterRegistry,
) {
    private var baseline: Map<String, String>? = null

    suspend fun captureBaseline(): Int {
        check(shizuku.status().ready) { "Shizuku is required for setting discovery" }
        baseline = capture()
        return baseline!!.size
    }

    suspend fun compare(): String {
        check(shizuku.status().ready) { "Shizuku is required for setting discovery" }
        val before = checkNotNull(baseline) { "Capture a baseline first" }
        val after = capture()
        val keys = (before.keys + after.keys).toSortedSet()
        val changes = keys.mapNotNull { key ->
            val old = before[key]
            val new = after[key]
            if (old == new) null else Triple(key, redact(key, old), redact(key, new))
        }
        return buildString {
            appendLine("Amply charging-settings discovery report")
            appendLine("created=${Instant.now()}")
            appendLine("manufacturer=${Build.MANUFACTURER}")
            appendLine("model=${Build.MODEL}")
            appendLine("sdk=${Build.VERSION.SDK_INT}")
            appendLine("fingerprint=${Build.FINGERPRINT}")
            appendLine("adapter=${registry.select().adapter?.id ?: "none"}")
            appendLine("changed_keys=${changes.size}")
            changes.forEach { (key, old, new) ->
                appendLine("$key: ${old ?: "<missing>"} -> ${new ?: "<missing>"}")
            }
        }
    }

    private suspend fun capture(): Map<String, String> = buildMap {
        SettingNamespace.entries.forEach { namespace ->
            shizuku.snapshot(namespace).forEach { (key, value) ->
                put("${namespace.commandName}/$key", value)
            }
        }
    }

    private fun redact(key: String, value: String?): String? {
        if (value == null) return null
        val sensitive = SENSITIVE_PARTS.any { key.contains(it, ignoreCase = true) }
        return if (sensitive) "<redacted>" else value.take(200)
    }

    companion object {
        private val SENSITIVE_PARTS = listOf(
            "android_id", "account", "device_name", "bluetooth_name", "location", "email",
            "phone", "ssid", "address", "token", "secret", "password", "history",
        )
    }
}
