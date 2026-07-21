package eu.darken.amply.alarm.core

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import eu.darken.amply.R
import eu.darken.amply.main.ui.MainActivity

/**
 * The charge-alarm alert: a single high-importance, auto-cancelling notification telling the user
 * to unplug. Its own channel at [NotificationManager.IMPORTANCE_HIGH] so the system applies its
 * default sound/vibration (channel vibration needs no VIBRATE permission on API 26+; the user, the
 * channel, or DND can still mute it, so copy never promises an alert tone).
 */
object ChargeAlarmNotifications {
    private const val CHANNEL = "charge_alarm"
    private const val ALARM_ID = 4103

    fun ensureChannel(context: Context) {
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(
                CHANNEL,
                context.getString(R.string.alarm_channel_name),
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = context.getString(R.string.alarm_channel_description)
                enableVibration(true)
            },
        )
    }

    /**
     * Whether an alarm alert would actually reach the user right now: notifications enabled app-wide
     * AND the alarm channel not muted to [NotificationManager.IMPORTANCE_NONE]. Does not create the
     * channel — an absent channel counts as deliverable (it's created at the first real alert).
     */
    fun canDeliver(context: Context): Boolean {
        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled()) return false
        val channel = manager.getNotificationChannel(CHANNEL) ?: return true
        return channel.importance != NotificationManager.IMPORTANCE_NONE
    }

    fun show(context: Context, percent: Int) {
        ensureChannel(context)
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val openIntent = PendingIntent.getActivity(
            context,
            6,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        runCatching {
            NotificationManagerCompat.from(context).notify(
                ALARM_ID,
                NotificationCompat.Builder(context, CHANNEL)
                    .setSmallIcon(R.drawable.ic_launcher_monochrome)
                    .setContentTitle(context.getString(R.string.alarm_notification_title))
                    .setContentText(context.getString(R.string.alarm_notification_body, percent))
                    .setContentIntent(openIntent)
                    .setCategory(NotificationCompat.CATEGORY_REMINDER)
                    .setPriority(NotificationCompat.PRIORITY_HIGH)
                    .setAutoCancel(true)
                    .build(),
            )
        }
    }

    /** Remove a shown alarm — called on unplug and when the user disables the feature. */
    fun cancel(context: Context) {
        runCatching { NotificationManagerCompat.from(context).cancel(ALARM_ID) }
    }
}
