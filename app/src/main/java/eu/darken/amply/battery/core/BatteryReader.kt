package eu.darken.amply.battery.core

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
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
    private val unitCalibration: BatteryUnitCalibration,
) {
    /** Reads its own sticky [Intent.ACTION_BATTERY_CHANGED] broadcast (for callers without one). */
    fun read(): BatteryReadout {
        val battery = runCatching {
            context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        }.getOrNull() ?: return BatteryReadout.UNKNOWN
        return read(battery)
    }

    /**
     * Build a readout from an already-resolved [Intent.ACTION_BATTERY_CHANGED] intent (the one the
     * charge-session service just evaluated), so plug state / percent / voltage / temperature all
     * come from a single observation and can't be joined across two different sticky reads during a
     * rapid unplug/replug. Non-null and never falls back to a second sticky read — a caller with no
     * intent must use [BatteryReadout.UNKNOWN] rather than passing null.
     */
    fun read(battery: Intent): BatteryReadout {
        val manager = context.getSystemService(BatteryManager::class.java)
        val rawCurrent = manager.propertyOrAbsent(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
        val plugged = battery.getIntExtra(BatteryManager.EXTRA_PLUGGED, ABSENT)
        val currentScale = unitCalibration.observeCurrent(
            rawCurrent = rawCurrent.takeUnless { it == ABSENT },
            plugged = plugged.takeUnless { it == ABSENT },
            interactive = isInteractive(),
            nowElapsedMillis = SystemClock.elapsedRealtime(),
        )

        return BatteryReadoutFactory.build(
            level = battery.getIntExtra(BatteryManager.EXTRA_LEVEL, ABSENT),
            scale = battery.getIntExtra(BatteryManager.EXTRA_SCALE, ABSENT),
            status = battery.getIntExtra(BatteryManager.EXTRA_STATUS, ABSENT),
            chargingStatus = battery.getIntExtra(BatteryManager.EXTRA_CHARGING_STATUS, ABSENT),
            plugged = plugged,
            health = battery.getIntExtra(BatteryManager.EXTRA_HEALTH, ABSENT),
            technology = battery.getStringExtra(BatteryManager.EXTRA_TECHNOLOGY),
            temperatureTenths = battery.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, ABSENT),
            voltageMillivolts = battery.getIntExtra(BatteryManager.EXTRA_VOLTAGE, ABSENT),
            // Withheld until the learned unit is loaded, so an uncorrected milliamp value is never shown.
            currentNowMicroamps = if (currentScale == CurrentScale.NOT_READY) ABSENT else rawCurrent,
            chargeCounterMicroampHours = manager.propertyOrAbsent(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER),
            cycleCount = cycleCount(battery),
            maxChargingCurrentMicroamps = battery.getIntExtra(EXTRA_MAX_CHARGING_CURRENT, ABSENT),
            maxChargingVoltageMicrovolts = battery.getIntExtra(EXTRA_MAX_CHARGING_VOLTAGE, ABSENT),
            romMisreportsUnits = unitCalibration.romMisreportsUnits,
            currentIsMilliScaled = currentScale == CurrentScale.MILLI_SCALED,
        )
    }

    // EXTRA_CYCLE_COUNT is only defined from API 34 (Android 14); older platforms never report it.
    private fun cycleCount(battery: Intent): Int =
        if (Build.VERSION.SDK_INT >= 34) battery.getIntExtra(EXTRA_CYCLE_COUNT, ABSENT) else ABSENT

    private fun isInteractive(): Boolean = runCatching {
        context.getSystemService(PowerManager::class.java)?.isInteractive
    }.getOrNull() ?: false

    private fun BatteryManager?.propertyOrAbsent(property: Int): Int =
        this?.let { runCatching { it.getIntProperty(property) }.getOrDefault(ABSENT) } ?: ABSENT

    private companion object {
        const val ABSENT = BatteryReadoutFactory.ABSENT

        // BatteryManager.EXTRA_CYCLE_COUNT — inlined to avoid a hard API-34 symbol reference.
        const val EXTRA_CYCLE_COUNT = "android.os.extra.CYCLE_COUNT"

        // BatteryManager.EXTRA_MAX_CHARGING_CURRENT/VOLTAGE are @hide: BatteryService puts them into
        // ACTION_BATTERY_CHANGED, but the constants are absent from the public SDK. The literal keys
        // are stable AOSP identifiers.
        const val EXTRA_MAX_CHARGING_CURRENT = "max_charging_current"
        const val EXTRA_MAX_CHARGING_VOLTAGE = "max_charging_voltage"
    }
}
