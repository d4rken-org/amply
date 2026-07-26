package eu.darken.amply.fullcharge.core

import eu.darken.amply.charging.core.ChargeObservation
import eu.darken.amply.charging.core.ChargePolicy

/**
 * Turns what is actually known about the current charge configuration into the any-level gesture's
 * arming evidence, plus the limit to name in the waiting notification.
 *
 * [evidence] has two sources, in order ([limitPercent] has only the first — see below):
 * 1. [ChargeObservation.Verified] — the live hardware/settings readback. Conclusive on its own; the
 *    journal is not consulted at all, so a native change Amply never made still decides.
 * 2. `lastPersistentPolicy` — Amply's own journal of persistent writes. Null until Amply's first
 *    persistent write, which is exactly why it cannot be the only source: a limit set natively (or
 *    by a previous install) leaves it absent, and the any-level basis would never arm even though
 *    the limit genuinely is set.
 *
 * "Protective" is `!allowsFullCharge`, never `!= Unrestricted` — `PauseAtFull` and `FixedLimit(100)`
 * both reach 100 % and must not arm a gesture whose whole purpose is to lift a cap.
 *
 * `ChargingPreferences.protectivePolicyNow()` is deliberately **not** a source here: it defaults to
 * `FixedLimit(80)` with no history and keeps a stale baseline after a persistent `Unrestricted`
 * write, so it would both arm and display a number that is not the configured policy.
 *
 * [limitPercent] deliberately has **no** journal fallback: naming a number is a user-facing claim
 * ("charging pauses at your 80 % limit"), and the journal records what Amply last wrote, not what is
 * configured now. A user who applied 80 % through Amply and then set the native charging setting to
 * unrestricted decodes as [ChargeObservation.Unknown] on Pixel's powered NORMAL state, so a journal
 * fallback would keep claiming a limit that no longer exists. Only a [ChargeObservation.Verified]
 * fixed limit may be named. In practice nothing loses a percentage it legitimately had: only the
 * Pixel adapter implements `decodeHardware` and only Pixel supports the reconnect gesture, so an
 * unverified state simply falls back to the generic "while your charge limit is holding" copy.
 *
 * Accepted residual (arming only, distinct from the above): with any-level ON, inconclusive hardware
 * evidence plus a stale protective journal still arms even if charging was since set unrestricted
 * natively. `SessionStartDecider` refuses such a start whenever the current policy is verifiable, so
 * this only bites on a WSS-only Pixel with no Shizuku, and it is pre-existing behaviour. Failing
 * closed would reverse `SessionStartDecider`'s explicit "a stale last-request must never block a
 * session" contract.
 */
object GestureBasis {

    fun evidence(hardware: ChargeObservation?, lastPersistent: ChargePolicy?): PolicyEvidence {
        if (hardware is ChargeObservation.Verified) {
            return if (hardware.policy.allowsFullCharge) {
                PolicyEvidence.UNRESTRICTED
            } else {
                PolicyEvidence.PROTECTIVE
            }
        }
        return when {
            lastPersistent == null -> PolicyEvidence.UNKNOWN
            lastPersistent.allowsFullCharge -> PolicyEvidence.UNRESTRICTED
            else -> PolicyEvidence.PROTECTIVE
        }
    }

    /**
     * The limit percent to name in the waiting notification, or null when nothing *verified* says
     * one. Only a verified observation answers: the journal records Amply's last write, not the
     * current configuration, so falling through to it would keep claiming "your 80 % limit" after
     * the user removed that limit natively.
     */
    fun limitPercent(hardware: ChargeObservation?): Int? =
        (hardware as? ChargeObservation.Verified)?.policy?.limitPercentOrNull()

    private fun ChargePolicy.limitPercentOrNull(): Int? =
        (this as? ChargePolicy.FixedLimit)?.percent?.takeIf { !allowsFullCharge }
}
