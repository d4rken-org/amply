package eu.darken.amply.main.ui

import android.Manifest
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.core.content.getSystemService
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import dagger.hilt.android.AndroidEntryPoint
import eu.darken.amply.R
import eu.darken.amply.common.AmplyLinks
import eu.darken.amply.common.theming.AmplyTheme
import eu.darken.amply.charging.core.DeviceInfo
import eu.darken.amply.charging.core.adapter.OemChargingShortcuts
import eu.darken.amply.diagnostics.ui.ContributionWizardScreen
import eu.darken.amply.diagnostics.ui.ContributionWizardViewModel
import eu.darken.amply.main.ui.battery.BatteryHubScreen
import eu.darken.amply.main.ui.battery.ChargeTeaserState
import eu.darken.amply.main.ui.dashboard.DashboardScreen
import eu.darken.amply.main.ui.dashboard.DashboardViewModel
import eu.darken.amply.main.ui.dashboard.shouldMonitorAccess
import eu.darken.amply.main.ui.onboarding.OnboardingScreen
import eu.darken.amply.main.ui.settings.AcknowledgementsScreen
import eu.darken.amply.main.ui.settings.ChargingHistorySettingsScreen
import eu.darken.amply.main.ui.settings.ChargingSettingsScreen
import eu.darken.amply.main.ui.settings.GeneralSettingsScreen
import eu.darken.amply.main.ui.settings.SettingsDestination
import eu.darken.amply.main.ui.settings.SettingsScreen
import eu.darken.amply.main.ui.settings.SettingsViewModel
import eu.darken.amply.main.ui.settings.SupportScreen
import eu.darken.amply.stats.ui.ChargeHistoryScreen
import eu.darken.amply.stats.ui.StatsSessionDetailScreen
import eu.darken.amply.stats.ui.StatsViewModel
import eu.darken.amply.upgrade.ui.UpgradeScreenHost

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    private val viewModel: DashboardViewModel by viewModels()
    private val settingsViewModel: SettingsViewModel by viewModels()
    private val contributionViewModel: ContributionWizardViewModel by viewModels()
    private val statsViewModel: StatsViewModel by viewModels()

    // Compose-observable so a widget launch that reuses an already-running activity (SINGLE_TOP →
    // onNewIntent, which does not re-run LaunchedEffect(Unit)) still triggers the permission flow.
    private val pendingNotificationRequest = mutableStateOf(false)
    private val pendingUpgradeRequest = mutableStateOf(false)

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        consumeNotificationRequest(intent)
        consumeUpgradeRequest(intent)
        setIntent(intent)
    }

    // Read the request flag once and strip it, so a later recreation (config change) can't replay it.
    private fun consumeNotificationRequest(intent: Intent) {
        if (intent.getBooleanExtra(EXTRA_REQUEST_NOTIFICATIONS, false)) {
            intent.removeExtra(EXTRA_REQUEST_NOTIFICATIONS)
            pendingNotificationRequest.value = true
        }
    }

    // Same consume-and-strip contract: a rotation must not re-open the upgrade screen on top of
    // whatever the user navigated to since.
    private fun consumeUpgradeRequest(intent: Intent) {
        if (intent.getBooleanExtra(EXTRA_OPEN_UPGRADE, false)) {
            intent.removeExtra(EXTRA_OPEN_UPGRADE)
            pendingUpgradeRequest.value = true
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        consumeNotificationRequest(intent)
        consumeUpgradeRequest(intent)
        enableEdgeToEdge()
        setContent {
            val themeState by settingsViewModel.themeState.collectAsState()
            // Collected here at the composition root, NOT down in the CHARGING_HISTORY_SETTINGS branch
            // that consumes it. `statsViewModel` is a lazy `by viewModels()` delegate, so collecting at
            // the destination would construct the ViewModel — and thus start its "eager" sharing — in
            // the very frame the screen appears, rendering the placeholder default before the stored
            // value lands (a drag started in that window would also be reset, since the slider re-keys
            // its local state on the incoming value). Collecting at the root makes it an already-resolved
            // source by the time the user is two levels deep in Settings, the same way `captureEnabled`
            // uses the resolved dashboard state instead of its own first emission. Don't move it down.
            val retentionDays by statsViewModel.retentionDays.collectAsState()
            AmplyTheme(themeState) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    val state by viewModel.state.collectAsState()
                    val debugState by settingsViewModel.debugState.collectAsState()
                    val contributionState by contributionViewModel.state.collectAsState()
                    var destination by rememberSaveable { mutableStateOf(SettingsDestination.DASHBOARD) }
                    // Where a back-out of the contribution wizard returns to (set on each entry).
                    var wizardOrigin by rememberSaveable { mutableStateOf(SettingsDestination.DASHBOARD) }
                    // Where the session-detail screen returns to: the history list when opened from it,
                    // or the battery hub when opened from its charge teaser.
                    var detailOrigin by rememberSaveable { mutableStateOf(SettingsDestination.CHARGE_HISTORY) }
                    // The upgrade screen is reached from five different surfaces, so it carries its
                    // own return target instead of assuming a parent. Both Back and the pitch's
                    // auto-dismiss use it, so an upgrade completed from a gate lands the user back
                    // where they were trying to go.
                    var upgradeOrigin by rememberSaveable { mutableStateOf(SettingsDestination.DASHBOARD) }
                    // Whether this visit is the "check my status" entry (which never auto-dismisses)
                    // or a gate/promo asking for the upgrade.
                    var upgradeManage by rememberSaveable { mutableStateOf(false) }
                    // Which surface asked for capture: the denial event arrives asynchronously, so
                    // the origin is recorded at the request instead of read off `destination` when
                    // the answer lands (the user may have navigated on by then).
                    var captureGateOrigin by rememberSaveable { mutableStateOf(SettingsDestination.BATTERY) }
                    val enterUpgrade = { origin: SettingsDestination, manage: Boolean ->
                        // Never record the upgrade screen as its own return target: a second entry
                        // while it is already open would strand Back on this screen.
                        if (origin != SettingsDestination.UPGRADE) upgradeOrigin = origin
                        upgradeManage = manage
                        destination = SettingsDestination.UPGRADE
                    }
                    val leaveUpgrade = { destination = upgradeOrigin }
                    val leaveWizard = {
                        contributionViewModel.exitWizard()
                        destination = wizardOrigin
                    }
                    val enterWizard = { origin: SettingsDestination ->
                        wizardOrigin = origin
                        destination = SettingsDestination.DIAGNOSTICS
                    }
                    // Two surfaces open a session detail (the hub's teaser and the history list), and
                    // each needs the id, the origin, and the destination set together — split across
                    // call sites, a forgotten origin assignment silently sends Back to whichever screen
                    // was used last time.
                    val openSession = { id: Long, origin: SettingsDestination ->
                        statsViewModel.openSession(id)
                        detailOrigin = origin
                        destination = SettingsDestination.STATS_SESSION_DETAIL
                    }
                var notificationAction by remember { mutableStateOf<NotificationAction?>(null) }
                val notificationLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestPermission(),
                ) { granted ->
                    if (granted) {
                        when (notificationAction) {
                            NotificationAction.START_FULL_CHARGE -> viewModel.startFullCharge()
                            NotificationAction.ENABLE_QUICK_GESTURE -> viewModel.setQuickFullChargeEnabled(true)
                            NotificationAction.ENABLE_CHARGE_ALARM -> viewModel.setChargeAlarmEnabled(true)
                            NotificationAction.ENABLE_STATS -> statsViewModel.setCaptureEnabled(true)
                            null -> Unit
                        }
                    }
                    // A refused permission leaves the alarm switch off (an alarm that can't alert
                    // is worse than a silent one); the user can retry from the card, and the blocked
                    // warning appears once the alarm is enabled while delivery is still off.
                    notificationAction = null
                }

                fun runWithNotifications(action: NotificationAction) {
                    if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(
                            this,
                            Manifest.permission.POST_NOTIFICATIONS,
                        ) != PackageManager.PERMISSION_GRANTED
                    ) {
                        notificationAction = action
                        notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    } else {
                        when (action) {
                            NotificationAction.START_FULL_CHARGE -> viewModel.startFullCharge()
                            NotificationAction.ENABLE_QUICK_GESTURE -> viewModel.setQuickFullChargeEnabled(true)
                            NotificationAction.ENABLE_CHARGE_ALARM -> viewModel.setChargeAlarmEnabled(true)
                            NotificationAction.ENABLE_STATS -> statsViewModel.setCaptureEnabled(true)
                        }
                    }
                }

                LaunchedEffect(pendingNotificationRequest.value) {
                    if (pendingNotificationRequest.value) {
                        pendingNotificationRequest.value = false
                        runWithNotifications(NotificationAction.START_FULL_CHARGE)
                    }
                }
                // The tile and widget send a free user here rather than doing nothing. They come from
                // outside the app, so the dashboard is the only sensible place to return to.
                LaunchedEffect(pendingUpgradeRequest.value) {
                    if (pendingUpgradeRequest.value) {
                        pendingUpgradeRequest.value = false
                        enterUpgrade(SettingsDestination.DASHBOARD, false)
                    }
                }
                // Both ViewModels answer a denied gate with an event rather than navigating
                // themselves; the origin is the surface the user was actually on.
                LaunchedEffect(Unit) {
                    viewModel.upgradeRequiredEvents.collect {
                        enterUpgrade(SettingsDestination.DASHBOARD, false)
                    }
                }
                // Keyed on Unit, not on `destination`: re-keying would cancel and restart the
                // collector on every navigation, and a denial emitted in that gap would be lost.
                // The origin comes from the recorded request instead.
                LaunchedEffect(Unit) {
                    statsViewModel.upgradeRequiredEvents.collect {
                        enterUpgrade(captureGateOrigin, false)
                    }
                }
                // The entitlement check passed; only now is the notification permission worth asking
                // for. Reversing the two would put a user through a system prompt and then refuse.
                LaunchedEffect(Unit) {
                    statsViewModel.proceedWithEnableEvents.collect {
                        runWithNotifications(NotificationAction.ENABLE_STATS)
                    }
                }
                LifecycleResumeEffect(Unit) {
                    viewModel.refresh()
                    // Separate from refresh(): that one runs on every battery broadcast, and a
                    // billing round-trip at that cadence would be wasteful.
                    viewModel.refreshUpgradeState()
                    // Also re-checks widget placement after returning from the launcher's pin dialog.
                    viewModel.refreshQuickAccessPresence()
                    // Re-check Shizuku for the contribution wizard too, so granting access from its intro card
                    // (which pauses the activity behind Shizuku's dialog) updates the card on return.
                    contributionViewModel.refreshStatus()
                    val nudge = viewModel.nudgeChargeService()
                    onPauseOrDispose { nudge.cancel() }
                }
                DisposableEffect(Unit) {
                    val receiver = object : BroadcastReceiver() {
                        override fun onReceive(context: Context?, intent: Intent?) {
                            viewModel.refresh()
                        }
                    }
                    ContextCompat.registerReceiver(
                        this@MainActivity,
                        receiver,
                        IntentFilter(Intent.ACTION_BATTERY_CHANGED),
                        ContextCompat.RECEIVER_EXPORTED,
                    )
                    onDispose { runCatching { unregisterReceiver(receiver) } }
                }
                // An external grant (`adb pm grant`, or Shizuku authorised in its own manager) fires no OS
                // callback while Amply stays foregrounded, so without this the dashboard only re-checks on the
                // next battery broadcast (~1 min). While the setup card is up, watch for the grant and stop the
                // instant it lands. RESUMED-scoped so it never polls in the background.
                val lifecycle = LocalLifecycleOwner.current.lifecycle
                val awaitingAccessGrant =
                    shouldMonitorAccess(state, onDashboard = destination == SettingsDestination.DASHBOARD)
                LaunchedEffect(awaitingAccessGrant) {
                    if (!awaitingAccessGrant) return@LaunchedEffect
                    lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                        viewModel.monitorAccessWhileAwaitingGrant()
                    }
                }

                    BackHandler(
                        enabled = state.onboardingComplete == true && destination != SettingsDestination.DASHBOARD,
                    ) {
                        when (destination) {
                            // The wizard clears its raw session and returns to whichever surface opened it.
                            SettingsDestination.DIAGNOSTICS -> leaveWizard()
                            // These are entered from the dashboard, not the settings hub. CHARGING is
                            // deliberately NOT among them: it lives under the hub, so system Back must
                            // return there — the same place its top-bar Back goes.
                            SettingsDestination.SETTINGS,
                            SettingsDestination.BATTERY -> destination = SettingsDestination.DASHBOARD
                            // The history list is only reachable from the battery hub's top bar.
                            SettingsDestination.CHARGE_HISTORY -> destination = SettingsDestination.BATTERY
                            // The session detail returns to whichever surface opened it.
                            SettingsDestination.STATS_SESSION_DETAIL -> {
                                statsViewModel.closeSession()
                                destination = detailOrigin
                            }
                            // Same reasoning: reached from five surfaces, returns to the recorded one.
                            SettingsDestination.UPGRADE -> leaveUpgrade()
                            else -> destination = SettingsDestination.SETTINGS
                        }
                    }

                when (state.onboardingComplete) {
                    null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                    false -> OnboardingScreen(onContinue = viewModel::completeOnboarding)
                    true -> when (destination) {
                        SettingsDestination.DASHBOARD -> DashboardScreen(
                            state = state,
                            adbCommand = viewModel.adbGrantCommand,
                            onRefresh = viewModel::refresh,
                            onSettings = { destination = SettingsDestination.SETTINGS },
                            onStartFull = { runWithNotifications(NotificationAction.START_FULL_CHARGE) },
                            onRestore = viewModel::restoreNow,
                            onApply = viewModel::applyPolicy,
                            onQuickFullChargeChange = { enabled ->
                                if (enabled) {
                                    runWithNotifications(NotificationAction.ENABLE_QUICK_GESTURE)
                                } else {
                                    viewModel.setQuickFullChargeEnabled(false)
                                }
                            },
                            onAlarmEnabledChange = { enabled ->
                                if (enabled) {
                                    runWithNotifications(NotificationAction.ENABLE_CHARGE_ALARM)
                                } else {
                                    viewModel.setChargeAlarmEnabled(false)
                                }
                            },
                            onAlarmTargetChange = viewModel::setChargeAlarmTarget,
                            onFixNotifications = viewModel::openNotificationSettings,
                            onOpenBatteryHub = { destination = SettingsDestination.BATTERY },
                            // Re-dispatch the charge service after a failed start (charging card retry).
                            onRetryCapture = { viewModel.nudgeChargeService() },
                            onPinWidget = viewModel::requestPinWidget,
                            onAddTile = viewModel::requestAddTile,
                            onDismissQuickAccess = viewModel::dismissQuickAccess,
                            onDismissInterruption = viewModel::dismissInterruption,
                            onNativeSettings = viewModel::openNativeSettings,
                            onOpenShizuku = viewModel::openShizuku,
                            onAllowShizuku = viewModel::requestShizukuPermission,
                            onGrantWss = viewModel::grantWriteSecureSettings,
                            onCopyAdb = viewModel::copyAdbCommand,
                            onCopyWebUsbLink = viewModel::copyWebUsbLink,
                            onOpenContribution = { enterWizard(SettingsDestination.DASHBOARD) },
                            onPrepareSupportReport = viewModel::prepareDeviceSupportReport,
                            onCopySupportReport = viewModel::copyDeviceSupportReport,
                            onOpenSupportIssue = viewModel::openDeviceSupportIssue,
                            onEmailSupport = viewModel::emailDeviceSupport,
                            onHelp = { destination = SettingsDestination.SUPPORT },
                            onUpgrade = { enterUpgrade(SettingsDestination.DASHBOARD, false) },
                        )
                        SettingsDestination.UPGRADE -> UpgradeScreenHost(
                            manage = upgradeManage,
                            onBack = leaveUpgrade,
                        )
                        SettingsDestination.SETTINGS -> SettingsScreen(
                            onBack = { destination = SettingsDestination.DASHBOARD },
                            // The settings entry is the status view: an existing purchaser opening it
                            // must not be bounced straight back out by the pitch's auto-dismiss.
                            isPro = state.upgrade?.isPro == true,
                            onUpgrade = { enterUpgrade(SettingsDestination.SETTINGS, true) },
                            onGeneral = { destination = SettingsDestination.GENERAL },
                            gestureEnabled = state.quickFullChargeEnabled,
                            onCharging = { destination = SettingsDestination.CHARGING },
                            captureEnabled = state.stats.enabled,
                            onChargingHistory = {
                                destination = SettingsDestination.CHARGING_HISTORY_SETTINGS
                            },
                            // Offered whenever this device is one we want contribution data for (unsupported/lab),
                            // regardless of whether Shizuku is installed yet — the wizard nudges the install.
                            // Withheld where a settings diff can discover nothing, so nobody is walked into a
                            // capture that cannot be delivered (see AdapterSupport.guidedCaptureUseful).
                            showDiagnostics = state.charging.contributionWanted && state.charging.guidedCaptureUseful,
                            diagnosticsReady = state.charging.access?.shizuku?.ready == true,
                            onDiagnostics = { enterWizard(SettingsDestination.SETTINGS) },
                            onSupport = { destination = SettingsDestination.SUPPORT },
                            onChangelog = { settingsViewModel.openUrl(AmplyLinks.CHANGELOG) },
                            onAcknowledgements = { destination = SettingsDestination.ACKNOWLEDGEMENTS },
                            onPrivacy = { settingsViewModel.openUrl(AmplyLinks.PRIVACY_POLICY) },
                        )
                        SettingsDestination.GENERAL -> GeneralSettingsScreen(
                            state = themeState,
                            onBack = { destination = SettingsDestination.SETTINGS },
                            onModeChange = settingsViewModel::setThemeMode,
                            onStyleChange = settingsViewModel::setThemeStyle,
                            onColorChange = settingsViewModel::setThemeColor,
                        )
                        // Guarded at the destination too, not just at the two entry points: this can be restored
                        // from saved state after a process death, which would otherwise resurrect the wizard on a
                        // device where it can never produce a deliverable capture. Rendering waits for adapter
                        // selection, because guidedCaptureUseful defaults permissive — composing on the default
                        // would flash the wizard for a frame before the resolved state redirects away from it.
                        SettingsDestination.DIAGNOSTICS -> if (!state.charging.adapterResolved) Unit
                        else if (!state.charging.guidedCaptureUseful) {
                            LaunchedEffect(Unit) { leaveWizard() }
                        } else ContributionWizardScreen(
                            state = contributionState,
                            onExit = leaveWizard,
                            onRefreshStatus = contributionViewModel::refreshStatus,
                            onOpenShizuku = viewModel::openShizuku,
                            onAllowShizuku = viewModel::requestShizukuPermission,
                            onFeatureNameChange = contributionViewModel::setFeatureName,
                            onRomVersionChange = contributionViewModel::setRomVersion,
                            onNotesChange = contributionViewModel::setNotes,
                            onPendingLabelChange = contributionViewModel::setPendingLabel,
                            onOpenNativeSettings = ::openContributionNativeSettings,
                            onCaptureMode = contributionViewModel::captureMode,
                            onSetEffect = contributionViewModel::setEffect,
                            onUndoLast = contributionViewModel::undoLastCapture,
                            onRestart = contributionViewModel::restartSession,
                            onRevealRow = contributionViewModel::revealRow,
                            onToggleInclude = contributionViewModel::toggleInclude,
                            onNext = contributionViewModel::goNext,
                            onBack = contributionViewModel::goBack,
                            onOpenIssue = {
                                openContributionIssue(contributionState.issueUrl, contributionState.reportText)
                            },
                            onCopyReport = { copyContribution(contributionState.reportText) },
                            onEmail = { emailContribution(contributionState.reportText) },
                        )
                        SettingsDestination.SUPPORT -> SupportScreen(
                            state = debugState,
                            onBack = { destination = SettingsDestination.SETTINGS },
                            onDocumentation = { settingsViewModel.openUrl(AmplyLinks.GITHUB) },
                            onIssueTracker = { settingsViewModel.openUrl(AmplyLinks.ISSUES) },
                            onContact = settingsViewModel::contactSupport,
                            onStartDebugLog = settingsViewModel::startDebugLog,
                            onStopDebugLog = settingsViewModel::stopDebugLog,
                            onShareDebugLog = settingsViewModel::shareLatestDebugLog,
                            onClearDebugLogs = settingsViewModel::clearDebugLogs,
                        )
                        SettingsDestination.ACKNOWLEDGEMENTS -> AcknowledgementsScreen(
                            onBack = { destination = SettingsDestination.SETTINGS },
                            onOpenUrl = settingsViewModel::openUrl,
                        )
                        SettingsDestination.CHARGING -> ChargingSettingsScreen(
                            gestureEnabled = state.quickFullChargeEnabled,
                            anyLevelEnabled = state.quickFullChargeAnyLevel,
                            // Exactly the dashboard card's gate, so settings can never switch the
                            // gesture on where the card correctly forbids it.
                            canEnableGesture = state.charging.reconnectSupported && state.charging.canApply,
                            onBack = { destination = SettingsDestination.SETTINGS },
                            onGestureEnabledChange = { enabled ->
                                if (enabled) {
                                    runWithNotifications(NotificationAction.ENABLE_QUICK_GESTURE)
                                } else {
                                    viewModel.setQuickFullChargeEnabled(false)
                                }
                            },
                            onAnyLevelChange = viewModel::setQuickFullChargeAnyLevel,
                        )
                        SettingsDestination.CHARGING_HISTORY_SETTINGS -> ChargingHistorySettingsScreen(
                            // Both values come from already-resolved sources (the dashboard state and
                            // the root-collected retention flow), so neither the switch nor the slider
                            // can render a placeholder for a frame while a first emission lands.
                            captureEnabled = state.stats.enabled,
                            retentionDays = retentionDays,
                            onBack = { destination = SettingsDestination.SETTINGS },
                            // Enabling goes through the entitlement gate first, which answers with
                            // either the upgrade route or the permission flow. Disabling is direct:
                            // a lapsed entitlement must never trap a user with a running service.
                            onCaptureEnabledChange = { enabled ->
                                if (enabled) {
                                    captureGateOrigin = SettingsDestination.CHARGING_HISTORY_SETTINGS
                                    statsViewModel.requestEnableCapture()
                                } else {
                                    statsViewModel.setCaptureEnabled(false)
                                }
                            },
                            onRetentionChange = statsViewModel::setRetentionDays,
                        )
                        // The hub reads the battery and the capture flag straight from the dashboard
                        // state (already collected and resolved, so the opt-in card can't flash on for
                        // a frame beside a teaser saying a charge is being recorded) — deliberately not
                        // the stats ViewModel's history flow, which is what would open stats.db.
                        SettingsDestination.BATTERY -> BatteryHubScreen(
                            readout = state.batteryReadout,
                            captureEnabled = state.stats.enabled,
                            teaser = ChargeTeaserState.from(state.stats, state.batteryReadout),
                            onBack = { destination = SettingsDestination.DASHBOARD },
                            onOpenHistory = { destination = SettingsDestination.CHARGE_HISTORY },
                            // Enable-only: turning recording back off lives in Settings › Charging history.
                            onEnableCapture = {
                                captureGateOrigin = SettingsDestination.BATTERY
                                statsViewModel.requestEnableCapture()
                            },
                            onOpenSession = { id -> openSession(id, SettingsDestination.BATTERY) },
                        )
                        // The Room-backed session list is collected only here, so the stats DB isn't
                        // opened just by visiting the hub — a user who never enables statistics never
                        // creates stats.db by looking at their battery.
                        SettingsDestination.CHARGE_HISTORY -> {
                            val historyState by statsViewModel.historyState.collectAsState()
                            ChargeHistoryScreen(
                                state = historyState,
                                onBack = { destination = SettingsDestination.BATTERY },
                                onOpenSession = { id ->
                                    openSession(id, SettingsDestination.CHARGE_HISTORY)
                                },
                                onClearData = statsViewModel::clearData,
                            )
                        }
                        SettingsDestination.STATS_SESSION_DETAIL -> {
                            val statsDetail by statsViewModel.detailState.collectAsState()
                            StatsSessionDetailScreen(
                                state = statsDetail,
                                onBack = {
                                    statsViewModel.closeSession()
                                    destination = detailOrigin
                                },
                            )
                        }
                    }
                }
                }
            }
        }
    }

    /** Opens the OEM's battery-protection screen when we can resolve one, else the generic battery-saver fallback. */
    private fun openContributionNativeSettings() {
        val intent = OemChargingShortcuts.resolve(this, DeviceInfo.current(this))
        if (intent != null) {
            runCatching { startActivity(intent) }.onFailure { viewModel.openNativeSettings() }
        } else {
            viewModel.openNativeSettings()
        }
    }

    /** Launches the prefilled issue when it fits in a URL; otherwise copies the report and opens a blank issue. */
    private fun openContributionIssue(url: String?, report: String?) {
        if (url != null) {
            val launched = runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }.isSuccess
            if (launched) return
        }
        report?.let { copyToClipboard(it) }
        val opened = runCatching {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("${AmplyLinks.ISSUES}/new")))
        }.isSuccess
        toast(if (opened) R.string.contribution_report_copied else R.string.setup_unsupported_no_browser)
    }

    private fun copyContribution(report: String?) {
        if (report == null) return
        copyToClipboard(report)
        toast(R.string.contribution_report_copied)
    }

    private fun emailContribution(report: String?) {
        if (report == null) return
        val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:")).apply {
            putExtra(Intent.EXTRA_EMAIL, arrayOf(SUPPORT_EMAIL))
            putExtra(Intent.EXTRA_SUBJECT, getString(R.string.contribution_share_subject))
            putExtra(Intent.EXTRA_TEXT, report)
        }
        runCatching { startActivity(intent) }.onFailure {
            copyToClipboard(report)
            toast(R.string.setup_unsupported_no_email)
        }
    }

    private fun copyToClipboard(text: String) {
        getSystemService<ClipboardManager>()
            ?.setPrimaryClip(ClipData.newPlainText(getString(R.string.contribution_share_subject), text))
    }

    private fun toast(resId: Int) = Toast.makeText(this, resId, Toast.LENGTH_SHORT).show()

    private enum class NotificationAction {
        START_FULL_CHARGE,
        ENABLE_QUICK_GESTURE,
        ENABLE_CHARGE_ALARM,
        ENABLE_STATS,
    }

    companion object {
        const val EXTRA_REQUEST_NOTIFICATIONS = "request_notifications"
        const val EXTRA_OPEN_UPGRADE = "open_upgrade"
        private const val SUPPORT_EMAIL = "support@darken.eu"
    }
}
