package eu.darken.amply.stats.core

import android.os.BatteryManager
import kotlin.math.abs

/**
 * Pure heuristic for whether an OEM charge limit appears to be holding the battery *right now*. The
 * recorder latches any positive result for the session; the UI phrases it as "limit likely reached"
 * rather than asserting it, because — unlike the privileged adapters — this is inferred from public
 * battery state without Shizuku.
 *
 * A hold means charging has actually *stopped* below full, not merely that a charge-limit *mode* is
 * enabled. Pixel keeps [android.os.BatteryManager.EXTRA_CHARGING_STATUS] == [HARDWARE_HOLD_STATE]
 * ("long life") set for the entire fixed-limit session — including while charging at over 1 A far
 * below the cap — so that value alone is not evidence of a hold. Two signals, strongest first:
 *  - plugged, below full, and the battery reports NOT_CHARGING: the reliable, OEM-agnostic signal,
 *    and the only one used when the platform doesn't report charge current; and
 *  - a Pixel refinement for the brief window where the cap is already holding but the status still
 *    reads CHARGING: long-life mode with charge current collapsed to ~0.
 */
object StatsLimitHitDetector {

    /** EXTRA_CHARGING_STATUS value meaning the charge policy is holding the battery below 100%. */
    const val HARDWARE_HOLD_STATE = 4

    /**
     * Charge-current magnitude (µA) at/under which the battery isn't meaningfully gaining charge.
     * Measured hold noise on a held Pixel is ≤ ~19 mA while active charging is ≥ ~500 mA, so 50 mA
     * cleanly separates a held cap from real charging. Compared as a magnitude so the OEM-defined
     * current sign doesn't matter.
     */
    const val HOLD_CURRENT_THRESHOLD_MICROAMPS = 50_000

    fun heldNow(
        plugged: Boolean,
        chargingStatus: Int?,
        batteryStatus: Int?,
        percent: Int?,
        currentNowMicroamps: Int?,
    ): Boolean {
        if (!plugged) return false
        // A known level below full is required to claim a limit was reached; an unknown level is not
        // evidence of anything.
        if (percent == null || percent >= 100) return false
        // Reliable and OEM-agnostic: the charger has stopped feeding the battery below full. This is
        // also the fallback when charge current isn't reported.
        if (batteryStatus == BatteryManager.BATTERY_STATUS_NOT_CHARGING) return true
        // Pixel transitional hold: long-life mode is capping at the limit but the status still reads
        // CHARGING for a moment. Only a hold once current has actually collapsed to ~0 — mode alone
        // stays set the whole session (e.g. 1.3 A at 69 %), which is the false positive this guards.
        return chargingStatus == HARDWARE_HOLD_STATE &&
            batteryStatus == BatteryManager.BATTERY_STATUS_CHARGING &&
            currentNowMicroamps != null &&
            abs(currentNowMicroamps) < HOLD_CURRENT_THRESHOLD_MICROAMPS
    }
}
