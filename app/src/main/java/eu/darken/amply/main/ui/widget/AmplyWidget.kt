package eu.darken.amply.main.ui.widget

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.annotation.Keep
import androidx.core.content.ContextCompat
import androidx.glance.ColorFilter
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalSize
import androidx.glance.action.ActionParameters
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.color.ColorProvider
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.Button
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import eu.darken.amply.R
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
import kotlinx.coroutines.CancellationException

/** Below this width the brand mark + name is dropped so the status line stays readable. */
private val BRAND_MIN_WIDTH = 200.dp

private val TITLE_COLOR = ColorProvider(Color(0xFF123832), Color(0xFFE0F5F0))

@Keep
class AmplyWidget : GlanceAppWidget() {
    // Exact so LocalSize reports the actual widget size and we can show the brand only when it fits.
    override val sizeMode: SizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val entryPoint = EntryPointAccessors.fromApplication(
            context.applicationContext,
            AmplyWidgetEntryPoint::class.java,
        )
        val repo = entryPoint.chargingRepository()
        val sessionStore = entryPoint.sessionStore()
        val preferences = entryPoint.chargingPreferences()

        // Seed once, before provideContent: the widget process can be cold and the in-memory state stale, and
        // native Settings changes are only observed while the app/service runs. Doing this here (not in a
        // per-composition LaunchedEffect) avoids re-refreshing once per size under SizeMode.Exact and avoids
        // briefly flashing the empty initial ChargingState() before the refresh lands. Cancellation propagates.
        try {
            // Populate repo.state (collected below) so the first composition renders real values, not the
            // empty initial ChargingState().
            repo.refresh()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Cold read failed; the composition falls back to the last-known repo.state.value.
        }
        val initialSession = runCatching { sessionStore.currentSession() }.getOrNull()
        val initialRequested = runCatching { preferences.lastRequestedNow() }.getOrNull()

