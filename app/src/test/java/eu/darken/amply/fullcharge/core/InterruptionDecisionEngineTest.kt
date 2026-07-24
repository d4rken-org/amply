package eu.darken.amply.fullcharge.core

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class InterruptionDecisionEngineTest {

    // --- survivedDeath -------------------------------------------------------

    @Test
    fun `survivedDeath is false when the stored token is null`() {
        InterruptionDecisionEngine.survivedDeath(storedToken = null, currentToken = "a") shouldBe false
    }

    @Test
    fun `survivedDeath is false when tokens match`() {
        InterruptionDecisionEngine.survivedDeath(storedToken = "a", currentToken = "a") shouldBe false
    }

    @Test
    fun `survivedDeath is true when a stored token differs`() {
        InterruptionDecisionEngine.survivedDeath(storedToken = "old", currentToken = "new") shouldBe true
    }

    // --- sameBoot ------------------------------------------------------------

    @Test
    fun `sameBoot needs both counts present and equal`() {
        InterruptionDecisionEngine.sameBoot(null, 5) shouldBe false
        InterruptionDecisionEngine.sameBoot(5, null) shouldBe false
        InterruptionDecisionEngine.sameBoot(null, null) shouldBe false
        InterruptionDecisionEngine.sameBoot(5, 6) shouldBe false
        InterruptionDecisionEngine.sameBoot(5, 5) shouldBe true
    }

    // --- shouldRecordForSession ---------------------------------------------

    @Test
    fun `session record requires a survived same-boot restore decision`() {
        // Gates fail → never record, whatever the decision.
        InterruptionDecisionEngine.shouldRecordForSession(false, true, SessionDecision.RESTORE_FULL) shouldBe false
        InterruptionDecisionEngine.shouldRecordForSession(true, false, SessionDecision.RESTORE_FULL) shouldBe false

        // Gates pass: only the RESTORE_* decisions record.
        val restores = listOf(
            SessionDecision.RESTORE_FULL,
            SessionDecision.RESTORE_DISCONNECTED,
            SessionDecision.RESTORE_ARM_TIMEOUT,
            SessionDecision.RESTORE_SAFETY_TIMEOUT,
        )
        restores.forEach {
            InterruptionDecisionEngine.shouldRecordForSession(true, true, it) shouldBe true
        }
        InterruptionDecisionEngine.shouldRecordForSession(true, true, SessionDecision.CONTINUE) shouldBe false
        InterruptionDecisionEngine.shouldRecordForSession(true, true, SessionDecision.MARK_CONNECTED) shouldBe false
    }

    // --- recoveryOutcome -----------------------------------------------------

    private fun result(
        outcome: BootRecoveryFlow.Outcome,
        restoreAttempted: Boolean = false,
        rewrites: Int = 0,
        retryRemaining: Boolean = false,
    ) = BootRecoveryFlow.Result(outcome, restoreAttempted, rewrites, retryRemaining)

    @Test
    fun `recoveryOutcome is null when gates fail`() {
        val r = result(BootRecoveryFlow.Outcome.CONVERGED, restoreAttempted = true)
        InterruptionDecisionEngine.recoveryOutcome(false, true, r) shouldBe null
        InterruptionDecisionEngine.recoveryOutcome(true, false, r) shouldBe null
    }

    @Test
    fun `converged with restore or rewrites is restored-late, without work is null`() {
        InterruptionDecisionEngine.recoveryOutcome(
            true, true, result(BootRecoveryFlow.Outcome.CONVERGED, restoreAttempted = true),
        ) shouldBe InterruptionOutcome.RESTORED_LATE
        InterruptionDecisionEngine.recoveryOutcome(
            true, true, result(BootRecoveryFlow.Outcome.CONVERGED, rewrites = 2),
        ) shouldBe InterruptionOutcome.RESTORED_LATE
        InterruptionDecisionEngine.recoveryOutcome(
            true, true, result(BootRecoveryFlow.Outcome.CONVERGED),
        ) shouldBe null
    }

    @Test
    fun `restore failed maps to still-pending`() {
        InterruptionDecisionEngine.recoveryOutcome(
            true, true, result(BootRecoveryFlow.Outcome.RESTORE_FAILED, restoreAttempted = true),
        ) shouldBe InterruptionOutcome.STILL_PENDING
    }

    @Test
    fun `gave up splits on retry-remaining`() {
        InterruptionDecisionEngine.recoveryOutcome(
            true, true, result(BootRecoveryFlow.Outcome.GAVE_UP, rewrites = 1, retryRemaining = true),
        ) shouldBe InterruptionOutcome.STILL_PENDING
        InterruptionDecisionEngine.recoveryOutcome(
            true, true, result(BootRecoveryFlow.Outcome.GAVE_UP, retryRemaining = false),
        ) shouldBe InterruptionOutcome.UNCONFIRMED
    }

    @Test
    fun `nothing-to-do and superseded map to null`() {
        InterruptionDecisionEngine.recoveryOutcome(
            true, true, result(BootRecoveryFlow.Outcome.NOTHING_TO_DO),
        ) shouldBe null
        InterruptionDecisionEngine.recoveryOutcome(
            true, true, result(BootRecoveryFlow.Outcome.SUPERSEDED),
        ) shouldBe null
    }

    // --- classifyExit --------------------------------------------------------

    @Test
    fun `classifyExit is OTHER with a null owner pid`() {
        val exits = listOf(ExitRecord(timestampMillis = 100, pid = 5, reason = 10))
        InterruptionDecisionEngine.classifyExit(exits, ownerPid = null, notBeforeMillis = 0) shouldBe
            InterruptionReason.OTHER
    }

    @Test
    fun `classifyExit is OTHER on an empty list`() {
        InterruptionDecisionEngine.classifyExit(emptyList(), ownerPid = 5, notBeforeMillis = 0) shouldBe
            InterruptionReason.OTHER
    }

    @Test
    fun `classifyExit filters by owner pid`() {
        val exits = listOf(ExitRecord(timestampMillis = 100, pid = 6, reason = 10))
        InterruptionDecisionEngine.classifyExit(exits, ownerPid = 5, notBeforeMillis = 0) shouldBe
            InterruptionReason.OTHER
    }

    @Test
    fun `classifyExit ignores records before the window`() {
        val exits = listOf(ExitRecord(timestampMillis = 50, pid = 5, reason = 10))
        InterruptionDecisionEngine.classifyExit(exits, ownerPid = 5, notBeforeMillis = 100) shouldBe
            InterruptionReason.OTHER
    }

    @Test
    fun `classifyExit picks the latest candidate`() {
        val exits = listOf(
            ExitRecord(timestampMillis = 100, pid = 5, reason = 10),
            ExitRecord(timestampMillis = 200, pid = 5, reason = 6),
        )
        // Latest (200) is a non-user reason, so OTHER despite an earlier user-stop record.
        InterruptionDecisionEngine.classifyExit(exits, ownerPid = 5, notBeforeMillis = 0) shouldBe
            InterruptionReason.OTHER
    }

    @Test
    fun `classifyExit reads codes 10 and 11 as user-stopped`() {
        listOf(10, 11).forEach { reason ->
            val exits = listOf(ExitRecord(timestampMillis = 100, pid = 5, reason = reason))
            InterruptionDecisionEngine.classifyExit(exits, ownerPid = 5, notBeforeMillis = 0) shouldBe
                InterruptionReason.USER_STOPPED
        }
    }

    @Test
    fun `classifyExit reads every other known code and an unknown one as OTHER`() {
        // ApplicationExitInfo reason codes 0..16 minus the two user-stop codes, plus a future code.
        val otherCodes = (0..16).filter { it != 10 && it != 11 } + 99
        otherCodes.forEach { reason ->
            val exits = listOf(ExitRecord(timestampMillis = 100, pid = 5, reason = reason))
            InterruptionDecisionEngine.classifyExit(exits, ownerPid = 5, notBeforeMillis = 0) shouldBe
                InterruptionReason.OTHER
        }
    }
}
