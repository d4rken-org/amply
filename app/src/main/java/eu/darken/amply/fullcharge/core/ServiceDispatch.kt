package eu.darken.amply.fullcharge.core

import android.content.Context
import android.content.Intent

/**
 * Pure dispatch logic for when and how [ChargeSessionService] is started outside of a direct user
 * action: at boot and when the app returns to the foreground.
 */
object ServiceDispatch {

    enum class Trigger { BOOT, FOREGROUND }

    /**
     * The service action a trigger should start, or null when there is no work (never start a
     * foreground service that would immediately decide to stop itself).
     *
     * At boot a persisted session is stale by definition, so recovery restores it right away. A
     * foreground launch may instead find the service alive with a healthy running session —
     * [ChargeSessionService.ACTION_CHECK] reconciles against live state instead of restoring
     * over it (which [ChargeSessionService.ACTION_RECOVER] would).
     */
    fun startAction(
        trigger: Trigger,
        sessionExists: Boolean,
        pendingRecovery: Boolean,
        gestureEnabled: Boolean,
    ): String? = when (trigger) {
        Trigger.BOOT -> when {
            sessionExists || pendingRecovery -> ChargeSessionService.ACTION_RECOVER
            gestureEnabled -> ChargeSessionService.ACTION_MONITOR
            else -> null
        }
        Trigger.FOREGROUND -> when {
            sessionExists || pendingRecovery || gestureEnabled -> ChargeSessionService.ACTION_CHECK
            else -> null
        }
    }

    enum class CheckResolution {
        ALREADY_RECOVERING,
        START_RECOVERY,
        RESUME_SESSION,
        MONITOR_OR_STOP,
    }

    /**
     * Reconciliation for [ChargeSessionService.ACTION_CHECK] and sticky-restart (null-action)
     * starts. A pending recovery target outranks a session record: when both exist the pending
     * target is the newer intent, and [BootRecoveryFlow] drops the stale session without
     * restoring it.
     */
    fun resolveCheck(
        recoveryActive: Boolean,
        pendingRecovery: Boolean,
        sessionExists: Boolean,
    ): CheckResolution = when {
        recoveryActive -> CheckResolution.ALREADY_RECOVERING
        pendingRecovery -> CheckResolution.START_RECOVERY
        sessionExists -> CheckResolution.RESUME_SESSION
        else -> CheckResolution.MONITOR_OR_STOP
    }

    fun startIntent(context: Context, action: String): Intent =
        Intent(context, ChargeSessionService::class.java).setAction(action)
}
