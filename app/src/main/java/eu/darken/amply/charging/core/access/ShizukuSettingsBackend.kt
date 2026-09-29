package eu.darken.amply.charging.core.access

import eu.darken.amply.R
import eu.darken.amply.charging.core.access.shizuku.PrivilegedManager
import eu.darken.amply.charging.core.access.shizuku.ShizukuController
import eu.darken.amply.charging.core.BackendKind
import eu.darken.amply.common.ca.toCaString
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/** The privileged backend, served through the Porter SDK by either the Porter or the Shizuku manager. */
@Singleton
class ShizukuSettingsBackend @Inject constructor(
    private val controller: ShizukuController,
) : AccessBackend, SettingsSnapshotSource {
    override val kind = BackendKind.SHIZUKU

    override suspend fun status(): BackendStatus {
        val manager = controller.manager()
        val connected = manager is PrivilegedManager.Installed && manager.connected
        val available = connected && controller.isAvailable()
        val granted = available && controller.isGranted()
        return shizukuBackendStatus(manager, available, granted)
    }

    override suspend fun read(namespace: SettingNamespace, key: String): SettingRead {
        // Lineage reads never go through a settings backend — the adapter reads a consistent snapshot via
        // LineageChargeReader (unprivileged ContentResolver). Guard defensively.
        if (namespace == SettingNamespace.LINEAGE_SYSTEM) {
            return SettingRead(readable = false, error = R.string.charging_reason_settings_unreadable.toCaString())
        }
        // Blocking Binder transaction: keep off the caller's (often Main) thread to avoid an ANR.
        return withContext(Dispatchers.IO) {
            try {
                SettingRead(readable = true, value = controller.service().readSetting(namespace.commandName, key))
            } catch (e: CancellationException) {
                // Binding the user service (controller.service()) can suspend up to ~15s; a cancelled read
                // must propagate rather than be reported as an unreadable setting — matching snapshot().
                throw e
            } catch (e: Exception) {
                SettingRead(false, error = (e.message ?: e.javaClass.simpleName).toCaString())
            }
        }
    }

    override suspend fun write(mutation: SettingMutation): Boolean = withContext(Dispatchers.IO) {
        try {
            if (mutation.namespace == SettingNamespace.LINEAGE_SYSTEM) {
                controller.service().writeLineageSetting(mutation.key, mutation.value)
            } else {
                controller.service().writeSetting(mutation.namespace.commandName, mutation.key, mutation.value)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            false
        }
    }

    override suspend fun snapshot(namespace: SettingNamespace): NamespaceSnapshot = withContext(Dispatchers.IO) {
        // Blocking Binder transaction (a full `settings list` can take ~10s) — keep it off the caller's thread.
        try {
            val raw = controller.service().snapshotSettings(namespace.commandName)
            NamespaceSnapshot.Success(parseSettingsList(raw))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // A failed namespace must surface as a typed failure, never an empty map — an empty map would make
            // the next diff claim every prior key was deleted.
            NamespaceSnapshot.Failure((e.message ?: e.javaClass.simpleName).toCaString())
        }
    }

    override suspend fun lineageHealth(): LineageHealthSummary? = withContext(Dispatchers.IO) {
        // Blocking Binder transaction. Best-effort by design: this only enriches a contribution report, so any
        // failure (no Shizuku, not LineageOS, service absent) degrades to "unknown" rather than surfacing an error.
        try {
            parseLineageHealthSummary(controller.service().dumpLineageChargingControl())
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }
}

/** Only a connected, answering manager can be ready; an incompatible or unreachable one never is. */
internal fun shizukuBackendStatus(
    manager: PrivilegedManager,
    available: Boolean,
    granted: Boolean,
): BackendStatus = when (manager) {
    PrivilegedManager.NotInstalled -> BackendStatus(
        available = false,
        granted = false,
        installed = false,
        detail = R.string.access_shizuku_not_installed.toCaString(),
    )
    PrivilegedManager.Unreachable -> BackendStatus(
        available = false,
        granted = false,
        installed = true,
        detail = R.string.access_shizuku_not_running.toCaString(),
    )
    is PrivilegedManager.Installed -> {
        val live = manager.connected && available
        BackendStatus(
            available = live,
            granted = live && granted,
            installed = true,
            detail = when {
                !live -> R.string.access_shizuku_not_running
                !granted -> R.string.access_shizuku_not_granted
                else -> R.string.access_shizuku_ready
            }.toCaString(),
        )
    }
    is PrivilegedManager.Incompatible -> BackendStatus(
        available = false,
        granted = false,
        installed = true,
        detail = when {
            manager.serverTooOld -> R.string.access_manager_server_too_old
            else -> R.string.access_manager_client_too_old
        }.toCaString(manager.backend.label),
    )
}
