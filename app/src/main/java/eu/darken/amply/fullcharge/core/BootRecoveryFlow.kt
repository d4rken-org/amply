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
 * Boot-time restore orchestration: restore the persisted protective policy, then drive
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
        suspend fun rewrite(policy: ChargePolicy): Boolean

        /** The policy Amply itself was most recently asked to configure, if any. */
        suspend fun intendedTarget(): ChargePolicy?
        fun batterySnapshot(): BatterySnapshot?
        fun hardwareObservation(snapshot: BatterySnapshot): ChargeObservation?

        /** Configured-settings readback, non-null only for synchronously verifiable adapters. */
        suspend fun settingsObservation(): ChargeObservation? = null

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
        val target = sessionTarget ?: hooks.pendingTarget() ?: return Outcome.NOTHING_TO_DO
        hooks.setPendingTarget(target)
        if (sessionTarget != null) {
            log(TAG) { "Boot recovery: restoring ${target.stableId}" }
            if (!hooks.restoreSession()) {
                log(TAG, Logging.Priority.ERROR) { "Boot restore failed; session remains persisted" }
                hooks.notifyFailure(writeFailed = true)
                hooks.clearPendingTarget()
                return Outcome.RESTORE_FAILED
            }
        } else {
            log(TAG) { "Boot recovery: resuming convergence check for ${target.stableId}" }
        }

        val startedAt = hooks.elapsedRealtime()
        var lastWriteAt = startedAt
        var rewrites = 0
        while (true) {
            hooks.tick()
            val intended = hooks.intendedTarget()
            if (intended != null && intended != target) {
                // The user (or another Amply entry point) chose a different policy while we
                // were converging; never write the boot target over a newer choice.
                log(TAG) { "Boot recovery superseded by ${intended.stableId}" }
                hooks.clearPendingTarget()
                return Outcome.SUPERSEDED
            }
            val now = hooks.elapsedRealtime()
            val snapshot = hooks.batterySnapshot()
            val observation = snapshot?.let { hooks.hardwareObservation(it) }
            val settingsRead = hooks.settingsObservation()
            val decision = BootRecoveryEngine.decide(
                target = target,
                plugged = snapshot?.plugged ?: false,
                percent = snapshot?.percent ?: -1,
                observation = observation,
                sinceLastWriteMillis = now - lastWriteAt,
                totalElapsedMillis = now - startedAt,
                rewriteCount = rewrites,
                settingsConfirmsTarget = (settingsRead as? ChargeObservation.Verified)?.policy == target,
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

        fun bootAction(sessionExists: Boolean, pendingRecovery: Boolean, gestureEnabled: Boolean): String? = when {
            sessionExists || pendingRecovery -> ChargeSessionService.ACTION_RECOVER
            gestureEnabled -> ChargeSessionService.ACTION_MONITOR
            else -> null
        }
    }
}
