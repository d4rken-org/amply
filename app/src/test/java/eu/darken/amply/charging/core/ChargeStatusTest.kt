package eu.darken.amply.charging.core

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ChargeStatusTest {
    private val target = ChargePolicy.FixedLimit(80)
    private val t0 = 1_000_000L

    private fun state(
        pending: PendingRequest? = PendingRequest(target, t0),
        observation: ChargeObservation = ChargeObservation.LastRequested(target),
    ) = ChargingState(observation = observation, pending = pending)

    @Test
    fun `settling within window and not hardware verified`() {
        assertThat(state().isSettling(t0 + 5_000)).isTrue()
    }

    @Test
    fun `hardware verification matching the target clears settling`() {
        val s = state(observation = ChargeObservation.Verified(target, BackendKind.BATTERY_HARDWARE))
        assertThat(s.isSettling(t0 + 5_000)).isFalse()
    }

    @Test
    fun `hardware verification for a different policy stays settling`() {
        val s = state(observation = ChargeObservation.Verified(ChargePolicy.Adaptive, BackendKind.BATTERY_HARDWARE))
        assertThat(s.isSettling(t0 + 5_000)).isTrue()
    }

    @Test
    fun `settings-level verification does not clear settling`() {
        val s = state(observation = ChargeObservation.Verified(target, BackendKind.SHIZUKU))
        assertThat(s.isSettling(t0 + 5_000)).isTrue()
    }

    @Test
    fun `at exactly the window boundary it is no longer settling`() {
        assertThat(state().isSettling(t0 + SETTLING_WINDOW_MILLIS)).isFalse()
    }

    @Test
    fun `a future timestamp from a backwards clock is not settling`() {
        assertThat(state().isSettling(t0 - 1)).isFalse()
    }

    @Test
    fun `no pending request is never settling`() {
        assertThat(state(pending = null).isSettling(t0 + 1)).isFalse()
    }

    @Test
    fun `settlingTarget reflects the pending target or null`() {
        assertThat(state().settlingTarget()).isEqualTo(target)
        assertThat(state(pending = null).settlingTarget()).isNull()
    }
}
