package eu.darken.amply.fullcharge.core

import eu.darken.amply.charging.core.ApplyResult
import eu.darken.amply.charging.core.ChargeObservation
import eu.darken.amply.charging.core.ChargePolicy
import eu.darken.amply.charging.core.ChargingRepository
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
 *
 * A resumed target carries its [RecoveryOrigin], which travels with every re-write: not everything
 * that ends up pending is a restore the user is already owed, and only those may bypass the
 * enforcement evidence gate (see [writeRecoveryTarget]).
 */
class BootRecoveryFlow(private val hooks: Hooks) {

    /** A persisted recovery target plus WHY it is owed — see [RecoveryOrigin]. */
    data class PendingRecovery(
        val target: ChargePolicy,
        val origin: RecoveryOrigin,
    )

    interface Hooks {
        suspend fun currentSessionTarget(): ChargePolicy?
        suspend fun pendingTarget(): PendingRecovery?

        /** Persists a target seeded from the session being recovered, i.e. [RecoveryOrigin.SESSION_RESTORE]. */
        suspend fun setPendingTarget(policy: ChargePolicy)
        suspend fun clearPendingTarget()
        suspend fun restoreSession(): Boolean

        /** Drops a session record that a newer pending target has made stale, without restoring it. */
        suspend fun dropStaleSession()

        /**
         * Re-write [policy]. [origin] decides the write path: an owed restore bypasses the
         * enforcement evidence tier, a user-requested policy must not.
         */
        suspend fun rewrite(policy: ChargePolicy, origin: RecoveryOrigin): Boolean

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

    /**
     * Outcome plus the facts an interruption assessor needs to decide whether the flow actually did
     * restore work: [restoreAttempted] (the flow called [Hooks.restoreSession]), [rewrites] (re-write
     * attempts issued, including a failed final one), and [retryRemaining] (a pending target is still
     * persisted on exit, so a later start will retry).
     */
    data class Result(
        val outcome: Outcome,
        val restoreAttempted: Boolean,
        val rewrites: Int,
        val retryRemaining: Boolean,
    )

    suspend fun run(): Result {
        var restoreAttempted = false
        var rewrites = 0
        val sessionTarget = hooks.currentSessionTarget()
        val pendingTarget = hooks.pendingTarget()
        val target = pendingTarget?.target ?: sessionTarget ?: return Result(
            outcome = Outcome.NOTHING_TO_DO,
            restoreAttempted = false,
            rewrites = 0,
            retryRemaining = false,
        )
        // A target seeded from the session below is by definition an owed restore; a persisted one
        // carries the origin of whoever created it.
        val origin = pendingTarget?.origin ?: RecoveryOrigin.SESSION_RESTORE
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
            restoreAttempted = true
            if (!hooks.restoreSession()) {
                log(TAG, Logging.Priority.ERROR) { "Restore failed; session remains persisted" }
                hooks.notifyFailure(writeFailed = true)
                hooks.clearPendingTarget()
                return Result(
                    outcome = Outcome.RESTORE_FAILED,
                    restoreAttempted = true,
                    rewrites = rewrites,
                    retryRemaining = false,
                )
            }
        }

        val startedAt = hooks.elapsedRealtime()
        var lastWriteAt = startedAt
        while (true) {
            hooks.tick()
            val intended = hooks.intendedTarget()
            if (intended != null && intended != target && intended != staleIntended) {
                // The user (or another Amply entry point) chose a different policy while we
                // were converging; never write the boot target over a newer choice.
                log(TAG) { "Boot recovery superseded by ${intended.stableId}" }
                hooks.clearPendingTarget()
                return Result(
                    outcome = Outcome.SUPERSEDED,
                    restoreAttempted = restoreAttempted,
                    rewrites = rewrites,
                    retryRemaining = false,
                )
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
                    log(TAG) { "Boot recovery: re-writing ${target.stableId} as $origin (attempt $rewrites)" }
                    if (!hooks.rewrite(target, origin)) {
                        // Keep the pending target: a re-write failure (lost access, partial
                        // write) should be retried by the next service start.
                        log(TAG, Logging.Priority.ERROR) { "Boot recovery re-write failed" }
                        hooks.notifyFailure(writeFailed = true)
                        return Result(
                            outcome = Outcome.GAVE_UP,
                            restoreAttempted = restoreAttempted,
                            rewrites = rewrites,
                            retryRemaining = true,
                        )
                    }
                    lastWriteAt = hooks.elapsedRealtime()
                }
                RecoveryDecision.DONE_OK -> {
                    log(TAG) { "Boot recovery finished for ${target.stableId}" }
                    hooks.clearPendingTarget()
                    return Result(
                        outcome = Outcome.CONVERGED,
                        restoreAttempted = restoreAttempted,
                        rewrites = rewrites,
                        retryRemaining = false,
                    )
                }
                RecoveryDecision.GIVE_UP -> {
                    log(TAG, Logging.Priority.ERROR) {
                        "Boot recovery: hardware did not converge to ${target.stableId}"
                    }
                    hooks.notifyFailure(writeFailed = false)
                    hooks.clearPendingTarget()
                    return Result(
                        outcome = Outcome.GAVE_UP,
                        restoreAttempted = restoreAttempted,
                        rewrites = rewrites,
                        retryRemaining = false,
                    )
                }
            }
        }
    }

    companion object {
        val TAG = logTag("BootRecoveryFlow")
    }
}

/**
 * Write a recovery target the way its [RecoveryOrigin] demands.
 *
 * A [RecoveryOrigin.SESSION_RESTORE] repays a protective policy the user already had, so it takes
 * the ungated [ChargingRepository.restorePersistent] — an OTA mid-session changes the build identity
 * and would otherwise leave the owed write refused, stranding the device Unrestricted. A
 * [RecoveryOrigin.USER_REQUEST] is a *fresh* choice that merely happens to be pending (the widget's
 * persistent-policy write persists its target before the risky write), so it stays on the gated
 * [ChargingRepository.reapplyPersistent]: a build that became a candidate or was refuted in the
 * meantime must not receive a new user write, least of all `Unrestricted`.
 */
internal suspend fun ChargingRepository.writeRecoveryTarget(
    policy: ChargePolicy,
    origin: RecoveryOrigin,
): ApplyResult = when (origin) {
    RecoveryOrigin.SESSION_RESTORE -> restorePersistent(policy, forceNotify = true)
    RecoveryOrigin.USER_REQUEST -> reapplyPersistent(policy)
}
