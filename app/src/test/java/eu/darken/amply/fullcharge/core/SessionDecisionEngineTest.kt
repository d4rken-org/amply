package eu.darken.amply.fullcharge.core

import eu.darken.amply.charging.core.ChargePolicy
import eu.darken.amply.fullcharge.core.ChargeSessionRecord
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class SessionDecisionEngineTest {
    private val started = 1_000_000L

    @Test
    fun `unplug before first connection remains armed`() {
        decide(age = 1_000, connectedSeen = false, plugged = false, full = false) shouldBe SessionDecision.CONTINUE
    }

    @Test
    fun `first connection is persisted`() {
        decide(age = 1_000, connectedSeen = false, plugged = true, full = false) shouldBe SessionDecision.MARK_CONNECTED
    }

    @Test
    fun `disconnect after connection restores`() {
        decide(age = 10_000, connectedSeen = true, plugged = false, full = false) shouldBe SessionDecision.RESTORE_DISCONNECTED
    }

    @Test
    fun `full battery wins immediately`() {
        decide(age = 1_000, connectedSeen = false, plugged = false, full = true) shouldBe SessionDecision.RESTORE_FULL
    }

    @Test
    fun `unconnected session expires after arm timeout`() {
        decide(
            age = SessionDecisionEngine.ARM_TIMEOUT_MILLIS,
            connectedSeen = false,
            plugged = false,
            full = false,
        ) shouldBe SessionDecision.RESTORE_ARM_TIMEOUT
    }

    @Test
    fun `safety timeout restores even while plugged`() {
        decide(
            age = SessionDecisionEngine.SAFETY_TIMEOUT_MILLIS,
            connectedSeen = true,
            plugged = true,
            full = false,
        ) shouldBe SessionDecision.RESTORE_SAFETY_TIMEOUT
    }

    @Test
    fun `release timeout constants are the real durations`() {
        SessionDecisionEngine.ARM_TIMEOUT_MILLIS shouldBe 15 * 60 * 1000L
        SessionDecisionEngine.SAFETY_TIMEOUT_MILLIS shouldBe 24 * 60 * 60 * 1000L
    }

    @Test
    fun `custom safety timeout shortens the safety restore`() {
        val decision = SessionDecisionEngine.decide(
            session = ChargeSessionRecord(ChargePolicy.FixedLimit(80), started, connectedSeen = true),
            nowMillis = started + 120_000L,
            plugged = true,
            full = false,
            armTimeoutMillis = 60_000L,
            safetyTimeoutMillis = 120_000L,
        )
        decision shouldBe SessionDecision.RESTORE_SAFETY_TIMEOUT
    }

    @Test
    fun `custom arm timeout shortens the arm restore`() {
        val decision = SessionDecisionEngine.decide(
            session = ChargeSessionRecord(ChargePolicy.FixedLimit(80), started, connectedSeen = false),
            nowMillis = started + 60_000L,
            plugged = false,
            full = false,
            armTimeoutMillis = 60_000L,
            safetyTimeoutMillis = 120_000L,
        )
        decision shouldBe SessionDecision.RESTORE_ARM_TIMEOUT
    }

    @Test
    fun `below a shortened safety timeout still continues`() {
        val decision = SessionDecisionEngine.decide(
            session = ChargeSessionRecord(ChargePolicy.FixedLimit(80), started, connectedSeen = true),
            nowMillis = started + 119_000L,
            plugged = true,
            full = false,
            armTimeoutMillis = 60_000L,
            safetyTimeoutMillis = 120_000L,
        )
        decision shouldBe SessionDecision.CONTINUE
    }

    private fun decide(age: Long, connectedSeen: Boolean, plugged: Boolean, full: Boolean) =
        SessionDecisionEngine.decide(
            session = ChargeSessionRecord(ChargePolicy.FixedLimit(80), started, connectedSeen),
            nowMillis = started + age,
            plugged = plugged,
            full = full,
        )
}
