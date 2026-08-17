package eu.darken.amply.charging.core

import eu.darken.amply.common.ca.toCaString
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class ChargeStatusTest {
    private val target = ChargePolicy.FixedLimit(80)
    private val t0 = 1_000_000L

    private fun state(
        pending: PendingRequest? = PendingRequest(target, t0),
        observation: ChargeObservation = ChargeObservation.LastRequested(target),
    ) = ChargingState(observation = observation, pending = pending)

    @Test
    fun `settling within window and not hardware verified`() {
        state().isSettling(t0 + 5_000) shouldBe true
    }

    @Test
    fun `hardware verification matching the target clears settling`() {
        val s = state(observation = ChargeObservation.Verified(target, BackendKind.BATTERY_HARDWARE))
        s.isSettling(t0 + 5_000) shouldBe false
    }

    @Test
    fun `hardware verification for a different policy stays settling`() {
        val s = state(observation = ChargeObservation.Verified(ChargePolicy.Adaptive, BackendKind.BATTERY_HARDWARE))
        s.isSettling(t0 + 5_000) shouldBe true
    }

    @Test
    fun `settings-level verification does not clear settling`() {
        val s = state(observation = ChargeObservation.Verified(target, BackendKind.SHIZUKU))
        s.isSettling(t0 + 5_000) shouldBe true
    }

    @Test
    fun `at exactly the window boundary it is no longer settling`() {
        state().isSettling(t0 + SETTLING_WINDOW_MILLIS) shouldBe false
    }

    @Test
    fun `a future timestamp from a backwards clock is not settling`() {
        state().isSettling(t0 - 1) shouldBe false
    }

    @Test
    fun `no pending request is never settling`() {
        state(pending = null).isSettling(t0 + 1) shouldBe false
    }

    @Test
    fun `settlingTarget reflects the pending target or null`() {
        state().settlingTarget() shouldBe target
        state(pending = null).settlingTarget() shouldBe null
    }

    // --- awaitingReplug: a latched request is a condition, not a countdown ---

    @Test
    fun `an awaiting-replug request is never settling`() {
        val s = state(pending = PendingRequest(target, t0, awaitingReplug = true))
        s.isSettling(t0 + 5_000) shouldBe false
    }

    @Test
    fun `isAwaitingReplug mirrors the pending flag`() {
        state(pending = PendingRequest(target, t0, awaitingReplug = true)).isAwaitingReplug() shouldBe true
        state().isAwaitingReplug() shouldBe false
        state(pending = null).isAwaitingReplug() shouldBe false
    }

    @Test
    fun `settlingTarget still reports an awaiting-replug target`() {
        state(pending = PendingRequest(target, t0, awaitingReplug = true)).settlingTarget() shouldBe target
    }

    // --- provesPolicyInEffect: does the configuration describe what the charger is doing? ---

    @Test
    fun `hardware evidence settles it for any policy`() {
        ChargeObservation.Verified(target, BackendKind.BATTERY_HARDWARE)
            .provesPolicyInEffect() shouldBe true
        ChargeObservation.Verified(ChargePolicy.Adaptive, BackendKind.BATTERY_HARDWARE)
            .provesPolicyInEffect() shouldBe true
    }

    @Test
    fun `a readback of an unconditional policy describes what the charger does`() {
        ChargeObservation.Verified(target, BackendKind.SHIZUKU).provesPolicyInEffect() shouldBe true
        ChargeObservation.Verified(target, BackendKind.DIRECT_WSS).provesPolicyInEffect() shouldBe true
        ChargeObservation.Verified(ChargePolicy.PauseAtFull, BackendKind.SHIZUKU)
            .provesPolicyInEffect() shouldBe true
        // Unrestricted included deliberately: it is in effect exactly as verifiably as a cap. This
        // predicate is about knowledge, not safety — it does not claim the battery is protected.
        ChargeObservation.Verified(ChargePolicy.Unrestricted, BackendKind.SHIZUKU)
            .provesPolicyInEffect() shouldBe true
    }

    @Test
    fun `a readback of adaptive proves only that the mode is configured`() {
        // The Xiaomi 13T case: configured, verified, and charging straight past the cap.
        ChargeObservation.Verified(ChargePolicy.Adaptive, BackendKind.SHIZUKU)
            .provesPolicyInEffect() shouldBe false
        ChargeObservation.Verified(ChargePolicy.Adaptive, BackendKind.DIRECT_WSS)
            .provesPolicyInEffect() shouldBe false
        ChargeObservation.Verified(ChargePolicy.Adaptive, BackendKind.DEEP_LINK)
            .provesPolicyInEffect() shouldBe false
    }

    @Test
    fun `non-verified observations never settle it`() {
        ChargeObservation.LastRequested(target).provesPolicyInEffect() shouldBe false
        ChargeObservation.Unknown("x".toCaString()).provesPolicyInEffect() shouldBe false
        ChargeObservation.NeedsSetup("x".toCaString()).provesPolicyInEffect() shouldBe false
        ChargeObservation.Unsupported("x".toCaString()).provesPolicyInEffect() shouldBe false
    }

    @Test
    fun `the claim predicate never leaks into settling`() {
        // Regression guard: adopting provesPolicyInEffect in isSettling (or in the repository's
        // `settled` / computeRefreshPending sync arm) would spin every Xiaomi adaptive write for the
        // whole window. Settling asks "did the write land", which a readback answers fully.
        val adaptive = ChargePolicy.Adaptive
        val s = ChargingState(
            observation = ChargeObservation.Verified(adaptive, BackendKind.SHIZUKU),
            pending = PendingRequest(adaptive, t0),
        )
        s.observation.provesPolicyInEffect() shouldBe false
        // Unchanged behaviour: a settings readback has never cleared settling, for any policy.
        s.isSettling(t0 + 5_000) shouldBe true
    }
}
