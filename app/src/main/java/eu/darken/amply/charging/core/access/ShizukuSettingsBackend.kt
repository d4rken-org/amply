package eu.darken.amply.charging.core.access

import eu.darken.amply.R
import eu.darken.amply.charging.core.access.shizuku.ShizukuController
import eu.darken.amply.charging.core.BackendKind
import eu.darken.amply.common.ca.toCaString
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ShizukuSettingsBackend @Inject constructor(
    private val controller: ShizukuController,
) : AccessBackend {
    override val kind = BackendKind.SHIZUKU

    override suspend fun status(): BackendStatus {
        val managerPackage = controller.managerPackage()
        val installed = managerPackage != null
        val available = controller.isAvailable()
        val granted = available && controller.isGranted()
        return BackendStatus(
            available = available,
            granted = granted,
            installed = installed,
            detail = when {
                !installed -> R.string.access_shizuku_not_installed
                !available -> R.string.access_shizuku_not_running
                !granted -> R.string.access_shizuku_not_granted
                else -> R.string.access_shizuku_ready
            }.toCaString(),
        )
    }

    override suspend fun read(namespace: SettingNamespace, key: String): SettingRead = runCatching {
        SettingRead(
            readable = true,
            value = controller.service().readSetting(namespace.commandName, key),
        )
    }.getOrElse { SettingRead(false, error = (it.message ?: it.javaClass.simpleName).toCaString()) }

    override suspend fun write(mutation: SettingMutation): Boolean = runCatching {
        controller.service().writeSetting(
            mutation.namespace.commandName,
            mutation.key,
            mutation.value,
        )
    }.getOrDefault(false)

    override suspend fun snapshot(namespace: SettingNamespace): Map<String, String> = runCatching {
        controller.service().snapshotSettings(namespace.commandName)
            .lineSequence()
            .mapNotNull { line ->
                val index = line.indexOf('=')
                if (index <= 0) null else line.substring(0, index) to line.substring(index + 1)
            }
            .toMap()
    }.getOrDefault(emptyMap())
}
