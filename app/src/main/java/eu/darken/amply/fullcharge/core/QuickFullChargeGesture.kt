package eu.darken.amply.fullcharge.core

import android.os.BatteryManager

enum class QuickFullChargeDecision {
    IDLE,
    ARMED,
    WAITING_FOR_RECONNECT,
    TRIGGER,
}

/** How conclusively the current charge configuration is known, for the any-level arming basis. */
enum class PolicyEvidence { PROTECTIVE, UNRESTRICTED, UNKNOWN }

/**
 * Detects a deliberate unplug/replug gesture that starts a one-time full charge.
 *
 * Two arming bases exist:
 * - Limit hold (default): Android's charging-policy hardware state reports the Pixel policy actively
 *   holding near its limit. Only the hardware signal is trusted, never Amply's cached request.
 * - Any level (opt-in): the user enabled the any-level option and the current charge configuration
 *   is conclusively protective ([PolicyEvidence.PROTECTIVE]); percent, battery status, and the
 *   hardware hold are deliberately ignored. This basis is revoked — including an already-open
 *   reconnect window — by an explicit opt-out or by *conclusive* [PolicyEvidence.UNRESTRICTED]
 *   evidence, so an opt-out can never produce a trigger.
 *
 * [PolicyEvidence.UNKNOWN] deliberately does **not** revoke: the strongest evidence (the battery
 * broadcast's charging-policy hardware state) is only reported while external power is present, so
 * the very unplug tick that opens the reconnect window reports UNKNOWN. Treating that as a
 * withdrawal would revoke the basis before the powered→unpowered edge is even recorded and the
 * window would never open. An open window survives inconclusive evidence for at most the 10 s
 * reconnect ceiling, and `ChargeSessionManager.begin()` re-verifies live state and refuses
 * (`SessionStartDecision.AlreadyChargesFull`) when readback proves charging already reaches full.
 *
 * The reconnect window has a debounce floor: a disconnect shorter than [minReconnectMillis] never
 * triggers, filtering momentary power cuts (car ignition, connector jostle). Timestamps must come
 * from `SystemClock.elapsedRealtime()` so wall-clock changes cannot distort the window.
 */
class QuickFullChargeGesture(
    private val minReconnectMillis: Long = MIN_RECONNECT_MILLIS,
    private val maxReconnectMillis: Long = MAX_RECONNECT_MILLIS,
) {
    init {
        require(minReconnectMillis >= 0) { "minReconnectMillis must not be negative" }
        require(maxReconnectMillis >= minReconnectMillis) { "Reconnect window must not be inverted" }
    }

    data class Input(
        val nowMillis: Long,
        val plugged: Boolean,
        val percent: Int,
        val batteryStatus: Int,
        val chargingStatus: Int,
        val anyLevelEnabled: Boolean,
        val policyEvidence: PolicyEvidence,
    )

    data class Output(
        val decision: QuickFullChargeDecision,
        /** True when the current arming/window/trigger rests on the any-level option, not a hardware hold. */
        val anyLevelBasis: Boolean,
    )

    private enum class ArmedBy { LIMIT_HOLD, ANY_LEVEL }

    private var previousPlugged: Boolean? = null
    private var armedBy: ArmedBy? = null
    private var disconnectedAtMillis: Long? = null

    fun update(input: Input): Output {
        val heldAtLimit = input.plugged &&
            input.chargingStatus == CHARGING_STATUS_POLICY &&
            input.batteryStatus != BatteryManager.BATTERY_STATUS_CHARGING &&
            input.percent in MIN_ARM_PERCENT..MAX_ARM_PERCENT
        val anyLevelHeld = input.anyLevelEnabled &&
            input.plugged &&
            input.policyEvidence == PolicyEvidence.PROTECTIVE

        // An any-level basis is dropped by an explicit opt-out or by conclusive evidence that
        // charging is unrestricted — never by merely inconclusive evidence, which is what an
        // unplugged tick always reports. A latched limit-hold basis survives option flips: its
        // evidence was the (momentary) hardware hold, which is mode-independent.
        if (armedBy == ArmedBy.ANY_LEVEL &&
            (!input.anyLevelEnabled || input.policyEvidence == PolicyEvidence.UNRESTRICTED)
        ) {
            armedBy = null
            disconnectedAtMillis = null
        }

        val previous = previousPlugged
        previousPlugged = input.plugged

        if (previous == null) {
            armedBy = basisOf(heldAtLimit, anyLevelHeld)
            return statusOutput(input)
        }

        if (previous && !input.plugged) {
            disconnectedAtMillis = input.nowMillis.takeIf { armedBy != null }
            return statusOutput(input)
        }

        if (!previous && input.plugged) {
            val windowBasis = armedBy
            val delta = disconnectedAtMillis?.let { input.nowMillis - it }
            disconnectedAtMillis = null
            armedBy = null
            if (windowBasis != null && delta != null && delta in minReconnectMillis..maxReconnectMillis) {
                return Output(QuickFullChargeDecision.TRIGGER, windowBasis == ArmedBy.ANY_LEVEL)
            }
            // A too-fast or too-late replug is no trigger, but the fresh plugged state may already
            // qualify again — re-arm immediately instead of waiting for another broadcast.
            armedBy = basisOf(heldAtLimit, anyLevelHeld)
            return statusOutput(input)
        }

        if (input.plugged) {
            when {
                // Latch the hold: at the later unplug tick the hardware evidence is already gone.
                heldAtLimit -> armedBy = ArmedBy.LIMIT_HOLD
                armedBy == null && anyLevelHeld -> armedBy = ArmedBy.ANY_LEVEL
            }
        } else if (disconnectedAtMillis?.let { input.nowMillis - it > maxReconnectMillis } == true) {
            disconnectedAtMillis = null
            armedBy = null
        }
        return statusOutput(input)
    }

    fun reset() {
        previousPlugged = null
        armedBy = null
        disconnectedAtMillis = null
    }

    private fun basisOf(heldAtLimit: Boolean, anyLevelHeld: Boolean): ArmedBy? = when {
        heldAtLimit -> ArmedBy.LIMIT_HOLD
        anyLevelHeld -> ArmedBy.ANY_LEVEL
        else -> null
    }

    private fun statusOutput(input: Input): Output {
        val decision = when {
            input.plugged && armedBy != null -> QuickFullChargeDecision.ARMED
            !input.plugged && disconnectedAtMillis != null -> QuickFullChargeDecision.WAITING_FOR_RECONNECT
            else -> QuickFullChargeDecision.IDLE
        }
        return Output(decision, armedBy == ArmedBy.ANY_LEVEL)
    }

    companion object {
        const val MIN_RECONNECT_MILLIS = 2_000L
        const val MAX_RECONNECT_MILLIS = 10_000L
        const val CHARGING_STATUS_POLICY = 4
        private const val MIN_ARM_PERCENT = 75
        private const val MAX_ARM_PERCENT = 90
    }
}
