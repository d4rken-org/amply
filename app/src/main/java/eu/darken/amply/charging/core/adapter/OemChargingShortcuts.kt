package eu.darken.amply.charging.core.adapter

import android.content.Context
import android.content.Intent
import android.provider.Settings
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
    // Samsung's exported battery-protection activity action, from the Samsung qualification run
    // (.claude/skills/device-qualification).
    private const val SAMSUNG_ACTION = "com.samsung.android.sm.ACTION_BATTERY_PROTECTION"

    fun resolve(context: Context, device: DeviceInfo): Intent? {
        val candidate = when {
            device.manufacturer.equals("Samsung", ignoreCase = true) -> Intent(SAMSUNG_ACTION)
            else -> null
        } ?: return null
        candidate.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return candidate.takeIf { it.resolveActivity(context.packageManager) != null }
    }

    /**
     * The generic chain every adapter falls back to: the system battery-usage screen, which on most OEM skins is
     * the entry point that also holds the built-in charge-protection toggle, then Battery Saver where it isn't
     * resolvable. Both are AOSP actions — no brittle OEM ComponentNames. `POWER_USAGE_SUMMARY` visibility is
     * declared in the manifest's `<queries>`, so it resolves on any ROM that ships the screen.
     *
     * A device Amply has **no adapter at all** for resolves through here too, which is the point: an unmapped
     * device is the one most likely to need the OEM's own screen, and it used to land straight on Battery Saver
     * because there was no adapter object to ask (`ChargingRepository.nativeSettingsIntent`).
     */
    fun genericBatterySettings(context: Context): Intent {
        val powerUsage = Intent(Intent.ACTION_POWER_USAGE_SUMMARY).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return if (powerUsage.resolveActivity(context.packageManager) != null) {
            powerUsage
        } else {
            Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }
}
