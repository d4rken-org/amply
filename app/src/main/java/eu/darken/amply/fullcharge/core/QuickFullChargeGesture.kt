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
 * observed hold would. Carrying no timestamp of its own, it is subject to the same retirement paths
 * as any latched basis — the out-of-band check below, an any-level revocation, expiry of a reconnect
 * window it later opens, a replug that re-derives to nothing, [reset], or consuming a trigger. What
 * it is *not* bound by is the original reconnect window; that is the point (see `a retry long after
 * the original unplug still triggers`).
 *
 * Two arming bases exist:
 * - Limit hold (default): the charge limit is established as active by either of two paths, both
 *   requiring Android's charging-policy hardware state, never Amply's cached request. *Held* adds a
 *   battery status other than `CHARGING`. *Settled at the limit* instead requires that the battery
 *   has already reached the verified limit ([Input.verifiedLimitPercent]), and deliberately does
 *   **not** wait for the battery status. That wait is the problem it exists to solve: for the
 *   ~10–12 s the Pixel HAL takes to act on a freshly written limit the phone is physically topping
 *   back up and reports `CHARGING`, so every path that writes the limit while already in the arming
 *   band — a session restore, boot recovery, the widget's persistent-policy buttons — left the
 *   gesture unable to arm at all, and an unplug in that window opened no reconnect window. Neither
 *   path subsumes the other: ordinary drift to 79 % under an 80 % limit is *held* but not *settled*,
 *   and the window after a write is *settled* but not *held*. Reaching the limit is what carries the
 *   intent the dropped battery status used to carry — it separates sitting at the limit from
 *   climbing through the band, so a replug at 76 % under an 80 % limit still arms nothing.
 *   Accepting `CHARGING` is what makes the steady-plugged drop below load-bearing, because the
 *   hardware state lags a policy change in both directions. Since both paths require the
 *   charging-policy state, a policy that reports a *different* hardware state — Pixel's adaptive
 *   charging — never arms this basis at all; the any-level basis is the only one that covers it.
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
        /**
         * Percent of a *verified* active fixed limit, null when nothing verified names one. Must come
         * from the live hardware/settings readback only — never from Amply's write journal, which
         * still reports a limit the user has since removed natively.
         */
        val verifiedLimitPercent: Int? = null,
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
        val inArmingBand = input.percent in MIN_ARM_PERCENT..MAX_ARM_PERCENT
        val policyActive = input.plugged && input.chargingStatus == CHARGING_STATUS_POLICY
        // Anything but CHARGING — NOT_CHARGING at a settled hold, but also FULL/DISCHARGING/UNKNOWN,
        // all of which equally mean the battery is not being driven up right now.
        val heldAtLimit = policyActive &&
            input.batteryStatus != BatteryManager.BATTERY_STATUS_CHARGING &&
            inArmingBand
        // The battery already reached the verified limit, so the limit is established without
        // waiting out the HAL transition that keeps the battery status at CHARGING right after a
        // write. Reaching the limit is what the dropped battery status is traded for.
        val settledAtLimit = policyActive &&
            inArmingBand &&
            input.verifiedLimitPercent != null &&
            input.percent >= input.verifiedLimitPercent
        val limitBasis = heldAtLimit || settledAtLimit
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
            state = armFrom(limitBasis, anyLevelHeld)
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
                        state = armFrom(limitBasis, anyLevelHeld)
                        statusOutput(input)
                    }
                }
            }
            state = armFrom(limitBasis, anyLevelHeld)
            return statusOutput(input)
        }

        if (input.plugged) {
            when {
                // Latch the hold: at the later unplug tick the hardware evidence is already gone.
                limitBasis -> state = State.Armed(ArmedBy.LIMIT_HOLD)
                state is State.Idle && anyLevelHeld -> state = State.Armed(ArmedBy.ANY_LEVEL)
                // Positive proof the limit is not holding: current is flowing while the hardware
                // reports no charging policy at all. Required because the settled path accepts a
                // `CHARGING` reading, and the hardware state lags a policy change in *both*
                // directions — leaving an 80% limit at 80% briefly still reports state 4, which
                // would otherwise latch a limit hold that no longer exists and survive (this
                // branch has never dropped a latch) until the battery left the band. Confined to
                // the steady-plugged branch on purpose: a replug tick legitimately reads
                // `CHARGING` with no policy state yet, and dropping there would destroy exactly
                // the carried basis the reconnect window exists to preserve.
                // An unreadable percent (`< 0`) marks the whole reading as a failed sticky-broadcast
                // read, so its charging status is no proof of anything either.
                state.armingBasis == ArmedBy.LIMIT_HOLD &&
                    input.percent >= 0 &&
                    input.batteryStatus == BatteryManager.BATTERY_STATUS_CHARGING &&
                    !policyActive -> state = State.Idle
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

    private fun armFrom(limitBasis: Boolean, anyLevelHeld: Boolean): State = when {
        limitBasis -> State.Armed(ArmedBy.LIMIT_HOLD)
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
