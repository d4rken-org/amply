package eu.darken.amply.main.ui.widget

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.annotation.Keep
import androidx.core.content.ContextCompat
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.action.ActionParameters
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.color.ColorProvider
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.Button
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import eu.darken.amply.charging.core.ChargePolicy
import eu.darken.amply.charging.core.ChargingPreferences
import eu.darken.amply.charging.core.ChargingRepository
import eu.darken.amply.charging.core.ChargingState
import eu.darken.amply.charging.core.isSettling
import eu.darken.amply.charging.core.settlingTarget
import eu.darken.amply.fullcharge.core.FullChargeStore
import eu.darken.amply.fullcharge.core.ChargeSessionService
import eu.darken.amply.fullcharge.core.policyOrNull
import eu.darken.amply.main.ui.MainActivity

@Keep
class AmplyWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val entryPoint = EntryPointAccessors.fromApplication(
            context.applicationContext,
            AmplyWidgetEntryPoint::class.java,
        )
        // Refresh on every render (with a last-good fallback): the widget process can be cold and the
        // in-memory state stale, and native Settings changes are only observed while the app/service runs.
        val repo = entryPoint.chargingRepository()
        val state = runCatching { repo.refresh() }.getOrElse { repo.state.value }
        val sessionActive = entryPoint.sessionStore().currentSession() != null
        val requestedTarget = runCatching { entryPoint.chargingPreferences().lastRequestedNow() }.getOrNull()
        val settling = state.isSettling(System.currentTimeMillis())
        val status = statusLine(sessionActive, settling, state, requestedTarget)

        provideContent {
            Column(
                modifier = GlanceModifier
                    .fillMaxSize()
                    .background(ColorProvider(Color(0xFFE5F4ED), Color(0xFF18352A)))
                    .clickable(
                        actionStartActivity(
                            Intent(context, MainActivity::class.java)
                                .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                        ),
                    )
                    .padding(12.dp),
                verticalAlignment = Alignment.Vertical.CenterVertically,
            ) {
                Text(
                    text = status,
                    style = TextStyle(
                        color = ColorProvider(Color(0xFF15382B), Color(0xFFE4F5EC)),
                        fontWeight = FontWeight.Bold,
                    ),
                )
                Row(modifier = GlanceModifier.fillMaxWidth().padding(top = 10.dp)) {
                    Button(
                        text = "∞ 80%",
                        onClick = actionRunCallback<ProtectAction>(),
                        modifier = GlanceModifier.defaultWeight(),
                    )
                    Spacer(GlanceModifier.width(8.dp))
                    Button(
                        text = "∞ 100%",
                        onClick = actionRunCallback<AlwaysFullAction>(),
                        modifier = GlanceModifier.defaultWeight(),
                    )
                    Spacer(GlanceModifier.width(8.dp))
                    Button(
                        text = "1× 100%",
                        onClick = actionRunCallback<FullChargeAction>(),
                        modifier = GlanceModifier.defaultWeight(),
                    )
                }
            }
        }
    }
}

/** Human-readable widget status derived from the requested target (observation alone degrades to Unknown on WSS-only). */
private fun statusLine(
    sessionActive: Boolean,
    settling: Boolean,
    state: ChargingState,
    requestedTarget: ChargePolicy?,
): String {
    if (sessionActive) return if (settling) "Charging to 100% · waiting…" else "Charging to 100% once"
    if (settling) return "${widgetLabel(state.settlingTarget() ?: requestedTarget)} · waiting for system…"
    return widgetLabel(state.observation.policyOrNull() ?: requestedTarget)
}

private fun widgetLabel(policy: ChargePolicy?): String = when (policy) {
    is ChargePolicy.FixedLimit -> "Limited to ${policy.percent}%"
    ChargePolicy.Unrestricted -> "Unlimited (100%)"
    ChargePolicy.Adaptive -> "Adaptive charging"
    null -> "Tap to set a limit"
}

@Keep
class AmplyWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = AmplyWidget()
}

@EntryPoint
@InstallIn(SingletonComponent::class)
interface AmplyWidgetEntryPoint {
    fun sessionStore(): FullChargeStore
    fun chargingRepository(): ChargingRepository
    fun chargingPreferences(): ChargingPreferences
}

/** "∞ 80%" — set a persistent 80% limit (ends any one-time session and applies 80% atomically). */
@Keep
class ProtectAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        setPersistentOrOpen(context, ChargePolicy.FixedLimit(80))
        AmplyWidget().updateAll(context)
    }
}

/** "∞ 100%" — always charge to 100% (persistent Unrestricted). */
@Keep
class AlwaysFullAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        setPersistentOrOpen(context, ChargePolicy.Unrestricted)
        AmplyWidget().updateAll(context)
    }
}

/** "1× 100%" — charge fully once, then restore the protective policy. */
@Keep
class FullChargeAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val entryPoint = EntryPointAccessors.fromApplication(
            context.applicationContext,
            AmplyWidgetEntryPoint::class.java,
        )
        if (entryPoint.sessionStore().currentSession() != null) {
            // Already charging once → tapping again cancels and restores the protective policy.
            startService(context, ChargeSessionService.ACTION_RESTORE)
        } else if (!canShowNotifications(context)) {
            openApp(context, true)
        } else {
            val state = entryPoint.chargingRepository().refresh()
            if (!state.controlEnabled || state.access?.canControl != true) {
                openApp(context, false)
                AmplyWidget().updateAll(context)
                return
            }
            // A once-session is meaningless when the persistent policy is already Unrestricted (it would
            // later "restore" down to the protective baseline). Prefer the current observation and only fall
            // back to the last request when nothing can be observed, so a native change away from unrestricted
            // still lets the once-session start.
            val currentPolicy = state.observation.policyOrNull()
                ?: entryPoint.chargingPreferences().lastRequestedNow()
            if (currentPolicy == ChargePolicy.Unrestricted) {
                openApp(context, false)
                AmplyWidget().updateAll(context)
                return
            }
            startService(context, ChargeSessionService.ACTION_START)
        }
        AmplyWidget().updateAll(context)
    }
}

/**
 * Route a persistent-policy change through the service (serialized, cancels sessions, force-writes so a
 * same-value write still re-triggers the HAL), or open the app when charging can't be controlled.
 */
private suspend fun setPersistentOrOpen(context: Context, policy: ChargePolicy) {
    val entryPoint = EntryPointAccessors.fromApplication(
        context.applicationContext,
        AmplyWidgetEntryPoint::class.java,
    )
    val state = entryPoint.chargingRepository().refresh()
    if (!state.controlEnabled || state.access?.canControl != true) {
        openApp(context, false)
        return
    }
    ContextCompat.startForegroundService(
        context,
        Intent(context, ChargeSessionService::class.java)
            .setAction(ChargeSessionService.ACTION_SET_PERSISTENT_POLICY)
            .putExtra(ChargeSessionService.EXTRA_TARGET_POLICY, policy.stableId),
    )
}

private fun startService(context: Context, action: String) {
    ContextCompat.startForegroundService(
        context,
        Intent(context, ChargeSessionService::class.java).setAction(action),
    )
}

private fun canShowNotifications(context: Context): Boolean = Build.VERSION.SDK_INT < 33 ||
    ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
    PackageManager.PERMISSION_GRANTED

private fun openApp(context: Context, requestNotifications: Boolean) {
    context.startActivity(
        Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(MainActivity.EXTRA_REQUEST_NOTIFICATIONS, requestNotifications)
        },
    )
}
