package eu.darken.amply.charging.core.access

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import eu.darken.amply.R
import eu.darken.amply.charging.core.BackendKind
import eu.darken.amply.common.ca.toCaString
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DirectSettingsBackend @Inject constructor(
    @ApplicationContext private val context: Context,
) : AccessBackend {

    override val kind = BackendKind.DIRECT_WSS

    override suspend fun status(): BackendStatus {
        val granted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.WRITE_SECURE_SETTINGS,
        ) == PackageManager.PERMISSION_GRANTED
        return BackendStatus(
            available = true,
            granted = granted,
            detail = (if (granted) R.string.access_wss_granted else R.string.access_wss_not_granted).toCaString(),
        )
    }

    override suspend fun read(namespace: SettingNamespace, key: String): SettingRead {
        // Lineage reads never go through a settings backend — the adapter reads a consistent snapshot via
        // LineageChargeReader (unprivileged ContentResolver). Guard defensively.
        if (namespace == SettingNamespace.LINEAGE_SYSTEM) {
            return SettingRead(readable = false, error = R.string.charging_reason_settings_unreadable.toCaString())
        }
        return readAosp(namespace, key)
    }

    private fun readAosp(namespace: SettingNamespace, key: String): SettingRead = runCatching {
        val value = when (namespace) {
            SettingNamespace.SECURE -> Settings.Secure.getString(context.contentResolver, key)
            SettingNamespace.GLOBAL -> Settings.Global.getString(context.contentResolver, key)
            SettingNamespace.SYSTEM -> Settings.System.getString(context.contentResolver, key)
            SettingNamespace.LINEAGE_SYSTEM -> error("Lineage reads are handled by LineageSettingsClient")
        }
        SettingRead(readable = true, value = value)
    }.getOrElse {
        SettingRead(
            readable = false,
            error = when (it) {
                is SecurityException -> R.string.access_read_blocked.toCaString()
                else -> (it.message ?: it.javaClass.simpleName).toCaString()
            },
        )
    }

    override suspend fun write(mutation: SettingMutation): Boolean {
        // WRITE_SECURE_SETTINGS cannot write the Lineage provider (it needs lineageos.permission.WRITE_SETTINGS,
        // held only by the shell UID) — fail honestly so the adapter falls back to Shizuku.
        if (mutation.namespace == SettingNamespace.LINEAGE_SYSTEM) return false
        if (!status().ready) return false
        return runCatching {
            when (mutation.namespace) {
                SettingNamespace.SECURE -> Settings.Secure.putString(
                    context.contentResolver,
                    mutation.key,
                    mutation.value,
                )
                SettingNamespace.GLOBAL -> Settings.Global.putString(
                    context.contentResolver,
                    mutation.key,
                    mutation.value,
                )
                SettingNamespace.SYSTEM -> Settings.System.putString(
                    context.contentResolver,
                    mutation.key,
                    mutation.value,
                )
                SettingNamespace.LINEAGE_SYSTEM -> false // unreachable: early-returned above
            }
        }.getOrDefault(false)
    }
}
