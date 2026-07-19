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
            .setSmallIcon(R.drawable.ic_amply)
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
                R.drawable.ic_amply,
                context.getString(R.string.session_notification_restore),
                restorePendingIntent,
            )
            .build()
    }

    fun gesture(context: Context, armed: Boolean): Notification {
        ensureChannels(context)
        val openPendingIntent = PendingIntent.getActivity(
            context,
            4,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(context, GESTURE_CHANNEL)
            .setSmallIcon(R.drawable.ic_amply)
            .setContentTitle(context.getString(R.string.gesture_notification_title))
            .setContentText(
                context.getString(
                    if (armed) R.string.gesture_notification_armed
                    else R.string.gesture_notification_waiting,
                ),
            )
            .setContentIntent(openPendingIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            // Show at once instead of the ~10s foreground-service deferral, so the armed
            // "reconnect now" cue is visible within its 10-second window.
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
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
            .setSmallIcon(R.drawable.ic_amply)
            .setContentTitle(context.getString(R.string.recovering_notification_title))
            .setContentText(context.getString(R.string.recovering_notification_body))
            .setContentIntent(openPendingIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

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
                    .setSmallIcon(R.drawable.ic_amply)
                    .setContentTitle(context.getString(R.string.recovery_notification_title))
                    .setContentText(context.getString(bodyRes))
                    .setContentIntent(openIntent)
                    .setAutoCancel(true)
                    .build(),
            )
        }
    }
}
