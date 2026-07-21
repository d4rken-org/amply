package eu.darken.amply.fullcharge.core

import eu.darken.amply.charging.core.ChargePolicy
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class SessionStartDeciderTest {

    private val pixelPolicies = listOf(
        ChargePolicy.FixedLimit(80),
        ChargePolicy.Adaptive,
        ChargePolicy.Unrestricted,
    )
    private val samsungModernPolicies = listOf(
        ChargePolicy.FixedLimit(80),
        ChargePolicy.FixedLimit(85),
        ChargePolicy.FixedLimit(90),
        ChargePolicy.FixedLimit(95),
        ChargePolicy.PauseAtFull,
        ChargePolicy.Unrestricted,
    )
    private val samsungLegacyPolicies = listOf(
        ChargePolicy.FixedLimit(85),
        ChargePolicy.Unrestricted,
    )

    private fun decide(
        current: ChargePolicy?,
        override: ChargePolicy = ChargePolicy.Unrestricted,
        stored: ChargePolicy = ChargePolicy.FixedLimit(80),
        supported: List<ChargePolicy> = pixelPolicies,
        default: ChargePolicy = ChargePolicy.FixedLimit(80),
        lastRequested: ChargePolicy? = null,
    ) = SessionStartDecider.decide(current, lastRequested, override, stored, supported, default)

    @Test
    fun `protective policy starts a session restoring exactly it`() {
        decide(ChargePolicy.FixedLimit(80)) shouldBe
            SessionStartDecision.Start(ChargePolicy.FixedLimit(80))
        decide(ChargePolicy.Adaptive) shouldBe
            SessionStartDecision.Start(ChargePolicy.Adaptive)
    }

    @Test
    fun `already unrestricted refuses uniformly`() {
        decide(ChargePolicy.Unrestricted) shouldBe SessionStartDecision.AlreadyChargesFull
    }

    @Test
    fun `samsung standard user refuses instead of rearming maximum`() {
        decide(
            current = ChargePolicy.PauseAtFull,
            override = ChargePolicy.PauseAtFull,
            stored = ChargePolicy.FixedLimit(80),
            supported = samsungModernPolicies,
        ) shouldBe SessionStartDecision.AlreadyChargesFull
    }

    @Test
    fun `samsung unrestricted user refuses even though override differs`() {
        decide(
            current = ChargePolicy.Unrestricted,
            override = ChargePolicy.PauseAtFull,
            supported = samsungModernPolicies,
        ) shouldBe SessionStartDecision.AlreadyChargesFull
    }

    @Test
    fun `unknown current falls back to the stored protective policy`() {
        decide(current = null, stored = ChargePolicy.Adaptive) shouldBe
            SessionStartDecision.Start(ChargePolicy.Adaptive)
    }

    @Test
    fun `stale unrestricted last-request never blocks a session`() {
        // WSS-only Pixel, unplugged: nothing is verifiable and the last request is stale
        // Unrestricted while the user has since enabled protection natively.
        decide(current = null, lastRequested = ChargePolicy.Unrestricted) shouldBe
            SessionStartDecision.Start(ChargePolicy.FixedLimit(80))
    }

    @Test
    fun `protective last-request is preferred as the restore candidate`() {
        decide(
            current = null,
            lastRequested = ChargePolicy.Adaptive,
            stored = ChargePolicy.FixedLimit(80),
        ) shouldBe SessionStartDecision.Start(ChargePolicy.Adaptive)
    }

    @Test
    fun `unsupported stored policy falls back to the adapter default`() {
        // A Pixel-era stored 80% limit is not applicable on a legacy Samsung (85% only).
        decide(
            current = null,
            override = ChargePolicy.Unrestricted,
            stored = ChargePolicy.FixedLimit(80),
            supported = samsungLegacyPolicies,
            default = ChargePolicy.FixedLimit(85),
        ) shouldBe SessionStartDecision.Start(ChargePolicy.FixedLimit(85))
    }

    @Test
    fun `unsupported observed policy falls back before persisting`() {
        // Observed 90% (e.g. set by another tool) with a legacy adapter that cannot re-apply it.
        decide(
            current = ChargePolicy.FixedLimit(90),
            override = ChargePolicy.Unrestricted,
            stored = ChargePolicy.FixedLimit(85),
            supported = samsungLegacyPolicies,
            default = ChargePolicy.FixedLimit(85),
        ) shouldBe SessionStartDecision.Start(ChargePolicy.FixedLimit(85))
    }
}
