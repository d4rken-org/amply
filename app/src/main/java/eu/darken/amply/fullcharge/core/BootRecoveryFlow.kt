package eu.darken.amply.fullcharge.core

import eu.darken.amply.charging.core.ChargeObservation
import eu.darken.amply.charging.core.ChargePolicy
import eu.darken.amply.common.debug.logging.Logging
import eu.darken.amply.common.debug.logging.log
import eu.darken.amply.common.debug.logging.logTag

data class BatterySnapshot(
    val plugged: Boolean,
    val percent: Int,
    val chargingState: Int,
)

/**
 * Restore orchestration for service starts without a live session (boot, and foreground-launch
 * checks after a force-stop): restore the persisted protective policy, then drive
 * [BootRecoveryEngine] until the charging hardware confirms it, a nudge re-write was sent,
 * or the budget runs out. The pending target persists across process death so a killed
 * service resumes the check instead of losing it.
 */
class BootRecoveryFlow(private val hooks: Hooks) {

    interface Hooks {
        suspend fun currentSessionTarget(): ChargePolicy?
        suspend fun pendingTarget(): ChargePolicy?
        suspend fun setPendingTarget(policy: ChargePolicy)
        suspend fun clearPendingTarget()
        suspend fun restoreSession(): Boolean

        /** Drops a session record that a newer pending target has made stale, without restoring it. */
        suspend fun dropStaleSession()
        suspend fun rewrite(policy: ChargePolicy): Boolean

        /** The policy Amply itself was most recently asked to configure, if any. */
        suspend fun intendedTarget(): ChargePolicy?
        fun batterySnapshot(): BatterySnapshot?
        fun hardwareObservation(snapshot: BatterySnapshot): ChargeObservation?

        /** [writeFailed] is true when a settings write failed (access problem), false when the hardware never confirmed. */
        fun notifyFailure(writeFailed: Boolean)
        suspend fun tick()
        fun elapsedRealtime(): Long
    }

    enum class Outcome {
        NOTHING_TO_DO,
        RESTORE_FAILED,
        CONVERGED,
        GAVE_UP,
        SUPERSEDED,
    }

    suspend fun run(): Outcome {
        val sessionTarget = hooks.currentSessionTarget()
        val pendingTarget = hooks.pendingTarget()
        val target = pendingTarget ?: sessionTarget ?: return Outcome.NOTHING_TO_DO
        var staleIntended: ChargePolicy? = null
        if (pendingTarget != null) {
            // The pending target is always the newest intent: setPersistentPolicy persists it
            // before superseding any session, and this flow only seeds it from the session it is
            // already recovering. A session record that coexists with it is stale — drop it
            // without restoring, so its older policy can never overwrite the newer choice.
            if (sessionTarget != null) {
                log(TAG) { "Recovery: dropping stale session; pending ${target.stableId} is newer" }
                hooks.dropStaleSession()
            }
            // The last-requested policy can predate the pending target when the process died
            // before the persistent write landed. Remember the stale value so the supersede
            // check below doesn't mistake it for a newer user choice and abandon recovery.
            staleIntended = hooks.intendedTarget()
            log(TAG) { "Recovery: resuming convergence check for ${target.stableId}" }
        } else {
            hooks.setPendingTarget(target)
            log(TAG) { "Recovery: restoring ${target.stableId}" }
            if (!hooks.restoreSession()) {
                log(TAG, Logging.Priority.ERROR) { "Restore failed; session remains persisted" }
                hooks.notifyFailure(writeFailed = true)
                hooks.clearPendingTarget()
                return Outcome.RESTORE_FAILED
            }
        }

        val startedAt = hooks.elapsedRealtime()
        var lastWriteAt = startedAt
        var rewrites = 0
        while (true) {
            hooks.tick()
            val intended = hooks.intendedTarget()
            if (intended != null && intended != target && intended != staleIntended) {
                // The user (or another Amply entry point) chose a different policy while we
                // were converging; never write the boot target over a newer choice.
                log(TAG) { "Boot recovery superseded by ${intended.stableId}" }
                hooks.clearPendingTarget()
                return Outcome.SUPERSEDED
            }
            val now = hooks.elapsedRealtime()
            val snapshot = hooks.batterySnapshot()
            val observation = snapshot?.let { hooks.hardwareObservation(it) }
            val decision = BootRecoveryEngine.decide(
                target = target,
                plugged = snapshot?.plugged ?: false,
                percent = snapshot?.percent ?: -1,
                observation = observation,
                sinceLastWriteMillis = now - lastWriteAt,
                totalElapsedMillis = now - startedAt,
                rewriteCount = rewrites,
            )
            when (decision) {
                RecoveryDecision.WAIT -> Unit
                RecoveryDecision.REWRITE -> {
                    rewrites++
                    log(TAG) { "Boot recovery: re-writing ${target.stableId} (attempt $rewrites)" }
                    if (!hooks.rewrite(target)) {
                        // Keep the pending target: a re-write failure (lost access, partial
                        // write) should be retried by the next service start.
                        log(TAG, Logging.Priority.ERROR) { "Boot recovery re-write failed" }
                        hooks.notifyFailure(writeFailed = true)
                        return Outcome.GAVE_UP
                    }
                    lastWriteAt = hooks.elapsedRealtime()
                }
                RecoveryDecision.DONE_OK -> {
                    log(TAG) { "Boot recovery finished for ${target.stableId}" }
                    hooks.clearPendingTarget()
                    return Outcome.CONVERGED
                }
                RecoveryDecision.GIVE_UP -> {
                    log(TAG, Logging.Priority.ERROR) {
                        "Boot recovery: hardware did not converge to ${target.stableId}"
                    }
                    hooks.notifyFailure(writeFailed = false)
                    hooks.clearPendingTarget()
                    return Outcome.GAVE_UP
                }
            }
        }
    }

    companion object {
        val TAG = logTag("BootRecoveryFlow")
    }
}
