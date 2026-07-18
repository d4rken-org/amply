package eu.darken.amply.fullcharge.core

import com.google.common.truth.Truth.assertThat
import eu.darken.amply.charging.core.ChargePolicy
import eu.darken.amply.fullcharge.core.ChargeSessionRecord
import org.junit.Test

class SessionDecisionEngineTest {
    private val started = 1_000_000L

    @Test
    fun `unplug before first connection remains armed`() {
        assertThat(decide(age = 1_000, connectedSeen = false, plugged = false, full = false))
            .isEqualTo(SessionDecision.CONTINUE)
    }

    @Test
    fun `first connection is persisted`() {
        assertThat(decide(age = 1_000, connectedSeen = false, plugged = true, full = false))
            .isEqualTo(SessionDecision.MARK_CONNECTED)
    }

    @Test
    fun `disconnect after connection restores`() {
        assertThat(decide(age = 10_000, connectedSeen = true, plugged = false, full = false))
            .isEqualTo(SessionDecision.RESTORE_DISCONNECTED)
    }

    @Test
    fun `full battery wins immediately`() {
        assertThat(decide(age = 1_000, connectedSeen = false, plugged = false, full = true))
            .isEqualTo(SessionDecision.RESTORE_FULL)
    }

    @Test
    fun `unconnected session expires after arm timeout`() {
        assertThat(
            decide(
                age = SessionDecisionEngine.ARM_TIMEOUT_MILLIS,
                connectedSeen = false,
                plugged = false,
                full = false,
            ),
        ).isEqualTo(SessionDecision.RESTORE_ARM_TIMEOUT)
    }

    @Test
    fun `safety timeout restores even while plugged`() {
        assertThat(
            decide(
                age = SessionDecisionEngine.SAFETY_TIMEOUT_MILLIS,
                connectedSeen = true,
                plugged = true,
                full = false,
            ),
        ).isEqualTo(SessionDecision.RESTORE_SAFETY_TIMEOUT)
    }

    private fun decide(age: Long, connectedSeen: Boolean, plugged: Boolean, full: Boolean) =
        SessionDecisionEngine.decide(
            session = ChargeSessionRecord(ChargePolicy.FixedLimit(80), started, connectedSeen),
            nowMillis = started + age,
            plugged = plugged,
            full = full,
        )
}
