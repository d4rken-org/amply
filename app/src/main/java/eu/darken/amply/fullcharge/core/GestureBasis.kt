package eu.darken.amply.fullcharge.core

import eu.darken.amply.charging.core.ChargeObservation
import eu.darken.amply.charging.core.ChargePolicy

/**
 * Turns what is actually known about the current charge configuration into the any-level gesture's
 * arming evidence, plus the limit to name in the waiting notification.
 *
 * [evidence] has three sources, in order ([limitPercent] has only the first — see below):
 * 1. `hardware` — the battery broadcast's charging-policy decode. Conclusive on its own; the
 *    journal is not consulted at all, so a native change Amply never made still decides. Null on
 *    every adapter that does not implement `decodeHardware`, i.e. everything but Pixel/GrapheneOS.
 * 2. `settings` — the synchronous settings readback, and the ONLY conclusive source on the
 *    `ReconnectSupport.ANY_LEVEL_ONLY` adapters, which publish no hold signal at all. Without it
 *    those devices would arm purely off the journal below, so a limit removed in the OEM's own
 *    settings would keep arming the gesture off a write Amply merely remembers making.
 *
 *    Null means "this adapter has NO sync source" — never "the read failed". The caller must keep
 *    those apart, because `ChargingRepository.syncReadback()` itself returns null for both
 *    (`readObservationOrNull` swallows a failed read), and they take opposite branches here: no
 *    source hands the question to the journal, a failed read must not. Callers with a sync source
 *    therefore substitute an explicit [ChargeObservation.Unknown] rather than passing null on.
 * 3. `lastPersistentPolicy` — Amply's own journal of persistent writes. Null until Amply's first
 *    persistent write, which is exactly why it cannot be the only source: a limit set natively (or
 *    by a previous install) leaves it absent, and the any-level basis would never arm even though
 *    the limit genuinely is set.
 *
 * Sources 1 and 2 are never both present in practice (an adapter is either hardware-decoding or
 * sync-readback), so their relative order is a formality rather than a policy choice.
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
 * fixed limit may be named. It deliberately does NOT take the `settings` source [evidence] gained:
 * every adapter that has one is `ReconnectSupport.ANY_LEVEL_ONLY`, where the notification renders
 * the any-level copy and never names a percent, so feeding it here would add a value nothing reads
 * while widening the input to the limit-hold basis's `verifiedLimitPercent`. On Pixel, where that
 * basis does run, `syncReadback()` returns null anyway — an unverified state simply falls back to
 * the generic "while your charge limit is holding" copy.
 *
 * Accepted residual (arming only, distinct from the above): with any-level ON, inconclusive hardware
 * evidence plus a stale protective journal still arms even if charging was since set unrestricted
 * natively. `SessionStartDecider` refuses such a start whenever the current policy is verifiable, so
 * this only bites on a WSS-only Pixel with no Shizuku, and it is pre-existing behaviour. Failing
 * closed would reverse `SessionStartDecider`'s explicit "a stale last-request must never block a
 * session" contract.
 */
object GestureBasis {

    fun evidence(
        hardware: ChargeObservation?,
        settings: ChargeObservation?,
        lastPersistent: ChargePolicy?,
    ): PolicyEvidence {
        (hardware as? ChargeObservation.Verified)?.let { return it.classify() }
        // Asymmetric with `hardware` on purpose. A non-Verified hardware decode is an ORDINARY
        // reading — the charging-policy state is absent while unplugged and ambiguous in the NORMAL
        // state — so it falls through and the journal still answers, exactly as it did before this
        // parameter existed. A non-null `settings` means the caller HAS a sync source and attempted
        // it, so a non-Verified result there is a genuine failure, and answering a failed readback
        // from the journal the readback exists to override is what would let a limit removed in the
        // OEM's own settings keep arming the gesture. Inconclusive is the honest answer, and the
        // engine already tolerates it everywhere it must (unplugged ticks and open windows).
        settings?.let { return (it as? ChargeObservation.Verified)?.classify() ?: PolicyEvidence.UNKNOWN }
        return when {
            lastPersistent == null -> PolicyEvidence.UNKNOWN
            lastPersistent.allowsFullCharge -> PolicyEvidence.UNRESTRICTED
            else -> PolicyEvidence.PROTECTIVE
        }
    }

    private fun ChargeObservation.Verified.classify(): PolicyEvidence =
        if (policy.allowsFullCharge) PolicyEvidence.UNRESTRICTED else PolicyEvidence.PROTECTIVE

    /**
     * The limit percent to name in the waiting notification, or null when nothing *verified* says
     * one. Only a verified observation answers: the journal records Amply's last write, not the
     * current configuration, so falling through to it would keep claiming "your 80 % limit" after
     * the user removed that limit natively.
     *
     * This is also the limit-hold gesture's *settled at the limit* arming input
     * (`QuickFullChargeGesture.Input.verifiedLimitPercent`), which is why the no-journal rule is
     * load-bearing beyond copy: [evidence]'s journal fallback would arm the default basis off a
     * limit Amply merely remembers writing. Keep this function journal-free.
     */
    fun limitPercent(hardware: ChargeObservation?): Int? =
        (hardware as? ChargeObservation.Verified)?.policy?.limitPercentOrNull()

    private fun ChargePolicy.limitPercentOrNull(): Int? =
        (this as? ChargePolicy.FixedLimit)?.percent?.takeIf { !allowsFullCharge }
}
