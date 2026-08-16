package eu.darken.amply.main.ui.dashboard

import android.app.StatusBarManager
import android.appwidget.AppWidgetManager
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import eu.darken.amply.R
import eu.darken.amply.charging.core.ChargeObservation
import eu.darken.amply.charging.core.ChargePolicy
import eu.darken.amply.charging.core.ChargingRepository
import eu.darken.amply.charging.core.ChargingState
import eu.darken.amply.charging.core.SETTLING_WINDOW_MILLIS
import eu.darken.amply.common.AmplyLinks
import eu.darken.amply.common.debug.logging.Logging
import eu.darken.amply.common.debug.logging.asLog
import eu.darken.amply.common.debug.logging.log
import eu.darken.amply.common.debug.logging.logTag
import eu.darken.amply.common.flow.SingleEventFlow
import eu.darken.amply.upgrade.core.UpgradeRepo
import eu.darken.amply.upgrade.core.isProForUi
import eu.darken.amply.common.flow.combine as combine6
import eu.darken.amply.fullcharge.core.ChargeSessionRecord
import eu.darken.amply.fullcharge.core.ChargeSessionService
import eu.darken.amply.fullcharge.core.FullChargeStore
import eu.darken.amply.fullcharge.core.InterruptionEvent
import eu.darken.amply.fullcharge.core.InterruptionStore
import eu.darken.amply.fullcharge.core.ServiceDispatch
import eu.darken.amply.main.core.DeviceSupportReport
import eu.darken.amply.main.core.DeviceSupportReporter
import eu.darken.amply.main.core.OnboardingSettings
import eu.darken.amply.main.core.QuickAccessState
import eu.darken.amply.main.core.QuickAccessStore
import eu.darken.amply.main.ui.tile.ChargeTileService
import eu.darken.amply.main.ui.widget.AmplyWidgetReceiver
import eu.darken.amply.main.core.formatReport
import eu.darken.amply.main.core.issueTitle
import eu.darken.amply.main.core.issueUrl
import eu.darken.amply.alarm.core.ChargeAlarmConfig
import eu.darken.amply.alarm.core.ChargeAlarmNotifications
import eu.darken.amply.alarm.core.ChargeAlarmStore
import eu.darken.amply.battery.core.BatteryReadout
import eu.darken.amply.battery.core.BatteryReadoutSource
import eu.darken.amply.monitor.core.ChargeMonitorWatcher
import eu.darken.amply.stats.core.CaptureServiceHealth
import eu.darken.amply.stats.core.ChargeSessionSummary
import eu.darken.amply.stats.core.ChargeStatsRepository
import eu.darken.amply.stats.core.StatsLiveSession
import eu.darken.amply.stats.core.StatsPreferences
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import javax.inject.Inject

data class DashboardUiState(
    val charging: ChargingState = ChargingState(),
    val session: ChargeSessionRecord? = null,
    val onboardingComplete: Boolean? = null,
    val quickFullChargeEnabled: Boolean = false,
    val quickFullChargeAnyLevel: Boolean = false,
    val deviceReport: DeviceSupportReport? = null,
    val quickAccess: QuickAccessState = QuickAccessState(),
    /** True once the initial widget-presence check has completed; the promo card hides until then. */
    val quickAccessChecked: Boolean = false,
    val tileRequestPending: Boolean = false,
    val batteryReadout: BatteryReadout? = null,
    val alarm: ChargeAlarmConfig = ChargeAlarmConfig(),
    /** True when the OS/channel would suppress the charge-alarm alert (permission off or blocked). */
    val notificationsBlocked: Boolean = false,
    /** Everything the stats card needs: capture switch, pipeline health, and the Room teaser data. */
    val stats: StatsDashboardState = StatsDashboardState(),
    /** A pending "Amply was interrupted while owing a restore" warning, or null when there is none. */
    val interruption: InterruptionEvent? = null,
    /**
     * The Pro entitlement, or null until it has been read at all. Nullable rather than defaulted:
     * "not upgraded" and "not yet known" have to stay distinguishable, or the promo card would flash
     * at a paying user on every cold start while billing is still connecting.
     */
    val upgrade: UpgradeSnapshot? = null,
)

