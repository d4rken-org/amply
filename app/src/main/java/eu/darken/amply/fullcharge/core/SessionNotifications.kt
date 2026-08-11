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
import eu.darken.amply.charging.core.ChargePolicy
import eu.darken.amply.main.ui.MainActivity

object SessionNotifications {
    const val SESSION_ID = 4101
    private const val RECOVERY_ID = 4102
    // Channel ids are invisible to the user and permanent — changing one resets that channel's
    // settings — so this keeps its original id while its display name has moved on.
    private const val SESSION_CHANNEL = "temporary_full_charge"
    private const val GESTURE_CHANNEL = "reconnect_gesture"
    private const val RECOVERY_CHANNEL = "charge_policy_recovery"
    private const val MONITOR_CHANNEL = "background_monitor"

    fun ensureChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        // Covers the whole temporary-override lifecycle — lifting the limit and putting it back —
        // because [recovering] shares it with [session]. Unlike importance, a channel's name and
        // description do update on an existing channel, so widening the wording reaches installs
        // that already have it.
        manager.createNotificationChannel(
            NotificationChannel(
                SESSION_CHANNEL,
                context.getString(R.string.session_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = context.getString(R.string.session_channel_description)
                setShowBadge(false)
            },
        )
        // Its own channel, kept separate from the session so the user can silence one without the
        // other, but LOW: the gesture notification is a passive standing cue that can sit there for
        // hours, so it belongs in the shade's silent section. DEFAULT bought nothing anyway — all
        // notifications here share SESSION_ID and set onlyAlertOnce, so the reconnect countdown
        // never re-alerted; the importance only produced one ding when the service went foreground.
        // LOW still keeps a status-bar icon, which is what makes an armed gesture discoverable.
        // Importance is fixed once a channel exists, so this only takes effect on a fresh install —
        // deliberately not migrated with a new channel id while the app is pre-launch.
        manager.createNotificationChannel(
            NotificationChannel(
                GESTURE_CHANNEL,
                context.getString(R.string.gesture_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = context.getString(R.string.gesture_channel_description)
                setShowBadge(false)
            },
        )
        // HIGH, matching the charge alarm: this fires when the protective policy could NOT be
        // restored, so the battery charges unprotected until the user intervenes — the exact
        // failure the app exists to prevent, and one that otherwise goes unnoticed overnight. It
        // would be backwards for a convenience reminder to out-rank it. Rare, auto-cancelling, and
        // withdrawn the moment a restore succeeds, so the heads-up costs nothing when all is well.
        manager.createNotificationChannel(
            NotificationChannel(
                RECOVERY_CHANNEL,
                context.getString(R.string.recovery_channel_name),
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = context.getString(R.string.recovery_channel_description)
            },
        )
        // Quiet channel for the "alive only to observe battery" case (e.g. charge alarm). Separate
        // from the gesture channel despite sharing LOW, so silencing the watcher's presence entirely
        // does not also hide the gesture that the user opted into.
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

    /**
     * [protectPolicy] is the adapter's declared protective default and has no default value on
     * purpose: the action writes it persistently, so the capability has to be handed in by the
     * caller that resolved the adapter rather than guessed here.
     */
    fun gesture(
        context: Context,
        decision: QuickFullChargeDecision,
        protectPolicy: ChargePolicy,
        anyLevel: Boolean = false,
        limitPercent: Int? = null,
    ): Notification {
        ensureChannels(context)
        val openPendingIntent = PendingIntent.getActivity(
            context,
            4,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        // Three distinct states, not two. WAITING_FOR_RECONNECT is the time-critical countdown and
        // stays a bare instruction — how the gesture armed is history by then. ARMED is what a
        // plugged-in user actually sees, for hours, so that is where the condition the gesture will
        // fire under (any level vs. a named limit vs. a generic hold) has to be spelled out — the
        // caller passes that condition, not whichever basis happened to latch first. IDLE keeps the
        // passive "waiting" copy, which explains the enabled mode rather than the current basis.
        val contentText = when (decision) {
            QuickFullChargeDecision.WAITING_FOR_RECONNECT -> context.getString(
                R.string.gesture_notification_armed,
            )
            QuickFullChargeDecision.ARMED -> when {
                anyLevel -> context.getString(R.string.gesture_notification_armed_any_level)
                limitPercent != null -> context.getString(
                    R.string.gesture_notification_armed_limit,
                    limitPercent,
                )
                else -> context.getString(R.string.gesture_notification_armed_holding)
            }
            else -> when {
                anyLevel -> context.getString(R.string.gesture_notification_waiting_any_level)
                limitPercent != null -> context.getString(
                    R.string.gesture_notification_waiting_limit,
                    limitPercent,
                )
                else -> context.getString(R.string.gesture_notification_waiting)
            }
        }
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
        // The steady-state notification carries the "you can turn this off" hint in its expanded
        // view — kept out of the collapsed line so it stays short, and off the reconnect countdown
        // so the time-sensitive instruction isn't diluted. ARMED can persist for hours, so it gets
        // the hint like IDLE does.
        if (decision != QuickFullChargeDecision.WAITING_FOR_RECONNECT) {
            builder.setStyle(
                NotificationCompat.BigTextStyle().bigText(
                    contentText + "\n\n" + context.getString(R.string.gesture_notification_disable_hint),
                ),
            )
        }
        // Mode switches only on the two standing states. An explicit allowlist, not "not
        // WAITING_FOR_RECONNECT": TRIGGER also reaches this builder, and neither the 10s countdown
        // nor the tick that starts a full charge should offer a competing persistent write.
        if (decision == QuickFullChargeDecision.IDLE || decision == QuickFullChargeDecision.ARMED) {
            builder
                .addAction(
                    R.drawable.ic_launcher_monochrome,
                    protectActionLabel(context, protectPolicy),
                    persistentPolicyIntent(context, 8, protectPolicy),
                )
                .addAction(
                    R.drawable.ic_launcher_monochrome,
                    context.getString(R.string.gesture_notification_action_always_full),
                    persistentPolicyIntent(context, 9, ChargePolicy.Unrestricted),
                )
        }
        return builder.build()
    }

    /** Mirrors the widget's protect-button naming; other policies are not protective defaults today. */
    private fun protectActionLabel(context: Context, policy: ChargePolicy): String = when (policy) {
        is ChargePolicy.FixedLimit -> context.getString(
            R.string.gesture_notification_action_protect_fixed,
            policy.percent,
        )
        ChargePolicy.Adaptive -> context.getString(R.string.gesture_notification_action_protect_adaptive)
        else -> context.getString(R.string.gesture_notification_action_protect)
    }

    /**
     * Both actions share [ChargeSessionService.ACTION_SET_PERSISTENT_POLICY] and differ only in an
     * extra, which does NOT factor into PendingIntent equality — hence the distinct [requestCode]
     * per action, or the second would overwrite the first's target.
     */
    private fun persistentPolicyIntent(
        context: Context,
        requestCode: Int,
        policy: ChargePolicy,
    ): PendingIntent = PendingIntent.getForegroundService(
        context,
        requestCode,
        Intent(context, ChargeSessionService::class.java)
            .setAction(ChargeSessionService.ACTION_SET_PERSISTENT_POLICY)
            .putExtra(ChargeSessionService.EXTRA_TARGET_POLICY, policy.stableId),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    /**
     * Progress while a restore converges on the charging hardware. Deliberately shares
     * [SESSION_CHANNEL] with [session] rather than the alerting recovery channel: this is the
     * passive end of the same override the user started, and the recovery channel is HIGH, which
     * would make it heads-up on every boot that owes a restore.
     */
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

    /** Cancel the "charge limit needs attention" notification once a later restore/convergence succeeds. */
    fun cancelRecovery(context: Context) {
        NotificationManagerCompat.from(context).cancel(RECOVERY_ID)
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
                    .setSmallIcon(R.drawable.ic_launcher_monochrome)
                    .setContentTitle(context.getString(R.string.recovery_notification_title))
                    .setContentText(context.getString(bodyRes))
                    .setContentIntent(openIntent)
                    // The only notification here reporting a failed state, so it is the only one
                    // that should rank and filter as an error rather than as service noise.
                    .setCategory(NotificationCompat.CATEGORY_ERROR)
                    .setAutoCancel(true)
                    .build(),
            )
        }
    }
}
