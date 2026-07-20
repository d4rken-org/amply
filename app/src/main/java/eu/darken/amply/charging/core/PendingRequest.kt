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
)