/** The two facts the dashboard needs about the entitlement. */
data class UpgradeSnapshot(
    val isPro: Boolean,
    val isSettled: Boolean,
)

/**
 * The upgrade ask — currently the Pro badges, and the promo card once it returns — has no business
 * appearing before we know the answer, or after a yes.
 */
fun shouldShowUpgradePromo(upgrade: UpgradeSnapshot?): Boolean =
    upgrade != null && upgrade.isSettled && !upgrade.isPro

@HiltViewModel
class DashboardViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: ChargingRepository,
    private val fullChargeStore: FullChargeStore,
    private val onboardingSettings: OnboardingSettings,
    private val deviceSupportReporter: DeviceSupportReporter,
    private val quickAccessStore: QuickAccessStore,
    private val batteryReadoutSource: BatteryReadoutSource,
    private val chargeAlarmStore: ChargeAlarmStore,
    private val statsPreferences: StatsPreferences,
    private val statsRepository: ChargeStatsRepository,
    private val captureServiceHealth: CaptureServiceHealth,
    private val interruptionStore: InterruptionStore,
    private val upgradeRepo: UpgradeRepo,
    private val watchers: Set<@JvmSuppressWildcards ChargeMonitorWatcher>,
) : ViewModel() {
    private val deviceReport = MutableStateFlow<DeviceSupportReport?>(null)
    private val quickAccessChecked = MutableStateFlow(false)
    private val tileRequestPending = MutableStateFlow(false)
    private val notificationsBlocked = MutableStateFlow(false)
    private var lastPinRequestAt = 0L

    /** Emitted when a gated affordance was tapped without the entitlement; the root navigates. */
    val upgradeRequiredEvents = SingleEventFlow<Unit>()

    // The typed combine overloads stop at five flows; the two gesture booleans are pre-combined.
    private val gestureFlags = combine(
        fullChargeStore.quickFullChargeEnabled.flow,
        fullChargeStore.quickFullChargeAnyLevel.flow,
    ) { enabled, anyLevel -> enabled to anyLevel }

    // Grouped so the outer combine keeps one slot each: the live battery readout, the alarm config,
    // the notifications-blocked flag, the interrupted-session warning, and the entitlement.
    private val unprivilegedExtras = combine(
        batteryReadoutSource.readouts(),
        chargeAlarmStore.config,
        notificationsBlocked,
        interruptionStore.event,
        // Seeded null so the dashboard renders immediately: waiting for the first billing answer
        // would hold up the whole screen, and the promo card is the only thing that needs it.
        upgradeRepo.upgradeInfo
            .map<UpgradeRepo.Info, UpgradeSnapshot?> { UpgradeSnapshot(isPro = it.isPro, isSettled = it.isSettled) }
            .catch { e ->
                if (e is CancellationException) throw e
                log(TAG, Logging.Priority.WARN) { "Upgrade info read failed: ${e.message}" }
                emit(null)
            }
            .onStart { emit(null) }
            .distinctUntilChanged(),
    ) { readout, alarm, blocked, interruption, upgrade ->
        UnprivilegedExtras(readout, alarm, blocked, interruption, upgrade)
    }

    private data class UnprivilegedExtras(
        val readout: BatteryReadout?,
        val alarm: ChargeAlarmConfig,
        val notificationsBlocked: Boolean,
        val interruption: InterruptionEvent?,
        val upgrade: UpgradeSnapshot?,
    )

    // Battery-statistics dashboard teaser. The session/count reads (which open the stats Room DB)
    // run only while capture is enabled — when it's off the card shows a promo, so a user who never
    // turned stats on never gets an empty stats.db created just by opening the dashboard.
    private val statsDashboard = statsDashboardStates(
        captureEnabled = statsPreferences.captureEnabled.flow,
        health = captureServiceHealth.state,
        recentSessions = { statsRepository.recentSessions(limit = 1) },
        sessionCount = { statsRepository.sessionCount() },
        currentSession = { statsRepository.currentSession() },
    )

    val state = combine6(
        combine(
            repository.state,
            fullChargeStore.session,
            onboardingSettings.isComplete.flow,
            gestureFlags,
            deviceReport,
        ) { charging, session, onboardingComplete, (quickFullChargeEnabled, quickFullChargeAnyLevel), report ->
            DashboardUiState(
                charging = charging,
                session = session,
                onboardingComplete = onboardingComplete,
                quickFullChargeEnabled = quickFullChargeEnabled,
                quickFullChargeAnyLevel = quickFullChargeAnyLevel,
                deviceReport = report,
            )
        },
        quickAccessStore.state.flow,
        quickAccessChecked,
        tileRequestPending,
        unprivilegedExtras,
        statsDashboard,
    ) { base, quickAccess, checked, tilePending, extras, stats ->
        base.copy(
            quickAccess = quickAccess,
            quickAccessChecked = checked,
            tileRequestPending = tilePending,
            batteryReadout = extras.readout,
            alarm = extras.alarm,
            notificationsBlocked = extras.notificationsBlocked,
            interruption = extras.interruption,
            upgrade = extras.upgrade,
            stats = stats,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DashboardUiState())

    val adbGrantCommand: String
        get() = "adb shell pm grant ${context.packageName} android.permission.WRITE_SECURE_SETTINGS"

    init {
        refresh()
        observeSettlingDeadline()
    }

    fun refresh() = viewModelScope.launch {
        refreshNotificationsBlocked()
        repository.refresh()
    }

    /**
     * Re-reconcile the entitlement with the store. Deliberately NOT part of [refresh]: that one runs
     * on every battery broadcast (~once a minute while charging), and a billing round-trip on that
     * cadence would be both wasteful and a good way to get rate-limited. Called on resume only.
     */
    fun refreshUpgradeState() = viewModelScope.launch {
        try {
            upgradeRepo.refresh()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log(TAG, Logging.Priority.WARN) { "Upgrade refresh failed: ${e.message}" }
        }
    }

    /**
     * Fire the post-settling refresh from the ViewModel rather than the status card's composition.
     * The card previously drove this from a LaunchedEffect, which is cancelled the instant the
     * dashboard leaves the screen — e.g. opening the battery hub mid-"applying" — stranding the
     * pending marker until the user returns. Owning the
     * deadline here promotes to Verified / clears pending regardless of what's on screen.
     * `flatMapLatest` restarts the timer whenever the pending request changes; the WorkManager
     * SettleScheduler stays the across-process-death backstop.
     */
    private fun observeSettlingDeadline() = viewModelScope.launch {
        repository.state
            .map { it.pending }
            .distinctUntilChanged()
            .flatMapLatest { pending ->
                if (pending == null || pending.awaitingReplug) {
                    // Awaiting-replug requests have no deadline: resolution comes from evidence at
                    // the next refresh (MainActivity's battery receiver fires one on every plug
                    // transition while the UI is visible; the SettleRefreshWorker covers the rest).
                    emptyFlow()
                } else {
                    flow {
                        // Re-check the wall-clock remaining after each sleep: a backward clock
                        // adjustment must not fire the refresh before the window (shared with the
                        // repository's wall-clock expiry) has truly elapsed and then fail to rearm,
                        // since the unchanged pending request is deduped upstream.
                        while (true) {
                            val remaining = pending.requestedAt + SETTLING_WINDOW_MILLIS - System.currentTimeMillis()
                            if (remaining <= 0) break
                            delay(remaining)
                        }
                        emit(Unit)
                    }
                }
            }
            .collect { refresh() }
    }

    /** Re-read whether alarm alerts would actually reach the user (permission, global toggle, and
     * the alarm channel not being muted to IMPORTANCE_NONE). */
    private fun refreshNotificationsBlocked() {
        notificationsBlocked.value = !ChargeAlarmNotifications.canDeliver(context)
    }

    /**
     * Runs while the access-setup card is on screen and the activity is resumed (see [shouldMonitorAccess];
     * driven from a repeatOnLifecycle boundary at the composition root, so it is cancelled on pause and torn
     * down the instant WSS is detected). Catches an external grant that fires no OS callback — `adb pm grant`
     * or Shizuku authorised in its own manager — by merging a slow poll with Shizuku availability events and
     * re-checking access cheaply. A tick failure is logged and the monitor keeps running; cancellation
     * propagates so the lifecycle can stop it.
     */
    suspend fun monitorAccessWhileAwaitingGrant() {
        merge(
            repository.accessEvents(),
            flow {
                while (true) {
                    emit(Unit)
                    delay(ACCESS_POLL_INTERVAL_MS)
                }
            },
        ).conflate().collect {
            // conflate() after merge bounds pending re-checks to one: while a full refresh suspends, a burst
            // of ticks/Shizuku events collapses instead of queuing behind it. (Not collectLatest — a new
            // tick must not cancel an in-progress repository refresh.)
            try {
                repository.refreshAccessIfChanged()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log(TAG, Logging.Priority.WARN) { "Access monitor tick failed: ${e.message}" }
            }
        }
    }

    /**
     * Foreground-entry nudge: after a force-stop, no receiver or sticky restart revives the
     * session service, so each resume checks for leftover work (persisted session, pending
     * recovery, enabled gesture) and pokes the service with ACTION_CHECK. The returned job is
     * cancelled on pause so the store reads can't complete after backgrounding and trigger a
     * foreground-service start from the background.
     */
    fun nudgeChargeService(): Job = viewModelScope.launch {
        try {
            // The first run in a new boot keeps conservative BOOT (restore) semantics even from
            // here: a launch of an app that stayed stopped across a reboot races the deferred
            // BOOT_COMPLETED delivery, and seeding the boot count first would downgrade both
            // paths to CHECK and resume a stale pre-reboot session.
            val bootCount = ServiceDispatch.currentBootCount(context)
            val trigger = ServiceDispatch.bootTrigger(
                currentBootCount = bootCount,
                lastSeenBootCount = fullChargeStore.lastSeenBootCount(),
            )
            // Consult optional watcher state only when nothing mandatory already requires the
            // service, so a slow/hung watcher can never delay a session/recovery nudge.
            val sessionExists = fullChargeStore.currentSession() != null
            val pendingRecovery = fullChargeStore.pendingRecoveryTarget() != null
            val gestureEnabled = fullChargeStore.isQuickFullChargeEnabled()
            val mandatory = sessionExists || pendingRecovery || gestureEnabled
            val action = ServiceDispatch.startAction(
                trigger = trigger,
                sessionExists = sessionExists,
                pendingRecovery = pendingRecovery,
                gestureEnabled = gestureEnabled,
                watcherEnabled = if (mandatory) false else anyWatcherEnabled(),
            )
            if (action != null) {
                log(TAG) { "Foreground nudge (trigger=$trigger): starting service with $action" }
                try {
                    ContextCompat.startForegroundService(context, ServiceDispatch.startIntent(context, action))
                    captureServiceHealth.reportDispatched()
                } catch (e: Exception) {
                    captureServiceHealth.reportFailed()
                    throw e
                }
            }
            // Record only after a start that didn't throw, so a failed dispatch is retried with
            // unchanged semantics on the next resume.
            bootCount?.let { fullChargeStore.setLastSeenBootCount(it) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Pause-cancellation is a best effort; if backgrounding still races the start
            // (ForegroundServiceStartNotAllowedException), the next resume retries.
            log(TAG, Logging.Priority.WARN) { "Foreground nudge failed: ${e.message}" }
        }
    }

    fun completeOnboarding() = viewModelScope.launch { onboardingSettings.complete() }

    /**
     * Opt this build into enforcement verification. The controls become available immediately and are
     * labelled as unproven until a charge is observed holding at the limit; the observation itself
     * rides the monitor service, which the nudge below (re)starts now that a watcher wants it.
     */
    fun startEnforcementVerification() = viewModelScope.launch {
        log(TAG, Logging.Priority.INFO) { "startEnforcementVerification()" }
        repository.startEnforcementVerification()
        ContextCompat.startForegroundService(
            context,
            Intent(context, ChargeSessionService::class.java).setAction(ChargeSessionService.ACTION_MONITOR),
        )
    }

    /**
     * Routed through the service (same command the widget's ∞ buttons use) rather than writing the
     * policy here: the service serializes the write against session/recovery writes, refuses when no
     * backend can write, persists the recovery target before the risky write, suppresses its own
     * settings-observer trip, force-writes so a same-value write still re-triggers the HAL, clears
     * the interruption warning plus its recovery notification on success (and posts one on failure)
     * — and resets the gesture engine, which the plain ACTION_MONITOR nudge never did, so a stale
     * arming basis could survive a persistent-policy change.
     */
    fun applyPolicy(policy: ChargePolicy) {
        log(TAG, Logging.Priority.INFO) { "applyPolicy(${policy.stableId})" }
        ContextCompat.startForegroundService(
            context,
            Intent(context, ChargeSessionService::class.java)
                .setAction(ChargeSessionService.ACTION_SET_PERSISTENT_POLICY)
                .putExtra(ChargeSessionService.EXTRA_TARGET_POLICY, policy.stableId),
        )
    }

    fun startFullCharge() {
        log(TAG, Logging.Priority.INFO) { "startFullCharge()" }
        ContextCompat.startForegroundService(
            context,
            Intent(context, ChargeSessionService::class.java).setAction(ChargeSessionService.ACTION_START),
        )
    }

    fun restoreNow() {
        ContextCompat.startForegroundService(
            context,
            Intent(context, ChargeSessionService::class.java).setAction(ChargeSessionService.ACTION_RESTORE),
        )
    }

    fun setQuickFullChargeEnabled(enabled: Boolean) = viewModelScope.launch {
        log(TAG, Logging.Priority.INFO) { "setQuickFullChargeEnabled($enabled)" }
        fullChargeStore.setQuickFullChargeEnabled(enabled)
        ContextCompat.startForegroundService(
            context,
            Intent(context, ChargeSessionService::class.java).setAction(ChargeSessionService.ACTION_MONITOR),
        )
    }

    fun setChargeAlarmEnabled(enabled: Boolean) = viewModelScope.launch {
        log(TAG, Logging.Priority.INFO) { "setChargeAlarmEnabled($enabled)" }
        chargeAlarmStore.setEnabled(enabled)
        if (!enabled) {
            // Disabling ends the alarm's claim on the service and clears any shown alert; the next
            // enable starts a fresh cycle.
            chargeAlarmStore.setFiredCycle(false)
            ChargeAlarmNotifications.cancel(context)
        }
        // Nudge the service to (re)evaluate whether it should stay alive for a watcher.
        ContextCompat.startForegroundService(
            context,
            Intent(context, ChargeSessionService::class.java).setAction(ChargeSessionService.ACTION_MONITOR),
        )
    }

    fun setChargeAlarmTarget(percent: Int) = viewModelScope.launch {
        chargeAlarmStore.setTargetPercent(percent)
    }

    /** Open the app's system notification settings so the user can re-enable alarm alerts. */
    fun openNotificationSettings() {
        val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }.onFailure {
            runCatching {
                context.startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                        .setData(Uri.fromParts("package", context.packageName, null))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
        }
    }

    /**
     * Watcher enablement for dispatch; a throwing or slow optional watcher must never gate recovery,
     * so each query is bounded and failure/timeout reads as false. Only reached when nothing
     * mandatory already requires the service.
     */
    private suspend fun anyWatcherEnabled(): Boolean = watchers.any { watcher ->
        runCatching {
            withTimeoutOrNull(WATCHER_QUERY_BUDGET_MILLIS) { watcher.isEnabled() } ?: false
        }.getOrElse {
            log(TAG, Logging.Priority.WARN) { "Watcher ${watcher.id} isEnabled failed: ${it.message}" }
            false
        }
    }

    fun setQuickFullChargeAnyLevel(enabled: Boolean) = viewModelScope.launch {
        log(TAG, Logging.Priority.INFO) { "setQuickFullChargeAnyLevel($enabled)" }
        fullChargeStore.setQuickFullChargeAnyLevel(enabled)
        // Nudge a running monitor so the notification copy and arming reflect the change now
        // instead of on the next broadcast; a stopped/ineligible service just stops itself again.
        ContextCompat.startForegroundService(
            context,
            Intent(context, ChargeSessionService::class.java).setAction(ChargeSessionService.ACTION_MONITOR),
        )
    }

    /**
     * Best-effort widget discovery for the quick-access promotion. Deliberately separate from
     * [refresh]: that runs on every battery broadcast, and a binder/DataStore failure here must
     * never affect charging state. Until the first successful check the promo card stays hidden —
     * offering "Add widget" before knowing one exists could create a duplicate.
     */
    fun refreshQuickAccessPresence() = viewModelScope.launch {
        runCatching {
            val widgetIds = withContext(Dispatchers.Default) {
                AppWidgetManager.getInstance(context)
                    .getAppWidgetIds(ComponentName(context, AmplyWidgetReceiver::class.java))
            }
            if (widgetIds.isNotEmpty()) quickAccessStore.markWidgetAdded()
            quickAccessChecked.value = true
        }.onFailure {
            // Stay "unchecked" so the card keeps hidden; the next resume retries.
            log(TAG, Logging.Priority.WARN) { "Quick-access widget check failed: ${it.message}" }
        }
    }

    fun dismissQuickAccess() = viewModelScope.launch { quickAccessStore.dismiss() }

    fun dismissInterruption() = viewModelScope.launch { interruptionStore.clear() }

    fun requestPinWidget() = viewModelScope.launch {
        // Gated before the launcher dialog, not after: asking the system to place a widget and only
        // then telling the user it needs an upgrade would be the wrong order.
        if (!upgradeRepo.isProForUi()) {
            upgradeRequiredEvents.tryEmit(Unit)
            return@launch
        }
        // The pin dialog is modal once shown, but rapid taps before it appears would queue
        // multiple launcher requests — swallow them.
        val now = SystemClock.elapsedRealtime()
        if (now - lastPinRequestAt < 1_000) return@launch
        lastPinRequestAt = now

        // No success PendingIntent: the launcher accepting the request doesn't mean placement, so
        // confirmation comes from the presence re-check on the next resume instead.
        val requested = runCatching {
            val manager = AppWidgetManager.getInstance(context)
            manager.isRequestPinAppWidgetSupported &&
                manager.requestPinAppWidget(ComponentName(context, AmplyWidgetReceiver::class.java), null, null)
        }.onFailure {
            log(TAG, Logging.Priority.WARN) { "requestPinAppWidget failed: ${it.message}" }
        }.getOrDefault(false)

        if (!requested) {
            Toast.makeText(
                context,
                context.getString(R.string.dashboard_quickaccess_widget_manual),
                Toast.LENGTH_LONG,
            ).show()
        }
    }

    fun requestAddTile() = viewModelScope.launch {
        if (!upgradeRepo.isProForUi()) {
            upgradeRequiredEvents.tryEmit(Unit)
            return@launch
        }
        if (Build.VERSION.SDK_INT < 33) {
            // No add-tile API before Tiramisu (reachable: legacy Samsung support covers One UI 4/5).
            Toast.makeText(
                context,
                context.getString(R.string.dashboard_quickaccess_tile_manual),
                Toast.LENGTH_LONG,
            ).show()
            return@launch
        }
        // SystemUI rejects overlapping requests with TILE_ADD_REQUEST_ERROR_REQUEST_IN_PROGRESS;
        // the flag also disables the button until the callback resolves.
        if (!tileRequestPending.compareAndSet(expect = false, update = true)) return@launch
        runCatching {
            context.getSystemService(StatusBarManager::class.java).requestAddTileService(
                ComponentName(context, ChargeTileService::class.java),
                context.getString(R.string.tile_label),
                Icon.createWithResource(context, R.drawable.ic_launcher_monochrome),
                ContextCompat.getMainExecutor(context),
            ) { result ->
                tileRequestPending.value = false
                when (result) {
                    StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED,
                    StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED,
                    -> viewModelScope.launch { quickAccessStore.markTileAdded() }
                    // User declined or closed the dialog — keep the card, no nagging toast.
                    StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_NOT_ADDED -> Unit
                    else -> {
                        log(TAG, Logging.Priority.WARN) { "requestAddTileService result: $result" }
                        Toast.makeText(
                            context,
                            context.getString(R.string.dashboard_quickaccess_tile_manual),
                            Toast.LENGTH_LONG,
                        ).show()
                    }
                }
            }
        }.onFailure {
            tileRequestPending.value = false
            log(TAG, Logging.Priority.WARN) { "requestAddTileService failed: ${it.message}" }
            Toast.makeText(
                context,
                context.getString(R.string.dashboard_quickaccess_tile_manual),
                Toast.LENGTH_LONG,
            ).show()
        }
    }

    fun requestShizukuPermission() = viewModelScope.launch {
        repository.requestShizukuPermission()
    }

    fun grantWriteSecureSettings() = viewModelScope.launch {
        // The status card's result message can be scrolled off-screen while the user watches the setup
        // card, so surface a failure locally too. The repository returns the refreshed permission state
        // (WSS actually granted), so false is a real failure — not a lost pm-grant reply.
        val granted = repository.grantWriteSecureSettings()
        if (!granted) {
            Toast.makeText(
                context,
                context.getString(R.string.dashboard_wss_grant_failed_toast),
                Toast.LENGTH_LONG,
            ).show()
        }
    }

    fun openNativeSettings() {
        val intent = repository.nativeSettingsIntent() ?: Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS)
        runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            .onFailure {
                context.startActivity(
                    Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
    }

    fun openShizuku() = viewModelScope.launch {
        val launch = repository.shizukuManagerPackage()
            ?.let(context.packageManager::getLaunchIntentForPackage)
        val intent = launch ?: Intent(
            Intent.ACTION_VIEW,
            Uri.parse("https://shizuku.rikka.app/guide/setup/"),
        )
        runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

    fun copyAdbCommand() {
        context.getSystemService(ClipboardManager::class.java).setPrimaryClip(
            ClipData.newPlainText("Amply setup command", adbGrantCommand),
        )
        Toast.makeText(context, context.getString(R.string.dashboard_adb_copied), Toast.LENGTH_SHORT).show()
    }

    fun copyWebUsbLink() {
        // The WebUSB helper runs on the computer the phone is plugged into, so we copy the
        // link for the user to open there rather than launching the phone's own browser.
        context.getSystemService(ClipboardManager::class.java).setPrimaryClip(
            ClipData.newPlainText("Amply web helper link", AmplyLinks.WEB_ADB),
        )
        Toast.makeText(context, context.getString(R.string.dashboard_weblink_copied), Toast.LENGTH_SHORT).show()
    }

    /** Build the device-support report so the confirmation dialog can preview exactly what will be shared. */
    fun prepareDeviceSupportReport() = viewModelScope.launch {
        // Clear first so the dialog shows a loading placeholder and its Copy/Open buttons stay
        // disabled until this specific snapshot is ready — copy/open then reuse this exact value.
        deviceReport.value = null
        deviceReport.value = deviceSupportReporter.collect()
    }

    fun copyDeviceSupportReport() {
        val report = deviceReport.value ?: return
        context.getSystemService(ClipboardManager::class.java).setPrimaryClip(
            ClipData.newPlainText("Amply device report", formatReport(report)),
        )
        Toast.makeText(context, context.getString(R.string.setup_unsupported_report_copied), Toast.LENGTH_SHORT).show()
    }

    fun openDeviceSupportIssue() {
        val report = deviceReport.value ?: return
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(issueUrl(report)))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }.onFailure {
            when (it) {
                is ActivityNotFoundException -> Toast.makeText(
                    context,
                    context.getString(R.string.setup_unsupported_no_browser),
                    Toast.LENGTH_LONG,
                ).show()
                else -> log(TAG, Logging.Priority.WARN) { "Could not open issue URL: ${it.message}" }
            }
        }
    }

    /**
     * Lower-barrier alternative to GitHub: open the user's mail app with a short friendly message and
     * the device report attached as a .txt. If the attachment can't be created, the report is inlined
     * into the body instead, so the email always carries the details the copy promises.
     */
    fun emailDeviceSupport() = viewModelScope.launch {
        // Always re-collect rather than reusing the cached snapshot: a report gathered before Shizuku was granted
        // carries lineage_cc_provider=unknown, and silently emailing that stale answer defeats the probe.
        val report = deviceSupportReporter.collect().also { deviceReport.value = it }
        val attachment = runCatching {
            withContext(Dispatchers.IO) {
                val dir = File(context.cacheDir, "support").apply { mkdirs() }
                val file = File(dir, "amply-device-report.txt")
                file.writeText(formatReport(report))
                FileProvider.getUriForFile(context, "${context.packageName}.files", file)
            }
        }.getOrNull()

        val body = buildString {
            append(context.getString(R.string.setup_unsupported_email_body))
            if (attachment == null) {
                // No attachment could be created; inline the report so it isn't lost.
                append("\n\n")
                append(formatReport(report))
            }
        }
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_EMAIL, arrayOf("support@darken.eu"))
            putExtra(Intent.EXTRA_SUBJECT, issueTitle(report))
            putExtra(Intent.EXTRA_TEXT, body)
            attachment?.let { putExtra(Intent.EXTRA_STREAM, it) }
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val chooser = Intent.createChooser(intent, context.getString(R.string.setup_unsupported_email_action))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(chooser) }.onFailure {
            Toast.makeText(context, context.getString(R.string.setup_unsupported_no_email), Toast.LENGTH_SHORT).show()
        }
    }

    private companion object {
        val TAG = logTag("Dashboard", "VM")

        // Slow on purpose: the merged Shizuku events make the Shizuku path near-instant, so this only
        // backstops the ADB-grant path (no OS callback exists for it) while the user is on the setup card.
        const val ACCESS_POLL_INTERVAL_MS = 2_000L
        const val WATCHER_QUERY_BUDGET_MILLIS = 2_000L
    }
}

