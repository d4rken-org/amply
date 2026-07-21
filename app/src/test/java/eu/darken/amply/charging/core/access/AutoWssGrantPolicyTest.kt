package eu.darken.amply.charging.core.access

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class AutoWssGrantPolicyTest {

    private fun eval(
        onboardingComplete: Boolean = true,
        controlEnabled: Boolean = true,
        shizukuReady: Boolean = true,
        wssReady: Boolean = false,
        attempted: Boolean = false,
    ) = AutoWssGrantPolicy.evaluate(
        onboardingComplete = onboardingComplete,
        controlEnabled = controlEnabled,
        shizukuReady = shizukuReady,
        wssReady = wssReady,
        attempted = attempted,
    )

    @Test
    fun `first eligible observation grants once and latches`() {
        eval() shouldBe AutoWssGrantPolicy.Outcome(grant = true, attempted = true)
    }

    @Test
    fun `already attempted this episode does not grant again`() {
        eval(attempted = true) shouldBe AutoWssGrantPolicy.Outcome(grant = false, attempted = true)
    }

    @Test
    fun `wss already granted ends the episode and clears the latch`() {
        eval(wssReady = true, attempted = true) shouldBe AutoWssGrantPolicy.Outcome(grant = false, attempted = false)
    }

    @Test
    fun `shizuku not ready is an inactive episode and clears the latch`() {
        eval(shizukuReady = false, attempted = true) shouldBe AutoWssGrantPolicy.Outcome(grant = false, attempted = false)
    }

    @Test
    fun `a fresh episode after a reset re-arms the grant`() {
        // After a reset the latch is false again; the next ready observation grants.
        eval(attempted = false) shouldBe AutoWssGrantPolicy.Outcome(grant = true, attempted = true)
    }

    @Test
    fun `never grants before onboarding is complete and preserves the latch`() {
        eval(onboardingComplete = false) shouldBe AutoWssGrantPolicy.Outcome(grant = false, attempted = false)
        eval(onboardingComplete = false, attempted = true) shouldBe
            AutoWssGrantPolicy.Outcome(grant = false, attempted = true)
    }

    @Test
    fun `never grants on a capability-gated device and preserves the latch`() {
        eval(controlEnabled = false) shouldBe AutoWssGrantPolicy.Outcome(grant = false, attempted = false)
        eval(controlEnabled = false, attempted = true) shouldBe
            AutoWssGrantPolicy.Outcome(grant = false, attempted = true)
    }

    @Test
    fun `reset takes priority over the ineligibility hold`() {
        // Even if the device is ineligible, an inactive/finished episode still clears the latch so a
        // later eligible episode is not stuck behind a stale attempt.
        eval(controlEnabled = false, shizukuReady = false, attempted = true) shouldBe
            AutoWssGrantPolicy.Outcome(grant = false, attempted = false)
    }
}
