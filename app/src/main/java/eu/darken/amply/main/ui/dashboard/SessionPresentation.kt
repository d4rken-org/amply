package eu.darken.amply.main.ui.dashboard

import eu.darken.amply.charging.core.ChargeObservation
import eu.darken.amply.charging.core.ChargePolicy
import eu.darken.amply.fullcharge.core.ChargeSessionRecord
import eu.darken.amply.fullcharge.core.policyOrNull

/**
 * How the dashboard presents a one-time full-charge session. A persisted session record alone does
 * not prove the override is in effect — the record is deliberately kept for recovery when the
 * override write fails, and it can linger after an external policy change. Only an observation of a
 * full-charge policy ([ChargePolicy.allowsFullCharge] covers each adapter's session override)
 * upgrades the record to [ACTIVE]; anything else stays [RECORDED] so no card claims 100% charging
 * that isn't happening.
 */
enum class SessionPresentation {
    /** No session record. */
    NONE,

    /** Session record present and the observed policy actually charges to 100%. */
    ACTIVE,

    /** Session record present but 100% charging is not confirmed (recovery, external change, …). */
    RECORDED,
    ;

    companion object {
        fun from(session: ChargeSessionRecord?, observation: ChargeObservation): SessionPresentation = when {
            session == null -> NONE
            observation.policyOrNull()?.allowsFullCharge == true -> ACTIVE
            else -> RECORDED
        }
    }
}

/**
 * The long-term policy the policy selector highlights. During a session that is the persisted
 * restore policy — the temporary override is not a choice the selector should mirror, and tapping
 * the already-highlighted override would silently make it permanent.
 */
fun selectedPolicyFor(session: ChargeSessionRecord?, observation: ChargeObservation): ChargePolicy? =
    session?.restorePolicy ?: observation.policyOrNull()
