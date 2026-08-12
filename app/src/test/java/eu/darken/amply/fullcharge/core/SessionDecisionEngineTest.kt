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

    // --- Replug grace window (plug-latched adapters; disabled via replugGraceMillis = 0 elsewhere) ---

    private val grace = SessionDecisionEngine.REPLUG_GRACE_MILLIS

    private fun decideGrace(
        age: Long,
        plugged: Boolean,
        full: Boolean = false,
        connectedSeen: Boolean = true,
        disconnectedAt: Long? = null,
        replugGraceMillis: Long = grace,
    ) = SessionDecisionEngine.decide(
        session = ChargeSessionRecord(
            ChargePolicy.FixedLimit(80),
            started,
            connectedSeen,
            disconnectedAtMillis = disconnectedAt,
        ),
        nowMillis = started + age,
        plugged = plugged,
        full = full,
        replugGraceMillis = replugGraceMillis,
    )

    @Test
    fun `grace disabled keeps the immediate disconnect restore`() {
        decideGrace(age = 10_000, plugged = false, replugGraceMillis = 0L) shouldBe
            SessionDecision.RESTORE_DISCONNECTED
    }

    @Test
    fun `first disconnect opens the grace window instead of restoring`() {
        decideGrace(age = 10_000, plugged = false) shouldBe SessionDecision.MARK_DISCONNECTED
    }

    @Test
    fun `unplugged inside the window continues`() {
        decideGrace(age = 10_000 + grace - 1, plugged = false, disconnectedAt = started + 10_000) shouldBe
            SessionDecision.CONTINUE
    }

    @Test
    fun `window expiry restores`() {
        decideGrace(age = 10_000 + grace, plugged = false, disconnectedAt = started + 10_000) shouldBe
            SessionDecision.RESTORE_DISCONNECTED
    }

    @Test
    fun `a backwards clock voids the window and restores`() {
        decideGrace(age = 9_000, plugged = false, disconnectedAt = started + 10_000) shouldBe
            SessionDecision.RESTORE_DISCONNECTED
    }

    @Test
    fun `replug inside the window is marked`() {
        decideGrace(age = 15_000, plugged = true, disconnectedAt = started + 10_000) shouldBe
            SessionDecision.MARK_REPLUGGED
    }

    @Test
    fun `replug after expiry restores instead of resuming a stale session`() {
        // The latch already happened at the physical plug event either way; honoring the persisted
        // bound closes the session with a protective config rather than resuming hours later.
        decideGrace(age = 10_000 + grace * 3, plugged = true, disconnectedAt = started + 10_000) shouldBe
            SessionDecision.RESTORE_DISCONNECTED
    }

    @Test
    fun `replug with a backwards clock also restores`() {
        decideGrace(age = 9_000, plugged = true, disconnectedAt = started + 10_000) shouldBe
            SessionDecision.RESTORE_DISCONNECTED
    }

    @Test
    fun `full battery wins over an open grace window`() {
        decideGrace(age = 15_000, plugged = false, full = true, disconnectedAt = started + 10_000) shouldBe
            SessionDecision.RESTORE_FULL
    }

    @Test
    fun `safety timeout wins over an open grace window`() {
        decideGrace(
            age = SessionDecisionEngine.SAFETY_TIMEOUT_MILLIS,
            plugged = false,
            disconnectedAt = started + SessionDecisionEngine.SAFETY_TIMEOUT_MILLIS - 1_000,
        ) shouldBe SessionDecision.RESTORE_SAFETY_TIMEOUT
    }

    @Test
    fun `grace never engages before the first connection`() {
        decideGrace(age = 1_000, plugged = false, connectedSeen = false) shouldBe SessionDecision.CONTINUE
    }

    @Test
    fun `grace constant is the real duration`() {
        SessionDecisionEngine.REPLUG_GRACE_MILLIS shouldBe 30_000L
    }

    private fun decide(age: Long, connectedSeen: Boolean, plugged: Boolean, full: Boolean) =
        SessionDecisionEngine.decide(
            session = ChargeSessionRecord(ChargePolicy.FixedLimit(80), started, connectedSeen),
            nowMillis = started + age,
            plugged = plugged,
            full = full,
        )
}
