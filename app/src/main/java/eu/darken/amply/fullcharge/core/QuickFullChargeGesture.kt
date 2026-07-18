package eu.darken.amply.fullcharge.core

import android.os.BatteryManager

enum class QuickFullChargeDecision {
    IDLE,
    ARMED,
    WAITING_FOR_RECONNECT,
    TRIGGER,
}

/**
 * Detects a deliberate unplug/replug gesture while Pixel's 80% charging policy is holding.
 * The detector only trusts Android's charging-policy hardware state, not Amply's cached request.
 */
class QuickFullChargeGesture(
    private val reconnectWindowMillis: Long = RECONNECT_WINDOW_MILLIS,
) {
    private var previousPlugged: Boolean? = null
    private var limitReachedWhilePlugged = false
    private var disconnectedAtMillis: Long? = null

    fun update(
        nowMillis: Long,
        plugged: Boolean,
        percent: Int,
        batteryStatus: Int,
        chargingStatus: Int,
    ): QuickFullChargeDecision {
        val heldAtLimit = plugged &&
            chargingStatus == CHARGING_STATUS_POLICY &&
            batteryStatus != BatteryManager.BATTERY_STATUS_CHARGING &&
            percent in MIN_ARM_PERCENT..MAX_ARM_PERCENT

        val previous = previousPlugged
        previousPlugged = plugged

        if (previous == null) {
            limitReachedWhilePlugged = heldAtLimit
            return if (heldAtLimit) QuickFullChargeDecision.ARMED else QuickFullChargeDecision.IDLE
        }

        if (previous && !plugged) {
            disconnectedAtMillis = nowMillis.takeIf { limitReachedWhilePlugged }
            return if (disconnectedAtMillis != null) {
                QuickFullChargeDecision.WAITING_FOR_RECONNECT
            } else {
                QuickFullChargeDecision.IDLE
            }
        }

        if (!previous && plugged) {
            val disconnectedAt = disconnectedAtMillis
            val shouldTrigger = disconnectedAt != null &&
                nowMillis - disconnectedAt in 0..reconnectWindowMillis
            disconnectedAtMillis = null
            limitReachedWhilePlugged = false
            return if (shouldTrigger) QuickFullChargeDecision.TRIGGER else QuickFullChargeDecision.IDLE
        }

        if (plugged && heldAtLimit) limitReachedWhilePlugged = true
        if (!plugged && disconnectedAtMillis?.let { nowMillis - it > reconnectWindowMillis } == true) {
            disconnectedAtMillis = null
            limitReachedWhilePlugged = false
        }

        return when {
            plugged && limitReachedWhilePlugged -> QuickFullChargeDecision.ARMED
            !plugged && disconnectedAtMillis != null -> QuickFullChargeDecision.WAITING_FOR_RECONNECT
            else -> QuickFullChargeDecision.IDLE
        }
    }

    fun reset() {
        previousPlugged = null
        limitReachedWhilePlugged = false
        disconnectedAtMillis = null
    }

    companion object {
        const val RECONNECT_WINDOW_MILLIS = 10_000L
        const val CHARGING_STATUS_POLICY = 4
        private const val MIN_ARM_PERCENT = 75
        private const val MAX_ARM_PERCENT = 90
    }
}
