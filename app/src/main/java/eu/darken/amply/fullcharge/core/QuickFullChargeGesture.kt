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
 * The engine is a three-state machine — `Idle`, `Armed(basis)`, `AwaitingReconnect(basis, since)` —
 * plus the orthogonal plug-edge memory [previousPlugged]. The arming basis is *carried* through the
 * unplugged gap by `AwaitingReconnect`, and a replug that is merely mistimed hands it back to
 * `Armed` instead of discarding it. That carry-over is what makes retrying possible: a physically
 * replugged phone reports `CHARGING` while it tops back up, so re-deriving the basis from the replug
 * reading alone can never reconstruct a limit hold, and every retry after a missed window used to be
 * inert. A rejected sub-[minReconnectMillis] gap is deliberately treated as a *non-event*: the basis
 * returns to the ordinary plugged-period latch and from there persists exactly as long as a freshly
 * observed hold would — it carries no timestamp and is retired only by the out-of-band check below,
 * by [reset], or by consuming a trigger. So a retry is not bounded by the original window; that is
 * the point (see `a retry long after the original unplug still triggers`).
 *
 * Two arming bases exist:
 * - Limit hold (default): Android's charging-policy hardware state reports the Pixel policy actively
 *   holding near its limit. Only the hardware signal is trusted, never Amply's cached request.
 * - Any level (opt-in): the user enabled the any-level option and the current charge configuration
 *   is conclusively protective ([PolicyEvidence.PROTECTIVE]); percent, battery status, and the
 *   hardware hold are deliberately ignored. This basis is revoked — including an already-open
 *   reconnect window — by an explicit opt-out or by *conclusive* [PolicyEvidence.UNRESTRICTED]
 *   evidence, so an opt-out can never produce a trigger. Revocation runs before the plug edges, so a
 *   revoked basis is never carried over by a mistimed replug either.
 *
 * [PolicyEvidence.UNKNOWN] is tolerated **only** on an unplugged tick or while a reconnect window is
 * open — that is the one place the strongest evidence is structurally unavailable, because the
 * battery broadcast's charging-policy hardware state is only reported while external power is
 * present. So the very unplug tick that opens the reconnect window reports UNKNOWN; treating that as
 * a withdrawal would revoke the basis before the powered→unpowered edge is even recorded and the
 * window would never open. An open window survives inconclusive evidence for at most the 10 s
 * reconnect ceiling, and `ChargeSessionManager.begin()` re-verifies live state and refuses
 * (`SessionStartDecision.AlreadyChargesFull`) when readback proves charging already reaches full.
 *
 * A *continuously plugged* tick that goes inconclusive does drop the basis: powered, the evidence is
 * available, so an inconclusive reading means the configuration is no longer known to be protective
 * (a natively-removed limit reads as UNKNOWN, not UNRESTRICTED, on a journal-less device). Dropping
 * costs nothing — the basis re-arms on the very next tick that reports protective evidence again.
 *
 * A latched *limit-hold* basis is retired by any readable percent outside the arming band. This is
 * defence in depth for a **pre-existing** gap, not a consequence of the carry-over: the
 * steady-plugged branch has never dropped a latched basis, so a limit removed in system settings
 * left the gesture armed while the battery climbed past the band. The check runs before the plug
 * edges, so an out-of-band reading also cancels an already-open reconnect window. It is deliberately
 * narrow: an any-level basis is percent-independent by design and would lose windows it may
 * legitimately hold, and an unreadable percent (`< 0`) retires nothing, so one failed
 * sticky-broadcast read cannot disarm a healthy gesture.
 *
 * The reconnect window has a debounce floor: a disconnect shorter than [minReconnectMillis] never
 * triggers, filtering momentary power cuts (car ignition, connector jostle); such a replug returns to
 * `Armed` with the carried basis, so the next deliberate attempt can fire. Timestamps must come
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

    private sealed interface State {
        data object Idle : State
        data class Armed(val basis: ArmedBy) : State
        data class AwaitingReconnect(val basis: ArmedBy, val sinceMillis: Long) : State
    }

    private val State.armingBasis: ArmedBy?
        get() = when (this) {
            State.Idle -> null
            is State.Armed -> basis
            is State.AwaitingReconnect -> basis
        }

    private var previousPlugged: Boolean? = null
    private var state: State = State.Idle

    fun update(input: Input): Output {
        val heldAtLimit = input.plugged &&
            input.chargingStatus == CHARGING_STATUS_POLICY &&
            input.batteryStatus != BatteryManager.BATTERY_STATUS_CHARGING &&
            input.percent in MIN_ARM_PERCENT..MAX_ARM_PERCENT
        val anyLevelHeld = input.anyLevelEnabled &&
            input.plugged &&
            input.policyEvidence == PolicyEvidence.PROTECTIVE

        // An any-level basis is dropped by an explicit opt-out, by conclusive evidence that charging
        // is unrestricted, or by inconclusive evidence on a tick where conclusive evidence was
        // available (plugged, no open window) — a natively-removed limit reads UNKNOWN, not
        // UNRESTRICTED, on a journal-less device. The "not mid-window" guard is load-bearing: this
        // block runs before the replug edge is handled, so without it a replug tick whose hardware
        // has not re-reported its hold yet would destroy its own trigger.
        // A latched limit-hold basis survives option flips: its evidence was the (momentary)
        // hardware hold, which is mode-independent.
        if (state.armingBasis == ArmedBy.ANY_LEVEL &&
            (!input.anyLevelEnabled ||
                input.policyEvidence == PolicyEvidence.UNRESTRICTED ||
                (input.policyEvidence == PolicyEvidence.UNKNOWN &&
                    input.plugged &&
                    state !is State.AwaitingReconnect))
        ) {
            state = State.Idle
        }

        // Defence in depth for a latched limit-hold basis: the steady-plugged branch has never
        // dropped one, so a limit removed in system settings left the gesture armed while the
        // battery climbed past the arming band. A reading outside the band retires it. Only a
        // limit-hold basis — an any-level basis is percent-independent by design. An unreadable
        // percent (< 0) retires nothing, so a single failed sticky-broadcast read cannot disarm a
        // healthy gesture.
        if (input.percent >= 0 &&
            input.percent !in MIN_ARM_PERCENT..MAX_ARM_PERCENT &&
            state.armingBasis == ArmedBy.LIMIT_HOLD
        ) {
            state = State.Idle
        }

        val previous = previousPlugged
        previousPlugged = input.plugged

        if (previous == null) {
            state = armFrom(heldAtLimit, anyLevelHeld)
            return statusOutput(input)
        }

        if (previous && !input.plugged) {
            // Carry the basis across the gap: the hardware evidence is already gone by this tick.
            state = (state as? State.Armed)
                ?.let { State.AwaitingReconnect(it.basis, input.nowMillis) }
                ?: State.Idle
            return statusOutput(input)
        }

        if (!previous && input.plugged) {
            val awaiting = state as? State.AwaitingReconnect
            if (awaiting != null) {
                val delta = input.nowMillis - awaiting.sinceMillis
                return when {
                    delta in minReconnectMillis..maxReconnectMillis -> {
                        state = State.Idle
                        Output(QuickFullChargeDecision.TRIGGER, awaiting.basis == ArmedBy.ANY_LEVEL)
                    }
                    // Too fast (a momentary power cut): keep the basis the window carried. The
                    // replug reading itself can never re-derive it — a phone topping back up
                    // reports CHARGING, not a settled hold — so discarding it here made every
                    // retry after a rejected attempt inert.
                    delta < minReconnectMillis -> {
                        state = State.Armed(awaiting.basis)
                        statusOutput(input)
                    }
                    // Too late: the window is spent, but the fresh plugged state may already
                    // qualify again — re-arm immediately instead of waiting for another broadcast.
                    else -> {
                        state = armFrom(heldAtLimit, anyLevelHeld)
                        statusOutput(input)
                    }
                }
            }
            state = armFrom(heldAtLimit, anyLevelHeld)
            return statusOutput(input)
        }

        if (input.plugged) {
            when {
                // Latch the hold: at the later unplug tick the hardware evidence is already gone.
                heldAtLimit -> state = State.Armed(ArmedBy.LIMIT_HOLD)
                state is State.Idle && anyLevelHeld -> state = State.Armed(ArmedBy.ANY_LEVEL)
            }
        } else {
            val awaiting = state as? State.AwaitingReconnect
            if (awaiting != null && input.nowMillis - awaiting.sinceMillis > maxReconnectMillis) {
                state = State.Idle
            }
        }
        return statusOutput(input)
    }

    fun reset() {
        previousPlugged = null
        state = State.Idle
    }

    private fun armFrom(heldAtLimit: Boolean, anyLevelHeld: Boolean): State = when {
        heldAtLimit -> State.Armed(ArmedBy.LIMIT_HOLD)
        anyLevelHeld -> State.Armed(ArmedBy.ANY_LEVEL)
        else -> State.Idle
    }

    private fun statusOutput(input: Input): Output {
        val current = state
        val decision = when {
            input.plugged && current is State.Armed -> QuickFullChargeDecision.ARMED
            !input.plugged && current is State.AwaitingReconnect -> QuickFullChargeDecision.WAITING_FOR_RECONNECT
            else -> QuickFullChargeDecision.IDLE
        }
        return Output(decision, current.armingBasis == ArmedBy.ANY_LEVEL)
    }

    companion object {
        const val MIN_RECONNECT_MILLIS = 2_000L
        const val MAX_RECONNECT_MILLIS = 10_000L
        const val CHARGING_STATUS_POLICY = 4
        private const val MIN_ARM_PERCENT = 75
        private const val MAX_ARM_PERCENT = 90
    }
}
