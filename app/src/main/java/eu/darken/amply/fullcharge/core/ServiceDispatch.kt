package eu.darken.amply.fullcharge.core

import android.content.Context
import android.content.Intent
import android.provider.Settings

/**
 * Pure dispatch logic for when and how [ChargeSessionService] is started outside of a direct user
 * action: at boot and when the app returns to the foreground.
 */
object ServiceDispatch {

    enum class Trigger { BOOT, FOREGROUND }

    /**
     * Which trigger semantics a BOOT_COMPLETED delivery should use. Android defers the broadcast
     * for stopped apps and re-delivers it when a force-stopped app next starts (observed on
     * Android 16 / Pixel 7a) — within the same boot that delivery races the foreground nudge and
     * must NOT restore over a live session. If Amply has already run during this boot, the
     * delivery is such a re-delivery and gets the reconciling [Trigger.FOREGROUND] semantics; a
     * genuinely new (or unknown) boot keeps the restoring [Trigger.BOOT] semantics.
     */
    fun bootTrigger(currentBootCount: Int?, lastSeenBootCount: Int?): Trigger = when {
        currentBootCount != null && currentBootCount == lastSeenBootCount -> Trigger.FOREGROUND
        else -> Trigger.BOOT
    }

    /** The system boot counter, or null where the setting is unavailable. */
    fun currentBootCount(context: Context): Int? = try {
        Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT)
    } catch (e: Settings.SettingNotFoundException) {
        null
    }

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
        watcherEnabled: Boolean = false,
    ): String? = when (trigger) {
        Trigger.BOOT -> when {
            sessionExists || pendingRecovery -> ChargeSessionService.ACTION_RECOVER
            gestureEnabled || watcherEnabled -> ChargeSessionService.ACTION_MONITOR
            else -> null
        }
        Trigger.FOREGROUND -> when {
            sessionExists || pendingRecovery || gestureEnabled || watcherEnabled ->
                ChargeSessionService.ACTION_CHECK
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
