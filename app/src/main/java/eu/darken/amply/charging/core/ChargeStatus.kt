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
    val age = now - p.requestedAt
    if (age !in 0 until SETTLING_WINDOW_MILLIS) return false // expired, or clock moved backwards
    val obs = observation
    return !(obs is ChargeObservation.Verified &&
        obs.backend == BackendKind.BATTERY_HARDWARE &&
        obs.policy == p.target)
}

/** The policy a settling request is converging on, or null when nothing is pending. Surfaces choose their own copy. */
fun ChargingState.settlingTarget(): ChargePolicy? = pending?.target
