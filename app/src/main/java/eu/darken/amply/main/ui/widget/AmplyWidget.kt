package eu.darken.amply.main.ui.widget

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.compose.runtime.Composable
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
import androidx.glance.action.actionParametersOf
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
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
import eu.darken.amply.charging.core.isAwaitingReplug
import eu.darken.amply.charging.core.isSettling
import eu.darken.amply.charging.core.settlingTarget
import eu.darken.amply.common.datastore.value
import eu.darken.amply.common.debug.logging.Logging
import eu.darken.amply.common.debug.logging.log
import eu.darken.amply.common.debug.logging.logTag
import eu.darken.amply.fullcharge.core.FullChargeStore
import eu.darken.amply.fullcharge.core.ChargeSessionService
import eu.darken.amply.fullcharge.core.policyOrNull
import eu.darken.amply.fullcharge.core.resolveQuickActionPolicies
import eu.darken.amply.main.ui.MainActivity
import eu.darken.amply.upgrade.core.UpgradeRepo
import eu.darken.amply.upgrade.core.isProForUi
import kotlinx.coroutines.CancellationException

private val TAG = logTag("Widget")

/** Below this width the brand mark + name is dropped so the status line stays readable. */
private val BRAND_MIN_WIDTH = 200.dp

/**
 * Below this height the 2-cell paddings would clip a 1-cell widget, so they shrink. 80dp sits between
 * the classic 1-cell (40dp) and 2-cell (110dp) heights.
 */
private val COMPACT_MIN_HEIGHT = 80.dp

private val TITLE_COLOR = ColorProvider(Color(0xFF123832), Color(0xFFE0F5F0))

@Keep
class AmplyWidget : GlanceAppWidget() {
    // Exact so LocalSize reports the actual widget size and we can show the brand only when it fits.
    override val sizeMode: SizeMode = SizeMode.Exact

    /**
     * Drop this instance's button selection when the launcher removes it, so a recycled AppWidget id
     * cannot inherit a stranger's configuration. Best-effort by design: there is no retry queue, and
     * a stale entry is harmless (it is only ever read for a widget that exists) — but a failure here
     * must not take the deletion down with it.
     */
    override suspend fun onDelete(context: Context, glanceId: GlanceId) {
        val entryPoint = EntryPointAccessors.fromApplication(
            context.applicationContext,
            AmplyWidgetEntryPoint::class.java,
        )
        try {
            val appWidgetId = GlanceAppWidgetManager(context).getAppWidgetId(glanceId)
            entryPoint.sessionStore().removeWidgetQuickActions(listOf(appWidgetId))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log(TAG, Logging.Priority.WARN) { "Widget config cleanup failed: ${e.message}" }
        }
    }

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val entryPoint = EntryPointAccessors.fromApplication(
            context.applicationContext,
            AmplyWidgetEntryPoint::class.java,
        )
        val repo = entryPoint.chargingRepository()
        val sessionStore = entryPoint.sessionStore()
        val preferences = entryPoint.chargingPreferences()
        // Per-instance: two Amply widgets on the same home screen can carry different buttons.
        val appWidgetId = runCatching { GlanceAppWidgetManager(context).getAppWidgetId(id) }.getOrNull()

        // Resolved before anything else: a free user's widget renders locked, so none of the state
        // below is worth fetching. Placement itself is never blocked — a widget the launcher refuses
        // to place is a far worse experience than one that explains what it needs.
        val isPro = entryPoint.upgradeRepo().isProForUi()
        if (!isPro) {
            provideContent { LockedWidget(context) }
            return
        }

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
        val initialQuickActions = runCatching { sessionStore.widgetQuickActions.value() }.getOrNull()

