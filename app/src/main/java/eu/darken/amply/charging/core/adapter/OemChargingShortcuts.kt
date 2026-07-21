package eu.darken.amply.charging.core.adapter

import android.content.Context
import android.content.Intent
import eu.darken.amply.charging.core.DeviceInfo

/**
 * Resolves a manufacturer's native battery-protection screen for the contribution wizard, independent of which
 * adapter is selected. An unsupported device selects a lab adapter whose own `nativeSettingsIntent` is only the
 * generic battery-saver screen, so the wizard — which targets exactly those devices — can't rely on it.
 *
 * Read-only: the candidate is resolved against the package manager and null is returned when absent, so the caller
 * can fall back to manual navigation guidance instead of launching an intent that goes nowhere.
 */
object OemChargingShortcuts {
    // Samsung's exported battery-protection activity action (docs/SAMSUNG_SPIKE_RESULTS.md).
    private const val SAMSUNG_ACTION = "com.samsung.android.sm.ACTION_BATTERY_PROTECTION"

    fun resolve(context: Context, device: DeviceInfo): Intent? {
        val candidate = when {
            device.manufacturer.equals("Samsung", ignoreCase = true) -> Intent(SAMSUNG_ACTION)
            else -> null
        } ?: return null
        candidate.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return candidate.takeIf { it.resolveActivity(context.packageManager) != null }
    }
}
