package eu.darken.amply.battery.core

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

/**
 * Thin, Android-dependent battery reader. Collects the sticky [Intent.ACTION_BATTERY_CHANGED]
 * extras plus the live [BatteryManager] integer properties, then hands the raw values to
 * [BatteryReadoutFactory] for normalization. Needs no permission.
 *
 * Every access is defensive: a missing sticky intent yields [BatteryReadout.UNKNOWN], and each
 * [BatteryManager.getIntProperty] call is guarded because some OEM firmwares throw for unsupported
 * properties instead of returning [Int.MIN_VALUE].
 */
class BatteryReader @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    fun read(): BatteryReadout {
        val battery = runCatching {
            context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        }.getOrNull() ?: return BatteryReadout.UNKNOWN

        val manager = context.getSystemService(BatteryManager::class.java)

        return BatteryReadoutFactory.build(
            level = battery.getIntExtra(BatteryManager.EXTRA_LEVEL, ABSENT),
            scale = battery.getIntExtra(BatteryManager.EXTRA_SCALE, ABSENT),
            status = battery.getIntExtra(BatteryManager.EXTRA_STATUS, ABSENT),
            plugged = battery.getIntExtra(BatteryManager.EXTRA_PLUGGED, ABSENT),
            health = battery.getIntExtra(BatteryManager.EXTRA_HEALTH, ABSENT),
            technology = battery.getStringExtra(BatteryManager.EXTRA_TECHNOLOGY),
            temperatureTenths = battery.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, ABSENT),
            voltageMillivolts = battery.getIntExtra(BatteryManager.EXTRA_VOLTAGE, ABSENT),
            currentNowMicroamps = manager.propertyOrAbsent(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW),
            chargeCounterMicroampHours = manager.propertyOrAbsent(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER),
            cycleCount = cycleCount(battery),
        )
    }

    // EXTRA_CYCLE_COUNT is only defined from API 34 (Android 14); older platforms never report it.
    private fun cycleCount(battery: Intent): Int =
        if (Build.VERSION.SDK_INT >= 34) battery.getIntExtra(EXTRA_CYCLE_COUNT, ABSENT) else ABSENT

    private fun BatteryManager?.propertyOrAbsent(property: Int): Int =
        this?.let { runCatching { it.getIntProperty(property) }.getOrDefault(ABSENT) } ?: ABSENT

    private companion object {
        const val ABSENT = BatteryReadoutFactory.ABSENT

        // BatteryManager.EXTRA_CYCLE_COUNT — inlined to avoid a hard API-34 symbol reference.
        const val EXTRA_CYCLE_COUNT = "android.os.extra.CYCLE_COUNT"
    }
}
