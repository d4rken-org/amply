package eu.darken.amply.fullcharge.core

import eu.darken.amply.charging.core.ChargePolicy

/** How many persistent-policy buttons a surface may show; more do not fit a notification row. */
const val MAX_QUICK_ACTION_POLICIES = 3

/**
 * Which persistent-policy buttons a surface (reconnect notification, widget) shows, from the stored
 * [storedIds] selection and the adapter's capabilities.
 *
 * **Invariant: every returned policy is a member of [supported]**, in every branch including the
 * fallbacks. The buttons dispatch [ChargeSessionService.ACTION_SET_PERSISTENT_POLICY], which persists
 * a recovery target and cancels any running session *before* [eu.darken.amply.charging.core.ChargingRepository]
 * rejects the write — an unsupported policy would therefore leave the device converging on a target
 * that can never be applied.
 *
 * A device with two or fewer policies has nothing to choose between, so a stored selection is ignored
 * there rather than allowed to hide one of the two.
 */
fun resolveQuickActionPolicies(
    storedIds: List<String>?,
    supported: List<ChargePolicy>,
    defaultProtective: ChargePolicy,
): List<ChargePolicy> {
    // Today's fixed pair, filtered: an adapter that supports neither (or a defaultProtective that
    // isn't in its own supported list) yields fewer buttons rather than an unusable one.
    val fallback = listOf(defaultProtective, ChargePolicy.Unrestricted)
        .distinct()
        .filter { it in supported }
    if (supported.size <= 2) return fallback
    val stored = storedIds.orEmpty()
        .mapNotNull { ChargePolicy.fromStableId(it) }
        .filter { it in supported }
        .distinct()
        .sortedBy { supported.indexOf(it) }
        .take(MAX_QUICK_ACTION_POLICIES)
    return stored.ifEmpty { fallback }
}

/**
 * Apply one picker row's toggle to [current], keeping the 1–[MAX_QUICK_ACTION_POLICIES] bounds and
 * the membership invariant above. Out-of-bounds toggles return [current] unchanged, so a stale UI
 * (or a caller that skipped the row's `enabled` state) can neither empty the selection nor grow it
 * past what a surface renders.
 */
fun toggleQuickActionPolicy(
    current: List<ChargePolicy>,
    policy: ChargePolicy,
    selected: Boolean,
    supported: List<ChargePolicy>,
): List<ChargePolicy> {
    val sanitized = current.filter { it in supported }.distinct()
    val next = when {
        selected -> when {
            policy !in supported || policy in sanitized -> sanitized
            sanitized.size >= MAX_QUICK_ACTION_POLICIES -> sanitized
            else -> sanitized + policy
        }
        sanitized.size <= 1 -> sanitized
        else -> sanitized - policy
    }
    return next.sortedBy { supported.indexOf(it) }
}
