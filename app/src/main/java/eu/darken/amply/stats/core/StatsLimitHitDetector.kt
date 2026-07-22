package eu.darken.amply.stats.core

import android.os.BatteryManager

/**
 * Pure heuristic for whether an OEM charge limit appears to be holding the battery *right now*. The
 * recorder latches any positive result for the session; the UI phrases it as "limit likely reached"
 * rather than asserting it, because — unlike the privileged adapters — this is inferred from public
 * battery state without Shizuku.
 *
 * Two signals, strongest first:
 *  - the hidden Pixel charge-policy state ([android.os.BatteryManager.EXTRA_CHARGING_STATUS] == 4,
 *    "long life"/limit hold — the same value [eu.darken.amply.fullcharge.core.QuickFullChargeGesture]
 *    keys its arming on), and
 *  - a generic fallback: plugged, reported NOT_CHARGING, and below full.
 */
object StatsLimitHitDetector {

    /** EXTRA_CHARGING_STATUS value meaning the charge policy is holding the battery below 100%. */
    const val HARDWARE_HOLD_STATE = 4

    fun heldNow(
        plugged: Boolean,
        chargingStatus: Int?,
        batteryStatus: Int?,
        percent: Int?,
    ): Boolean {
        if (!plugged) return false
        if (chargingStatus == HARDWARE_HOLD_STATE) return true
        val belowFull = percent == null || percent < 100
        return batteryStatus == BatteryManager.BATTERY_STATUS_NOT_CHARGING && belowFull
    }
}
