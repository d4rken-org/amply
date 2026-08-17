package eu.darken.amply.charging.core

/** How long after a write we consider the hardware to still be "settling" to the requested policy. */
const val SETTLING_WINDOW_MILLIS = 15_000L

/**
 * True while a requested policy is written but not yet hardware-confirmed and still inside the settling
 * window. Only a [BackendKind.BATTERY_HARDWARE] verification *for the requested target* counts as
 * confirmation — a settings-level (Shizuku) readback confirms the setting, not the charging state, and
 * a hardware reading for a different policy is the old state that hasn't transitioned yet.
 *
 * `now` is passed in (not read here) so this stays pure and unit-testable, and so callers can drive a
 * live clock for the countdown.
 */
fun ChargingState.isSettling(now: Long): Boolean {
    val p = pending ?: return false
    // A latched request has no clock to run down — surfaces show the replug hint, not a spinner.
    if (p.awaitingReplug) return false
    val age = now - p.requestedAt
    if (age !in 0 until SETTLING_WINDOW_MILLIS) return false // expired, or clock moved backwards
    val obs = observation
    return !(obs is ChargeObservation.Verified &&
        obs.backend == BackendKind.BATTERY_HARDWARE &&
        obs.policy == p.target)
}

/**
 * Whether the observed policy is what the charger is actually *doing*, as opposed to what has merely
 * been *selected*. Note this is a statement about knowledge, not about safety: `Unrestricted` is in
 * effect exactly as verifiably as a fixed limit, it just protects nothing.
 *
 * For an unconditional policy the two coincide — a fixed limit caps, and no limit charges to full,
 * whenever configured. For a policy whose engagement the OEM decides
 * ([ChargePolicy.enforcementIsConditional]) they come apart: a readback proves the mode is selected
 * and nothing more, so only a [BackendKind.BATTERY_HARDWARE] reading can show it is engaged.
 *
 * [isSettling] asks whether the write landed; this asks whether the configuration describes reality.
 * Those are different questions, which is why this is **presentation only**. It must never be adopted
 * by pending, settling, recovery, session, or gesture logic: those all ask "is the configuration what
 * we asked for?", which a conditional policy answers fully. In particular `ChargingRepository`'s
 * `settled` computation and `computeRefreshPending`'s sync-readback arm must keep clearing on any
 * matching readback — adopting this there would spin a Xiaomi adaptive write for the full settling
 * window on every apply, on that adapter's own protective default.
 */
fun ChargeObservation.provesPolicyInEffect(): Boolean =
    this is ChargeObservation.Verified &&
        (backend == BackendKind.BATTERY_HARDWARE || !policy.enforcementIsConditional)

/** The policy a settling request is converging on, or null when nothing is pending. Surfaces choose their own copy. */
fun ChargingState.settlingTarget(): ChargePolicy? = pending?.target

/**
 * True while a written policy is waiting for the user to unplug and replug before the charging
 * hardware can pick it up (plug-latched adapters). Mutually exclusive with [isSettling].
 */
fun ChargingState.isAwaitingReplug(): Boolean = pending?.awaitingReplug == true
