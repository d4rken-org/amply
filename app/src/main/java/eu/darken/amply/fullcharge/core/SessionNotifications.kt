package eu.darken.amply.fullcharge.core

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import android.content.pm.PackageManager
import android.os.Build
import eu.darken.amply.R
import eu.darken.amply.main.ui.MainActivity

object SessionNotifications {
    const val SESSION_ID = 4101
    private const val RECOVERY_ID = 4102
    private const val SESSION_CHANNEL = "temporary_full_charge"
    private const val GESTURE_CHANNEL = "reconnect_gesture"
    private const val RECOVERY_CHANNEL = "charge_policy_recovery"
    private const val MONITOR_CHANNEL = "background_monitor"

    fun ensureChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                SESSION_CHANNEL,
                context.getString(R.string.session_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Shows while Amply temporarily allows charging to 100%"
                setShowBadge(false)
            },
        )
        // Its own channel at DEFAULT importance so the persistent reconnect monitor is
        // reliably visible in the status bar (the shared low channel was easy to miss).
        manager.createNotificationChannel(
            NotificationChannel(
                GESTURE_CHANNEL,
                context.getString(R.string.gesture_channel_name),
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = "Shows while the reconnect-for-100% gesture is watching for a replug"
                setShowBadge(false)
            },
        )
        manager.createNotificationChannel(
            NotificationChannel(
                RECOVERY_CHANNEL,
                context.getString(R.string.recovery_channel_name),
                NotificationManager.IMPORTANCE_DEFAULT,
            ),
        )
        // Quiet channel for the "alive only to observe battery" case (e.g. charge alarm). LOW so the
        // background foreground-service notification doesn't draw attention like the gesture cue.
        manager.createNotificationChannel(
            NotificationChannel(
                MONITOR_CHANNEL,
                context.getString(R.string.monitor_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = context.getString(R.string.monitor_channel_description)
                setShowBadge(false)
            },
        )
    }

    /** Ongoing, quiet notification shown while the service stays alive only for a watcher. */
    fun monitoring(context: Context): Notification {
        ensureChannels(context)
        val openPendingIntent = PendingIntent.getActivity(
            context,
            7,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(context, MONITOR_CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle(context.getString(R.string.monitor_notification_title))
            .setContentText(context.getString(R.string.monitor_notification_body))
            .setContentIntent(openPendingIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    fun session(context: Context, connected: Boolean): Notification {
        ensureChannels(context)
        val restoreIntent = Intent(context, ChargeSessionService::class.java).apply {
            action = ChargeSessionService.ACTION_RESTORE
        }
        val restorePendingIntent = PendingIntent.getService(
            context,
            1,
            restoreIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val openPendingIntent = PendingIntent.getActivity(
            context,
            2,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(context, SESSION_CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle(context.getString(R.string.session_notification_title))
            .setContentText(
                context.getString(
                    if (connected) R.string.session_notification_active
                    else R.string.session_notification_armed,
                ),
            )
            .setContentIntent(openPendingIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .addAction(
                R.drawable.ic_launcher_monochrome,
                context.getString(R.string.session_notification_restore),
                restorePendingIntent,
            )
            .build()
    }

    fun gesture(context: Context, armed: Boolean, anyLevel: Boolean = false): Notification {
        ensureChannels(context)
        val openPendingIntent = PendingIntent.getActivity(
            context,
            4,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        // Armed copy is the same regardless of arming basis; only the passive "waiting" copy
        // distinguishes any-level from limit-hold.
        val contentText = context.getString(
            when {
                armed -> R.string.gesture_notification_armed
                anyLevel -> R.string.gesture_notification_waiting_any_level
                else -> R.string.gesture_notification_waiting
            },
        )
        val builder = NotificationCompat.Builder(context, GESTURE_CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle(context.getString(R.string.gesture_notification_title))
            .setContentText(contentText)
            .setContentIntent(openPendingIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            // Show at once instead of the ~10s foreground-service deferral, so the armed
            // "reconnect now" cue is visible within its 10-second window.
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        // The persistent waiting notification carries the "you can turn this off" hint in its
        // expanded view — kept out of the collapsed line so it stays short, and off the armed
        // cue so the time-sensitive reconnect instruction isn't diluted.
        if (!armed) {
            builder.setStyle(
                NotificationCompat.BigTextStyle().bigText(
                    contentText + "\n\n" + context.getString(R.string.gesture_notification_disable_hint),
                ),
            )
        }
        return builder.build()
    }

    fun recovering(context: Context): Notification {
        ensureChannels(context)
        val openPendingIntent = PendingIntent.getActivity(
            context,
            5,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(context, SESSION_CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle(context.getString(R.string.recovering_notification_title))
            .setContentText(context.getString(R.string.recovering_notification_body))
            .setContentIntent(openPendingIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    // TODO known gap: this notification (RECOVERY_ID) is not cancelled when a later restore succeeds;
    // it lingers until swiped. Should cancel on successful restore. See "Known gaps" in
    // .claude/rules/privileged-access.md.
    fun showRecovery(context: Context, bodyRes: Int = R.string.recovery_notification_body) {
        ensureChannels(context)
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) != PackageManager.PERMISSION_GRANTED
        ) return
        val openIntent = PendingIntent.getActivity(
            context,
            3,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        runCatching {
            NotificationManagerCompat.from(context).notify(
                RECOVERY_ID,
                NotificationCompat.Builder(context, RECOVERY_CHANNEL)
                    .setSmallIcon(R.drawable.ic_launcher_monochrome)
                    .setContentTitle(context.getString(R.string.recovery_notification_title))
                    .setContentText(context.getString(bodyRes))
                    .setContentIntent(openIntent)
                    .setAutoCancel(true)
                    .build(),
            )
        }
    }
}
