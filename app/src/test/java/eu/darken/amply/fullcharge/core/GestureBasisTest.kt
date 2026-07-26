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

    @Test
    fun `a verified observation decides on its own`() {
        val staleJournal = ChargePolicy.FixedLimit(80)
        GestureBasis.evidence(verified(ChargePolicy.FixedLimit(80)), null) shouldBe PolicyEvidence.PROTECTIVE
        GestureBasis.evidence(verified(ChargePolicy.Adaptive), null) shouldBe PolicyEvidence.PROTECTIVE
        // The journal must not rescue an observation that conclusively reaches 100%.
        GestureBasis.evidence(verified(ChargePolicy.Unrestricted), staleJournal) shouldBe
            PolicyEvidence.UNRESTRICTED
        GestureBasis.evidence(verified(ChargePolicy.PauseAtFull), staleJournal) shouldBe
            PolicyEvidence.UNRESTRICTED
        GestureBasis.evidence(verified(ChargePolicy.FixedLimit(100)), staleJournal) shouldBe
            PolicyEvidence.UNRESTRICTED
    }

    @Test
    fun `without hardware evidence the journal answers`() {
        GestureBasis.evidence(null, ChargePolicy.FixedLimit(80)) shouldBe PolicyEvidence.PROTECTIVE
        GestureBasis.evidence(null, ChargePolicy.Unrestricted) shouldBe PolicyEvidence.UNRESTRICTED
    }

    @Test
    fun `nothing known is inconclusive, not unrestricted`() {
        GestureBasis.evidence(null, null) shouldBe PolicyEvidence.UNKNOWN
        GestureBasis.evidence(
            ChargeObservation.Unknown("no readback".toCaString()),
            null,
        ) shouldBe PolicyEvidence.UNKNOWN
    }

    @Test
    fun `the named limit comes from the strongest source`() {
        GestureBasis.limitPercent(verified(ChargePolicy.FixedLimit(85)), ChargePolicy.FixedLimit(80)) shouldBe 85
        // A natively-set adaptive policy must never be labelled as the journal's 80% limit.
        GestureBasis.limitPercent(verified(ChargePolicy.Adaptive), ChargePolicy.FixedLimit(80)) shouldBe null
        GestureBasis.limitPercent(null, ChargePolicy.FixedLimit(80)) shouldBe 80
        GestureBasis.limitPercent(verified(ChargePolicy.FixedLimit(100)), null) shouldBe null
        GestureBasis.limitPercent(null, null) shouldBe null
    }
}
