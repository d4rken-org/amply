package eu.darken.amply.charging.core

/**
 * A charging-policy change that Amply has written but the charging hardware has not yet confirmed.
 *
 * Bound to its [target] on purpose: a hardware reading for a *different* policy (e.g. the old limit
 * still holding mid-transition) is not confirmation of this request.
 */
data class PendingRequest(
    val target: ChargePolicy,
    val requestedAt: Long,
    /**
     * True when the write can only latch at the next plug transition (a
     * [eu.darken.amply.charging.core.adapter.ChargingAdapter.policyLatchesAtPlug] adapter wrote while
     * external power was present). Resolution is a *condition* — an observed unplug, or hardware
     * evidence for the target — never the settling-window clock, so such a request carries no expiry.
     */
    val awaitingReplug: Boolean = false,
)