        provideContent {
            // Reactive composition: Glance never re-runs an already-active provideGlance, so a one-shot
            // pre-provideContent read alone would miss later backend changes and freeze the widget after a tap.
            // Observing the same reactive sources the rest of the app uses makes Glance recompose this content
            // in place whenever the backend emits. The StateFlow already reflects the seeded refresh above; the
            // two plain flows are seeded with the pre-read values so the first frame is never empty.
            val state by repo.state.collectAsState()
            val session by sessionStore.session.collectAsState(initial = initialSession)
            val requestedTarget by preferences.lastRequested.collectAsState(initial = initialRequested)

            val display = widgetDisplay(state, sessionActive = session != null, now = System.currentTimeMillis())
            val status = statusLine(context, display.sessionActive, display.settling, state, requestedTarget)
            val showBrand = display.steady && LocalSize.current.width >= BRAND_MIN_WIDTH
            val titleStyle = TextStyle(color = TITLE_COLOR, fontWeight = FontWeight.Bold)
            Column(
                modifier = GlanceModifier
                    .fillMaxSize()
                    .background(ColorProvider(Color(0xFFE1F5F0), Color(0xFF153531)))
                    .clickable(
                        actionStartActivity(
                            Intent(context, MainActivity::class.java)
                                .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                        ),
                    )
                    .padding(12.dp),
                verticalAlignment = Alignment.Vertical.CenterVertically,
            ) {
                Row(verticalAlignment = Alignment.Vertical.CenterVertically) {
                    if (showBrand) {
                        Image(
                            provider = ImageProvider(R.drawable.ic_launcher_monochrome),
                            contentDescription = null,
                            modifier = GlanceModifier.size(16.dp),
                            colorFilter = ColorFilter.tint(TITLE_COLOR),
                        )
                        Spacer(GlanceModifier.width(6.dp))
                    }
                    Text(
                        text = if (showBrand) context.getString(R.string.widget_brand_status, status) else status,
                        style = titleStyle,
                        maxLines = 1,
                    )
                }
                // Compact single-line labels: slightly smaller text, and maxLines=1 so the worst case
                // ellipsizes on one line instead of wrapping/clipping inside the button.
                val buttonText = TextStyle(fontSize = 12.sp)
                Row(modifier = GlanceModifier.fillMaxWidth().padding(top = 10.dp)) {
                    Button(
                        text = protectButtonLabel(context, state.defaultProtectivePolicy),
                        onClick = actionRunCallback<ProtectAction>(),
                        modifier = GlanceModifier.defaultWeight(),
                        style = buttonText,
                        maxLines = 1,
                    )
                    Spacer(GlanceModifier.width(6.dp))
                    Button(
                        text = context.getString(R.string.widget_button_always_full),
                        onClick = actionRunCallback<AlwaysFullAction>(),
                        modifier = GlanceModifier.defaultWeight(),
                        style = buttonText,
                        maxLines = 1,
                    )
                    Spacer(GlanceModifier.width(6.dp))
                    Button(
                        text = context.getString(R.string.widget_button_full_once),
                        onClick = actionRunCallback<FullChargeAction>(),
                        modifier = GlanceModifier.defaultWeight(),
                        style = buttonText,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

/**
 * Structural (context-free) widget display derivation, kept pure so the branches are JVM-unit-testable.
 * `sessionActive` wins over everything; `settling` is the pending-request window; `steady` (a plain resting
 * policy, nothing in flight) is the only state that shows the brand mark.
 */
internal data class WidgetDisplay(
    val sessionActive: Boolean,
    val settling: Boolean,
    val steady: Boolean,
)

internal fun widgetDisplay(state: ChargingState, sessionActive: Boolean, now: Long): WidgetDisplay {
    val settling = state.isSettling(now)
    return WidgetDisplay(
        sessionActive = sessionActive,
        settling = settling,
        steady = !sessionActive && !settling,
    )
}

/**
 * Human-readable widget status. A just-tapped change surfaces within ~0.4s as the service-written
 * "<target> · waiting for system…" cue — that is the tap acknowledgement (no separate optimistic phase,
 * which is unreliable given Glance's widget-session caching). Derived from the requested target because
 * observation alone degrades to Unknown on WSS-only.
 */
private fun statusLine(
    context: Context,
    sessionActive: Boolean,
    settling: Boolean,
    state: ChargingState,
    requestedTarget: ChargePolicy?,
): String {
    if (sessionActive) {
        return if (settling) {
            context.getString(R.string.widget_status_charging_waiting)
        } else {
            context.getString(R.string.widget_status_charging_once)
        }
    }
    if (settling) {
        return context.getString(
            R.string.widget_status_waiting_suffix,
            widgetLabel(context, state.settlingTarget() ?: requestedTarget),
        )
    }
    return widgetLabel(context, state.observation.policyOrNull() ?: requestedTarget)
}

/** "∞ <limit>" — the adapter's protective default, e.g. ∞80% on Pixel, ∞Auto on Xiaomi. */
internal fun protectButtonLabel(context: Context, policy: ChargePolicy?): String = when (policy) {
    is ChargePolicy.FixedLimit -> context.getString(R.string.widget_button_protect_fixed, policy.percent)
    ChargePolicy.Adaptive -> context.getString(R.string.widget_button_protect_adaptive)
    else -> context.getString(R.string.widget_button_protect)
}

private fun widgetLabel(context: Context, policy: ChargePolicy?): String = when (policy) {
    is ChargePolicy.FixedLimit -> context.getString(R.string.widget_label_limited, policy.percent)
    ChargePolicy.Unrestricted -> context.getString(R.string.widget_label_unlimited)
    ChargePolicy.Adaptive -> context.getString(R.string.widget_label_adaptive)
    ChargePolicy.PauseAtFull -> context.getString(R.string.widget_label_pause_at_full)
    null -> context.getString(R.string.widget_label_tap)
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

/** "∞ <limit>" — set the adapter's default protective limit persistently (ends any one-time session). */
@Keep
class ProtectAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val entryPoint = EntryPointAccessors.fromApplication(
            context.applicationContext,
            AmplyWidgetEntryPoint::class.java,
        )
        val policy = entryPoint.chargingRepository().currentAdapter()?.defaultProtectivePolicy
            ?: ChargePolicy.FixedLimit(80)
        setPersistentOrOpen(context, policy)
    }
}

/** "∞ 100%" — always charge to 100% (persistent Unrestricted). */
@Keep
class AlwaysFullAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        setPersistentOrOpen(context, ChargePolicy.Unrestricted)
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
            if (!state.canApply) {
                openApp(context, false)
                return
            }
            // A once-session is meaningless when the battery already reaches 100% (Unrestricted, or a
            // pause-at-full mode). Prefer the current observation and only fall back to the last request
            // when nothing can be observed, so a native change away from full-charging still lets the
            // once-session start. The manager re-checks this centrally; here it just avoids a service start.
            val currentPolicy = state.observation.policyOrNull()
                ?: entryPoint.chargingPreferences().lastRequestedNow()
            if (currentPolicy?.allowsFullCharge == true) {
                openApp(context, false)
                return
            }
            startService(context, ChargeSessionService.ACTION_START)
        }
    }
}

/**
 * Route a persistent-policy change through the service (serialized, cancels sessions, force-writes so a
 * same-value write still re-triggers the HAL), or open the app when charging can't be controlled. The
 * service does the write AND the authoritative widget render, so the charging command is never gated on a
 * widget update.
 */
private suspend fun setPersistentOrOpen(context: Context, policy: ChargePolicy) {
    val entryPoint = EntryPointAccessors.fromApplication(
        context.applicationContext,
        AmplyWidgetEntryPoint::class.java,
    )
    val state = entryPoint.chargingRepository().refresh()
    if (!state.canApply) {
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
