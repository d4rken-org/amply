package eu.darken.amply.fullcharge.core

/** A single historical process-exit record, distilled from `ApplicationExitInfo`. */
data class ExitRecord(
    val timestampMillis: Long,
    val pid: Int,
    val reason: Int,
)

/**
 * Pure, JVM-testable decisions for interrupted-session detection. Everything here is evidence-based:
 * a survived-death signal from a process-token mismatch, a same-boot guard from the boot counter, an
 * effect gate from what work was actually due, and a cosmetic exit-reason classification that never
 * overclaims a force-stop.
 */
object InterruptionDecisionEngine {

    /** `ApplicationExitInfo.REASON_USER_REQUESTED` (10). */
    const val REASON_USER_REQUESTED = 10

    /** `ApplicationExitInfo.REASON_USER_STOPPED` (11). */
    const val REASON_USER_STOPPED = 11

    /** True when persisted work was created by a different, now-dead process. */
    fun survivedDeath(storedToken: String?, currentToken: String): Boolean =
        storedToken != null && storedToken != currentToken

    /** True only when both boot counts are known and equal — otherwise a reboot cannot be excluded. */
    fun sameBoot(storedBootCount: Int?, currentBootCount: Int?): Boolean =
        storedBootCount != null && currentBootCount != null && storedBootCount == currentBootCount

    /**
     * A session pickup that crossed a same-boot death warrants an event only when a restore was
     * actually due (a `RESTORE_*` decision). `CONTINUE` / `MARK_*` mean the session resumed
     * unharmed — the replug-grace marks included, they just move the session along — no event.
     */
    fun shouldRecordForSession(survived: Boolean, sameBoot: Boolean, decision: SessionDecision): Boolean {
        if (!survived || !sameBoot) return false
        return when (decision) {
            SessionDecision.RESTORE_FULL,
            SessionDecision.RESTORE_DISCONNECTED,
            SessionDecision.RESTORE_ARM_TIMEOUT,
            SessionDecision.RESTORE_SAFETY_TIMEOUT -> true
            SessionDecision.CONTINUE,
            SessionDecision.MARK_CONNECTED,
            SessionDecision.MARK_DISCONNECTED,
            SessionDecision.MARK_REPLUGGED -> false
        }
    }

    /**
     * Maps a completed [BootRecoveryFlow.Result] to an outcome, or null for "no event". Requires a
     * survived same-boot death first; then only a flow that actually did (or attempted) work counts.
     */
    fun recoveryOutcome(
        survived: Boolean,
        sameBoot: Boolean,
        result: BootRecoveryFlow.Result,
    ): InterruptionOutcome? {
        if (!survived || !sameBoot) return null
        return when (result.outcome) {
            BootRecoveryFlow.Outcome.CONVERGED ->
                if (result.restoreAttempted || result.rewrites > 0) InterruptionOutcome.RESTORED_LATE else null
            BootRecoveryFlow.Outcome.RESTORE_FAILED -> InterruptionOutcome.STILL_PENDING
            BootRecoveryFlow.Outcome.GAVE_UP ->
                if (result.retryRemaining) InterruptionOutcome.STILL_PENDING else InterruptionOutcome.UNCONFIRMED
            BootRecoveryFlow.Outcome.NOTHING_TO_DO,
            BootRecoveryFlow.Outcome.SUPERSEDED -> null
        }
    }

    /**
     * Cosmetic reason from the historical exit list: the latest record for [ownerPid] at or after
     * [notBeforeMillis] decides. Codes 10/11 read as [InterruptionReason.USER_STOPPED]; anything else,
     * a null owner PID, or no matching record reads as [InterruptionReason.OTHER].
     */
    fun classifyExit(
        exits: List<ExitRecord>,
        ownerPid: Int?,
        notBeforeMillis: Long,
    ): InterruptionReason {
        if (ownerPid == null) return InterruptionReason.OTHER
        val candidate = exits
            .filter { it.pid == ownerPid && it.timestampMillis >= notBeforeMillis }
            .maxByOrNull { it.timestampMillis }
            ?: return InterruptionReason.OTHER
        return when (candidate.reason) {
            REASON_USER_REQUESTED, REASON_USER_STOPPED -> InterruptionReason.USER_STOPPED
            else -> InterruptionReason.OTHER
        }
    }
}
