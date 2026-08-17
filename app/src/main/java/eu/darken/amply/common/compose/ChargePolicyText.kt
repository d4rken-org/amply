package eu.darken.amply.common.compose

import eu.darken.amply.R
import eu.darken.amply.charging.core.ChargePolicy
import eu.darken.amply.common.ca.CaString
import eu.darken.amply.common.ca.toCaString

/**
 * How a [ChargePolicy] is named and explained to the user.
 *
 * Shared rather than per-screen: the dashboard's status card and the charge-condition editor both
 * describe the same policies, and two copies would drift — the editor would keep promising "protects
 * the battery" for a 100% limit long after the dashboard learned better (see [description]'s
 * full-charge special case).
 *
 * The resource ids keep their original `dashboard_policy_*` names. They are a stored translation
 * key, not a location: renaming them for tidiness would orphan every translation of a string whose
 * text did not change.
 */
fun ChargePolicy.shortLabel(): CaString = when (this) {
    ChargePolicy.Adaptive -> R.string.dashboard_policy_adaptive.toCaString()
    ChargePolicy.Unrestricted -> R.string.dashboard_policy_full.toCaString()
    ChargePolicy.PauseAtFull -> R.string.dashboard_policy_pause_at_full.toCaString()
    is ChargePolicy.FixedLimit -> R.string.dashboard_policy_fixed.toCaString(percent)
}

fun ChargePolicy.description(): CaString = when (this) {
    ChargePolicy.Adaptive -> R.string.dashboard_policy_desc_adaptive.toCaString()
    ChargePolicy.Unrestricted -> R.string.dashboard_policy_desc_full.toCaString()
    ChargePolicy.PauseAtFull -> R.string.dashboard_policy_desc_pause.toCaString()
    is ChargePolicy.FixedLimit -> if (percent >= 100) {
        // A 100% "limit" is a full charge; the battery-health claim would be wrong.
        R.string.dashboard_policy_desc_full.toCaString()
    } else {
        R.string.dashboard_policy_desc_fixed.toCaString(percent)
    }
}
