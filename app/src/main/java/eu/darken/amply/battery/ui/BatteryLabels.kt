package eu.darken.amply.battery.ui

import android.os.BatteryManager
import androidx.annotation.StringRes
import eu.darken.amply.R

/**
 * Maps raw [BatteryManager] integer constants to user-facing string resources. Unknown or future
 * constants fall back to a generic label rather than crashing or hiding the row.
 */

@StringRes
internal fun batteryStatusLabel(status: Int?): Int = when (status) {
    BatteryManager.BATTERY_STATUS_CHARGING -> R.string.battery_status_charging
    BatteryManager.BATTERY_STATUS_DISCHARGING -> R.string.battery_status_discharging
    BatteryManager.BATTERY_STATUS_NOT_CHARGING -> R.string.battery_status_not_charging
    BatteryManager.BATTERY_STATUS_FULL -> R.string.battery_status_full
    else -> R.string.battery_value_unknown
}

@StringRes
internal fun batteryHealthLabel(health: Int?): Int = when (health) {
    BatteryManager.BATTERY_HEALTH_GOOD -> R.string.battery_health_good
    BatteryManager.BATTERY_HEALTH_OVERHEAT -> R.string.battery_health_overheat
    BatteryManager.BATTERY_HEALTH_DEAD -> R.string.battery_health_dead
    BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE -> R.string.battery_health_over_voltage
    BatteryManager.BATTERY_HEALTH_UNSPECIFIED_FAILURE -> R.string.battery_health_failure
    BatteryManager.BATTERY_HEALTH_COLD -> R.string.battery_health_cold
    else -> R.string.battery_value_unknown
}

@StringRes
internal fun batteryPlugLabel(plugged: Int?): Int? = when (plugged) {
    null -> null
    0 -> R.string.battery_plug_unplugged
    BatteryManager.BATTERY_PLUGGED_AC -> R.string.battery_plug_ac
    BatteryManager.BATTERY_PLUGGED_USB -> R.string.battery_plug_usb
    BatteryManager.BATTERY_PLUGGED_WIRELESS -> R.string.battery_plug_wireless
    else -> R.string.battery_plug_other
}
