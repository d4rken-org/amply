package eu.darken.amply.charging.core

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
}
