package eu.darken.amply.charging.core.access.shizuku

import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.PermissionInfo
import dagger.hilt.android.qualifiers.ApplicationContext
import eu.darken.amply.common.debug.logging.Logging
import eu.darken.amply.common.debug.logging.log
import eu.darken.amply.common.debug.logging.logTag
import rikka.shizuku.ShizukuProvider
import javax.inject.Inject
import javax.inject.Singleton

/** Detects the installed Shizuku manager by the permission it declares, including renamed forks. */
@Singleton
class ShizukuInstallationDetector @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    @Suppress("DEPRECATION")
    fun managerPackage(): String? = resolveShizukuManagerPackage {
        context.packageManager.getPermissionInfo(ShizukuProvider.PERMISSION, 0)
    }.also { packageName ->
        log(TAG, Logging.Priority.VERBOSE) { "managerPackage=$packageName" }
    }

    private companion object {
        val TAG = logTag("Shizuku", "Installation")
    }
}

internal fun resolveShizukuManagerPackage(lookup: () -> PermissionInfo): String? = try {
    lookup().packageName?.takeUnless(String::isBlank)
} catch (_: PackageManager.NameNotFoundException) {
    null
} catch (_: Exception) {
    null
}