/**
 * True only while the access-setup card is actually on screen awaiting a grant, mirroring its visibility in
 * [DashboardScreen]: onboarding done, on the dashboard, a control-capable device (not Unsupported), and
 * durable WRITE_SECURE_SETTINGS not yet granted. Gates on direct WSS — not [AccessSnapshot.canControl] — so a
 * Shizuku-ready device still polls for an external `adb pm grant`. Keeps the watcher off unsupported devices
 * (where access would never flip) and off every other screen.
 */
fun shouldMonitorAccess(state: DashboardUiState, onDashboard: Boolean): Boolean =
    state.onboardingComplete == true &&
        onDashboard &&
        state.charging.observation !is ChargeObservation.Unsupported &&
        state.charging.access?.direct?.ready != true

/**
 * The stats slice of the dashboard state, extracted so its semantics are JVM-testable without the
 * ViewModel: disabled emits the promo default without touching the providers (no stats.db creation);
 * enabling emits a loading marker before Room's first answer; any pipeline failure downgrades to
 * `unavailable` instead of cancelling the caller's whole state flow. The Room-backed flows are
 * built through the providers *inside* the collected flow, so even a synchronous construction
 * failure (broken stats.db) lands in the catch rather than escaping [flatMapLatest].
 */
internal fun statsDashboardStates(
    captureEnabled: Flow<Boolean>,
    health: Flow<CaptureServiceHealth.NudgeOutcome>,
    recentSessions: () -> Flow<List<ChargeSessionSummary>>,
    sessionCount: () -> Flow<Int>,
    currentSession: () -> Flow<StatsLiveSession?>,
): Flow<StatsDashboardState> = captureEnabled.flatMapLatest { enabled ->
    if (!enabled) {
        flowOf(StatsDashboardState())
    } else {
        flow {
            emitAll(
                combine(
                    recentSessions(),
                    sessionCount(),
                    currentSession(),
                    health,
                ) { recent, count, live, nudge ->
                    StatsDashboardState(
                        enabled = true,
                        startFailed = nudge == CaptureServiceHealth.NudgeOutcome.FAILED,
                        lastSession = recent.firstOrNull(),
                        sessionCount = count,
                        live = live,
                    )
                },
            )
        }
            .onStart { emit(StatsDashboardState(enabled = true, loading = true)) }
            .catch { e ->
                log(STATS_FLOW_TAG, Logging.Priority.ERROR) { "Stats dashboard flow failed: ${e.asLog()}" }
                emit(StatsDashboardState(enabled = true, unavailable = true))
            }
    }
}

private val STATS_FLOW_TAG = logTag("Dashboard", "VM", "Stats")