        provideContent {
            // Reactive composition: Glance never re-runs an already-active provideGlance, so a one-shot
            // pre-provideContent read alone would miss later backend changes and freeze the widget after a tap.
            // Observing the same reactive sources the rest of the app uses makes Glance recompose this content
            // in place whenever the backend emits. The StateFlow already reflects the seeded refresh above; the
            // two plain flows are seeded with the pre-read values so the first frame is never empty.
            val state by repo.state.collectAsState()
            val session by sessionStore.session.collectAsState(initial = initialSession)
            val requestedTarget by preferences.lastRequested.collectAsState(initial = initialRequested)
            val quickActionConfig by sessionStore.widgetQuickActions.flow
                .collectAsState(initial = initialQuickActions)

            val display = widgetDisplay(state, sessionActive = session != null, now = System.currentTimeMillis())
            val status = statusLine(
                context,
                display.sessionActive,
                display.settling,
                display.awaitingReplug,
                state,
                requestedTarget,
            )
            val showBrand = display.steady && LocalSize.current.width >= BRAND_MIN_WIDTH
            // A 1-cell widget keeps the status line: information beats a perfectly padded layout on
            // an unusually tight grid.
            val compact = LocalSize.current.height < COMPACT_MIN_HEIGHT
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
                    .padding(if (compact) 6.dp else 12.dp),
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
                val quickActions = widgetQuickActions(state, appWidgetId?.let { quickActionConfig?.get(it) })
                Row(modifier = GlanceModifier.fillMaxWidth().padding(top = if (compact) 4.dp else 10.dp)) {
                    if (quickActions == null) {
                        // No resolved adapter yet, or a device with nothing to render from (a
                        // diagnostics-only lab adapter): keep the pre-configuration rendering, whose
                        // actions resolve their policy at tap time instead of at render time.
                        Button(
                            text = policyButtonLabel(context, state.defaultProtectivePolicy),
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
                    } else {
                        quickActions.forEach { policy ->
                            Button(
                                text = policyButtonLabel(context, policy),
                                onClick = actionRunCallback<SetPolicyAction>(
                                    actionParametersOf(SetPolicyAction.POLICY_KEY to policy.stableId),
                                ),
                                modifier = GlanceModifier.defaultWeight(),
                                style = buttonText,
                                maxLines = 1,
                            )
                            Spacer(GlanceModifier.width(6.dp))
                        }
                    }
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
 * What a free user's widget shows: the brand, the tier it needs, and a one-line explanation. The whole
 * surface opens the upgrade screen — there are no per-button affordances to mis-tap, and a widget that
 * did nothing at all would read as broken.
 */
@Composable
private fun LockedWidget(context: Context) {
    val titleStyle = TextStyle(color = TITLE_COLOR, fontWeight = FontWeight.Bold)
    Column(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(ColorProvider(Color(0xFFE1F5F0), Color(0xFF153531)))
            .clickable(
                actionStartActivity(
                    Intent(context, MainActivity::class.java)
                        .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                        .putExtra(MainActivity.EXTRA_OPEN_UPGRADE, true),
                ),
            )
            .padding(12.dp),
        verticalAlignment = Alignment.Vertical.CenterVertically,
    ) {
        Row(verticalAlignment = Alignment.Vertical.CenterVertically) {
            Image(
                provider = ImageProvider(R.drawable.ic_launcher_monochrome),
                contentDescription = null,
                modifier = GlanceModifier.size(16.dp),
                colorFilter = ColorFilter.tint(TITLE_COLOR),
            )
            Spacer(GlanceModifier.width(6.dp))
            Text(
                text = context.getString(
                    R.string.app_name_upgraded_template,
                    context.getString(R.string.app_name),
                    context.getString(R.string.app_name_upgrade_postfix),
                ),
                style = titleStyle,
                maxLines = 1,
            )
        }
        Text(
            text = context.getString(R.string.widget_locked_body),
            style = TextStyle(fontSize = 12.sp),
            maxLines = 2,
            modifier = GlanceModifier.padding(top = 6.dp),
        )
    }
}

/**
 * Structural (context-free) widget display derivation, kept pure so the branches are JVM-unit-testable.
 * `sessionActive` wins over everything; `settling` is the pending-request window; `awaitingReplug` is a
 * plug-latched adapter's condition-based pending (mutually exclusive with settling by construction);
 * `steady` (a plain resting policy, nothing in flight) is the only state that shows the brand mark.
 */
internal data class WidgetDisplay(
    val sessionActive: Boolean,
    val settling: Boolean,
    val awaitingReplug: Boolean,
    val steady: Boolean,
)

internal fun widgetDisplay(state: ChargingState, sessionActive: Boolean, now: Long): WidgetDisplay {
    val settling = state.isSettling(now)
    val awaitingReplug = state.isAwaitingReplug()
    return WidgetDisplay(
        sessionActive = sessionActive,
        settling = settling,
        awaitingReplug = awaitingReplug,
        steady = !sessionActive && !settling && !awaitingReplug,
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
    awaitingReplug: Boolean,
    state: ChargingState,
    requestedTarget: ChargePolicy?,
): String {
    if (sessionActive) {
        return when {
            settling -> context.getString(R.string.widget_status_charging_waiting)
            // Plug-latched adapters: the session exists but its override hasn't latched — claiming
            // "charging to 100% once" would be false until the user re-seats the cable.
            awaitingReplug -> context.getString(R.string.widget_status_charging_replug)
            else -> context.getString(R.string.widget_status_charging_once)
        }
    }
    if (settling) {
        return context.getString(
            R.string.widget_status_waiting_suffix,
            widgetLabel(context, state.settlingTarget() ?: requestedTarget),
        )
    }
    // Plug-latched adapters: the value is configured (label it) but only takes effect at the next
    // plug session. May linger while nothing observes a replug; never claims the reverse error.
    if (awaitingReplug) {
        return context.getString(
            R.string.widget_status_replug_suffix,
            widgetLabel(context, state.settlingTarget() ?: requestedTarget),
        )
    }
    return widgetLabel(context, state.observation.policyOrNull() ?: requestedTarget)
}

/**
 * Which persistent-policy buttons this widget instance shows, or null for the pre-configuration
 * rendering, whose actions resolve their policy at tap time.
 *
 * Branching on [ChargingState.adapterResolved] and NOT on the policy list alone: the capability
 * defaults are permissive, so an unresolved state would briefly render buttons the resolved adapter
 * forbids.
 *
 * Fewer than two supported policies is the **diagnostics-only device** — every lab adapter resolves
 * with an empty supported list, which is every non-gated device — and rendering from that list would
 * silently drop both persistent-policy buttons the widget shows today. Those devices keep the legacy
 * rendering, which is safe because its buttons carry no pre-resolved target: `setPersistentOrOpen`
 * resolves the policy at tap time and opens the app instead of dispatching when `canApply` is false,
 * and the service refuses a target that isn't in the adapter's supported list.
 */
internal fun widgetQuickActions(state: ChargingState, storedIds: List<String>?): List<ChargePolicy>? {
    val defaultProtective = state.defaultProtectivePolicy
    if (!state.adapterResolved || defaultProtective == null || state.supportedPolicies.size < 2) return null
    return resolveQuickActionPolicies(storedIds, state.supportedPolicies, defaultProtective)
}

/** "∞ <mode>", e.g. ∞80% on Pixel, ∞Auto on Xiaomi; null is the unresolved adapter's generic label. */
internal fun policyButtonLabel(context: Context, policy: ChargePolicy?): String = when (policy) {
    is ChargePolicy.FixedLimit -> context.getString(R.string.widget_button_protect_fixed, policy.percent)
    ChargePolicy.Adaptive -> context.getString(R.string.widget_button_protect_adaptive)
    ChargePolicy.Unrestricted -> context.getString(R.string.widget_button_always_full)
    ChargePolicy.PauseAtFull -> context.getString(R.string.widget_button_pause_at_full)
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
    fun upgradeRepo(): UpgradeRepo
}

/**
 * Every widget action re-checks the entitlement rather than trusting the rendering that produced the
 * button. A widget composition can be several minutes stale — Glance re-renders on its own schedule —
 * so a lapse between render and tap would otherwise still perform the gated write.
 */
private suspend fun requireProOrOpenUpgrade(context: Context): Boolean {
    val entryPoint = EntryPointAccessors.fromApplication(
        context.applicationContext,
        AmplyWidgetEntryPoint::class.java,
    )
    if (entryPoint.upgradeRepo().isProForUi()) return true
    openApp(context, requestNotifications = false, openUpgrade = true)
    return false
}

/** "∞ <limit>" — set the adapter's default protective limit persistently (ends any one-time session). */
@Keep
class ProtectAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        if (!requireProOrOpenUpgrade(context)) return
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
        if (!requireProOrOpenUpgrade(context)) return
        setPersistentOrOpen(context, ChargePolicy.Unrestricted)
    }
}

/**
 * A configured button: the policy rides along as a stable id, since the composition that rendered
 * the button can be minutes old and the widget's configuration may have changed since.
 */
@Keep
class SetPolicyAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        if (!requireProOrOpenUpgrade(context)) return
        val policy = resolveSetPolicyTarget(parameters[POLICY_KEY])
        if (policy == null) {
            // A button whose target this build cannot read is not guessed at — open the app instead.
            openApp(context, false)
            return
        }
        setPersistentOrOpen(context, policy)
    }

    companion object {
        val POLICY_KEY = ActionParameters.Key<String>("policy")
    }
}

/** Missing or unreadable ids yield null, which the action turns into "open the app". */
internal fun resolveSetPolicyTarget(raw: String?): ChargePolicy? = ChargePolicy.fromStableId(raw)

/** "1× 100%" — charge fully once, then restore the protective policy. */
@Keep
class FullChargeAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        if (!requireProOrOpenUpgrade(context)) return
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

private fun openApp(context: Context, requestNotifications: Boolean, openUpgrade: Boolean = false) {
    context.startActivity(
        Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(MainActivity.EXTRA_REQUEST_NOTIFICATIONS, requestNotifications)
            if (openUpgrade) putExtra(MainActivity.EXTRA_OPEN_UPGRADE, true)
        },
    )
}
