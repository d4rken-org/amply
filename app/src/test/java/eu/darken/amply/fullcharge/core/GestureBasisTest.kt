package eu.darken.amply.fullcharge.core

import eu.darken.amply.charging.core.BackendKind
import eu.darken.amply.charging.core.ChargeObservation
import eu.darken.amply.charging.core.ChargePolicy
import eu.darken.amply.common.ca.toCaString
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class GestureBasisTest {

    private fun verified(policy: ChargePolicy) =
        ChargeObservation.Verified(policy, BackendKind.BATTERY_HARDWARE)

    /** What a sync-readback adapter (Samsung/OnePlus/Xiaomi HyperOS 3/LineageOS) contributes. */
    private fun readback(policy: ChargePolicy) =
        ChargeObservation.Verified(policy, BackendKind.DIRECT_WSS)

    @Test
    fun `a verified observation decides on its own`() {
        val staleJournal = ChargePolicy.FixedLimit(80)
        GestureBasis.evidence(verified(ChargePolicy.FixedLimit(80)), null, null) shouldBe PolicyEvidence.PROTECTIVE
        GestureBasis.evidence(verified(ChargePolicy.Adaptive), null, null) shouldBe PolicyEvidence.PROTECTIVE
        // The journal must not rescue an observation that conclusively reaches 100%.
        GestureBasis.evidence(verified(ChargePolicy.Unrestricted), null, staleJournal) shouldBe
            PolicyEvidence.UNRESTRICTED
        GestureBasis.evidence(verified(ChargePolicy.PauseAtFull), null, staleJournal) shouldBe
            PolicyEvidence.UNRESTRICTED
        GestureBasis.evidence(verified(ChargePolicy.FixedLimit(100)), null, staleJournal) shouldBe
            PolicyEvidence.UNRESTRICTED
    }

    @Test
    fun `without hardware evidence the journal answers`() {
        GestureBasis.evidence(null, null, ChargePolicy.FixedLimit(80)) shouldBe PolicyEvidence.PROTECTIVE
        GestureBasis.evidence(null, null, ChargePolicy.Unrestricted) shouldBe PolicyEvidence.UNRESTRICTED
    }

    @Test
    fun `nothing known is inconclusive, not unrestricted`() {
        GestureBasis.evidence(null, null, null) shouldBe PolicyEvidence.UNKNOWN
        GestureBasis.evidence(
            ChargeObservation.Unknown("no readback".toCaString()),
            ChargeObservation.Unknown("no readback".toCaString()),
            null,
        ) shouldBe PolicyEvidence.UNKNOWN
    }

    @Test
    fun `the settings readback answers where no hardware signal exists`() {
        // The ANY_LEVEL_ONLY adapters' only conclusive source. Without it these arm off the journal
        // alone, so a limit removed in the OEM's settings would keep arming the gesture.
        val staleJournal = ChargePolicy.FixedLimit(80)
        GestureBasis.evidence(null, readback(ChargePolicy.FixedLimit(80)), null) shouldBe
            PolicyEvidence.PROTECTIVE
        GestureBasis.evidence(null, readback(ChargePolicy.Unrestricted), staleJournal) shouldBe
            PolicyEvidence.UNRESTRICTED
        GestureBasis.evidence(null, readback(ChargePolicy.PauseAtFull), staleJournal) shouldBe
            PolicyEvidence.UNRESTRICTED
        // A FAILED read must not be answered from the journal. This is the whole point of the
        // source: the journal says "Amply last wrote a limit", which is exactly the claim a
        // readback exists to override, so trusting it here would keep arming the gesture after the
        // user removed the limit in the OEM's own settings. Inconclusive is the honest answer, and
        // it costs only a re-arm on the next tick that reads successfully.
        GestureBasis.evidence(
            null,
            ChargeObservation.Unknown("no readback".toCaString()),
            staleJournal,
        ) shouldBe PolicyEvidence.UNKNOWN
    }

    // The asymmetry between the two conclusive sources, pinned: a non-Verified HARDWARE decode is an
    // ordinary reading (no charging-policy state while unplugged, ambiguous in NORMAL), so it still
    // falls through to the journal — Pixel depends on that and must not change.
    @Test
    fun `an inconclusive hardware decode still falls through to the journal`() {
        GestureBasis.evidence(
            ChargeObservation.Unknown("powered NORMAL".toCaString()),
            null,
            ChargePolicy.FixedLimit(80),
        ) shouldBe PolicyEvidence.PROTECTIVE
    }

    // Null settings means "no sync source on this adapter", which is not a failure and hands the
    // question to the journal as before. Only a non-null non-Verified is a failed read.
    @Test
    fun `no sync source is not a failed read`() {
        GestureBasis.evidence(null, null, ChargePolicy.FixedLimit(80)) shouldBe PolicyEvidence.PROTECTIVE
    }

    @Test
    fun `limitPercent is source-agnostic, the caller is what withholds the readback`() {
        // limitPercent itself only asks whether the observation is Verified, so a settings readback
        // would name a percent perfectly well. What is deliberate lives one level up: the service
        // passes only the hardware decode, because every adapter that has a readback renders the
        // any-level copy and never names a percent, and feeding it here would also widen the
        // limit-hold basis's `verifiedLimitPercent` input.
        GestureBasis.limitPercent(readback(ChargePolicy.FixedLimit(80))) shouldBe 80
        GestureBasis.limitPercent(null) shouldBe null
    }

    @Test
    fun `only a verified fixed limit may be named`() {
        GestureBasis.limitPercent(verified(ChargePolicy.FixedLimit(85))) shouldBe 85
        // A verified policy that isn't a hard cap names nothing.
        GestureBasis.limitPercent(verified(ChargePolicy.Adaptive)) shouldBe null
        GestureBasis.limitPercent(verified(ChargePolicy.FixedLimit(100))) shouldBe null
        // Inconclusive hardware must not be rescued by anything: a limit the user removed natively
        // decodes as Unknown, and naming the journal's 80% there is a false user-facing claim.
        GestureBasis.limitPercent(ChargeObservation.Unknown("no readback".toCaString())) shouldBe null
        GestureBasis.limitPercent(null) shouldBe null
    }
}
