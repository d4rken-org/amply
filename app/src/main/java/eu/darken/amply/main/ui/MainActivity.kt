package eu.darken.amply.main.ui

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
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
import eu.darken.amply.BuildConfig
import eu.darken.amply.R
import eu.darken.amply.common.AmplyLinks
import eu.darken.amply.common.theming.AmplyTheme
import eu.darken.amply.charging.core.DeviceInfo
import eu.darken.amply.charging.core.adapter.OemChargingShortcuts
import eu.darken.amply.diagnostics.ui.ContributionWizardScreen
import eu.darken.amply.diagnostics.ui.ContributionWizardViewModel
import eu.darken.amply.main.ui.battery.BatteryHubScreen
import eu.darken.amply.main.ui.battery.BatteryMetricDetailScreen
import eu.darken.amply.main.ui.battery.ChargeTeaserState
import eu.darken.amply.main.ui.dashboard.DashboardScreen
import eu.darken.amply.main.ui.dashboard.DashboardViewModel
import eu.darken.amply.main.ui.dashboard.shouldMonitorAccess
import eu.darken.amply.main.ui.dashboard.shouldShowUpgradePromo
import eu.darken.amply.main.ui.onboarding.OnboardingScreen
import eu.darken.amply.main.ui.qualification.QualificationScreen
import eu.darken.amply.main.ui.qualification.QualificationViewModel
import eu.darken.amply.main.ui.settings.AcknowledgementsScreen
import eu.darken.amply.main.ui.settings.ChargingHistorySettingsScreen
import eu.darken.amply.main.ui.settings.ChargingSettingsScreen
import eu.darken.amply.main.ui.settings.GeneralSettingsScreen
import eu.darken.amply.main.ui.settings.SettingsDestination
import eu.darken.amply.main.ui.settings.SettingsScreen
import eu.darken.amply.main.ui.settings.SettingsViewModel
import eu.darken.amply.main.ui.settings.SupportScreen
import eu.darken.amply.rules.core.PlugKind
import eu.darken.amply.rules.ui.ChargeRuleEditorScreen
import eu.darken.amply.rules.ui.ChargeRulesScreen
import eu.darken.amply.rules.ui.ChargeRulesViewModel
import eu.darken.amply.stats.core.CurveMetricAvailability
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
    private val rulesViewModel: ChargeRulesViewModel by viewModels()
    private val qualificationViewModel: QualificationViewModel by viewModels()

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
                    val qualificationState by qualificationViewModel.state.collectAsState()
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
                    // The rules screen is reached from the dashboard card and from the settings hub,
                    // so Back returns to whichever one opened it.
                    var rulesOrigin by rememberSaveable { mutableStateOf(SettingsDestination.DASHBOARD) }
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
                    val enterRules = { origin: SettingsDestination ->
                        rulesOrigin = origin
                        destination = SettingsDestination.CHARGE_RULES
                    }
                    val rulesState by rulesViewModel.state.collectAsState()
                    val ruleEditor by rulesViewModel.editor.collectAsState()
                var notificationAction by remember { mutableStateOf<NotificationAction?>(null) }
                // Which rule the pending ENABLE_CHARGE_RULE belongs to; recorded at the request
                // because the permission answer arrives asynchronously.
                var pendingRuleId by remember { mutableStateOf<String?>(null) }
                val runNotificationAction: (NotificationAction?) -> Unit = { action ->
                    when (action) {
                        NotificationAction.START_FULL_CHARGE -> viewModel.startFullCharge()
                        NotificationAction.ENABLE_QUICK_GESTURE -> viewModel.setQuickFullChargeEnabled(true)
                        NotificationAction.ENABLE_CHARGE_ALARM -> viewModel.setChargeAlarmEnabled(true)
                        NotificationAction.ENABLE_STATS -> statsViewModel.setCaptureEnabled(true)
                        NotificationAction.ENABLE_CHARGE_RULE ->
                            pendingRuleId?.let { rulesViewModel.setRuleEnabled(it, true) }
                        null -> Unit
                    }
                }
                val notificationLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestPermission(),
                ) { granted ->
                    if (granted) runNotificationAction(notificationAction)
                    // A refused permission leaves the alarm switch off (an alarm that can't alert
                    // is worse than a silent one); the user can retry from the card, and the blocked
                    // warning appears once the alarm is enabled while delivery is still off.
                    notificationAction = null
                    pendingRuleId = null
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
                        runNotificationAction(action)
                    }
                }

                // Runtime-requested only from API 31; below that the manifest permission is granted
                // at install time and the prompt does not exist.
                val bluetoothLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestPermission(),
                ) { rulesViewModel.refreshEditorBluetooth() }
                val requestBluetooth = {
                    if (Build.VERSION.SDK_INT >= 31) {
                        bluetoothLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT)
                    } else {
                        rulesViewModel.refreshEditorBluetooth()
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
                // The same contract as the stats gate: a denial routes to the upgrade screen, a pass
                // routes to the notification prompt (a rule keeps the monitor's quiet notification
                // up), and a third event says the editor's working copy is ready to navigate to.
                LaunchedEffect(Unit) {
                    rulesViewModel.upgradeRequiredEvents.collect {
                        enterUpgrade(SettingsDestination.CHARGE_RULES, false)
                    }
                }
                LaunchedEffect(Unit) {
                    rulesViewModel.proceedWithEnableEvents.collect { ruleId ->
                        pendingRuleId = ruleId
                        runWithNotifications(NotificationAction.ENABLE_CHARGE_RULE)
                    }
                }
                LaunchedEffect(Unit) {
                    rulesViewModel.openEditorEvents.collect {
                        destination = SettingsDestination.CHARGE_RULE_EDIT
                    }
                }
                // Every editor exit — saved, deleted, backed out unchanged, or discarded — arrives
                // here. Navigating from the call sites instead would mean each one re-deciding
                // whether the draft may be abandoned, and one of them getting it wrong.
                LaunchedEffect(Unit) {
                    rulesViewModel.closeEditorEvents.collect {
                        destination = SettingsDestination.CHARGE_RULES
                    }
                }
                // While the editor is open, keep its Bluetooth picture live: the adapter being
                // switched on or off, and devices being paired or unpaired, both change what the
                // list should show while the user is looking at it. Scoped to the destination and
                // to RESUMED, so nothing is registered from anywhere else in the app.
                LifecycleResumeEffect(destination) {
                    val bluetoothWatcher = if (destination == SettingsDestination.CHARGE_RULE_EDIT) {
                        object : BroadcastReceiver() {
                            override fun onReceive(context: Context?, intent: Intent?) {
                                // Terminal states only: the TURNING_ON/TURNING_OFF steps would each
                                // trigger a sweep that answers for an adapter still in motion.
                                val interesting = when (intent?.action) {
                                    BluetoothAdapter.ACTION_STATE_CHANGED -> {
                                        val state = intent.getIntExtra(
                                            BluetoothAdapter.EXTRA_STATE,
                                            BluetoothAdapter.ERROR,
                                        )
                                        state == BluetoothAdapter.STATE_ON || state == BluetoothAdapter.STATE_OFF
                                    }
                                    BluetoothDevice.ACTION_BOND_STATE_CHANGED -> {
                                        val bond = intent.getIntExtra(
                                            BluetoothDevice.EXTRA_BOND_STATE,
                                            BluetoothDevice.ERROR,
                                        )
                                        bond == BluetoothDevice.BOND_BONDED || bond == BluetoothDevice.BOND_NONE
                                    }
                                    else -> false
                                }
                                if (interesting) rulesViewModel.refreshEditorBluetooth()
                            }
                        }.also {
                            ContextCompat.registerReceiver(
                                this@MainActivity,
                                it,
                                IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED).apply {
                                    addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
                                },
                                ContextCompat.RECEIVER_EXPORTED,
                            )
                        }
                    } else {
                        null
                    }
                    onPauseOrDispose {
                        bluetoothWatcher?.let { runCatching { unregisterReceiver(it) } }
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
                    // The Bluetooth grant is made in a system dialog that pauses the activity, so
                    // re-read it on return rather than trusting the value from before.
                    rulesViewModel.refreshEditorBluetooth()
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
                            // The metric detail is only reachable from a hub tile. Both saved keys
                            // are cleared together, so a restored process can't come back to a
                            // metric without the session it belonged to.
                            SettingsDestination.BATTERY_METRIC_DETAIL -> {
                                statsViewModel.closeMetric()
                                destination = SettingsDestination.BATTERY
                            }
                            // Reached from the dashboard card or the settings hub.
                            SettingsDestination.CHARGE_RULES -> destination = rulesOrigin
                            // Asks rather than closes: an edited draft raises the discard
                            // confirmation, and the navigation happens on the close event.
                            SettingsDestination.CHARGE_RULE_EDIT -> rulesViewModel.requestCloseEditor()
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
                            onOpenConditions = { enterRules(SettingsDestination.DASHBOARD) },
                            onOpenBatteryHub = { destination = SettingsDestination.BATTERY },
                            // Re-dispatch the charge service after a failed start (charging card retry).
                            onRetryCapture = { viewModel.nudgeChargeService() },
                            onStartVerification = { viewModel.startEnforcementVerification() },
                            onProveLimit = { destination = SettingsDestination.QUALIFICATION },
                            proveLimitAvailable = BuildConfig.ENABLE_QUALIFICATION_RUN,
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
                            // Badging is settled-aware, unlike the row's own Active/Free subtitle:
                            // an unresolved entitlement must not badge a gated row at a purchaser.
                            showProBadge = shouldShowUpgradePromo(state.upgrade),
                            onUpgrade = { enterUpgrade(SettingsDestination.SETTINGS, true) },
                            onGeneral = { destination = SettingsDestination.GENERAL },
                            gestureEnabled = state.quickFullChargeEnabled,
                            onCharging = { destination = SettingsDestination.CHARGING },
                            activeRuleCount = state.conditions.enabledRules.size,
                            onChargeRules = { enterRules(SettingsDestination.SETTINGS) },
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
                        // Guarded at the destination as well as at the entry point: this can be
                        // restored from saved state after a process death, and a release build must
                        // never resurrect a screen its own flag says does not exist.
                        SettingsDestination.QUALIFICATION -> if (!BuildConfig.ENABLE_QUALIFICATION_RUN) {
                            LaunchedEffect(Unit) { destination = SettingsDestination.DASHBOARD }
                        } else QualificationScreen(
                            state = qualificationState,
                            onExit = { destination = SettingsDestination.DASHBOARD },
                            onRefresh = qualificationViewModel::refreshEligibility,
                            onStart = qualificationViewModel::start,
                            onCancel = qualificationViewModel::cancel,
                            onNext = qualificationViewModel::goNext,
                            onBack = qualificationViewModel::goBack,
                            onDismissResult = {
                                qualificationViewModel.dismissResult()
                                destination = SettingsDestination.DASHBOARD
                            },
                            onOpenIssue = {
                                openContributionIssue(
                                    qualificationState.issueUrl.takeIf { it.isNotBlank() },
                                    qualificationState.reportText,
                                )
                            },
                            onCopyReport = { copyContribution(qualificationState.reportText) },
                            onEmail = { emailContribution(qualificationState.reportText) },
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
                            availablePolicies = state.notificationActionPolicies,
                            selectedPolicyIds = state.notificationActionSelection,
                            onBack = { destination = SettingsDestination.SETTINGS },
                            onGestureEnabledChange = { enabled ->
                                if (enabled) {
                                    runWithNotifications(NotificationAction.ENABLE_QUICK_GESTURE)
                                } else {
                                    viewModel.setQuickFullChargeEnabled(false)
                                }
                            },
                            onAnyLevelChange = viewModel::setQuickFullChargeAnyLevel,
                            onNotificationPolicyToggle = viewModel::toggleGestureNotificationPolicy,
                        )
                        SettingsDestination.CHARGE_RULES -> ChargeRulesScreen(
                            state = rulesState,
                            onBack = { destination = rulesOrigin },
                            // Gated: the ViewModel answers with either the upgrade route or the
                            // editor-ready event, so nobody fills in a condition and is refused at
                            // the end.
                            onAdd = rulesViewModel::requestAddRule,
                            onEdit = rulesViewModel::editRule,
                            onDelete = rulesViewModel::deleteRule,
                            // Enabling passes the gate and the notification prompt; disabling is
                            // direct, so a lapsed entitlement can never trap a running rule.
                            onEnabledChange = { id, enabled ->
                                if (enabled) {
                                    rulesViewModel.requestEnableRule(id)
                                } else {
                                    rulesViewModel.setRuleEnabled(id, false)
                                }
                            },
                            onMove = rulesViewModel::moveRule,
                            onFixBluetoothPermission = requestBluetooth,
                        )
                        // Rendering waits for the working copy: the destination can be restored from
                        // saved state after a process death, when there is no draft left to edit.
                        SettingsDestination.CHARGE_RULE_EDIT -> {
                            val editor = ruleEditor
                            if (editor == null) {
                                LaunchedEffect(Unit) { destination = SettingsDestination.CHARGE_RULES }
                            } else {
                                val plugged = state.batteryReadout?.plugged ?: 0
                                val detectedPlugKind = PlugKind.fromExtraPlugged(plugged)
                                ChargeRuleEditorScreen(
                                    state = editor,
                                    // Read off the dashboard's live battery state, which the root
                                    // already collects — the editor never reaches for it itself.
                                    detectedPlugKind = detectedPlugKind,
                                    chargerUnrecognized = plugged != 0 && detectedPlugKind == null,
                                    // Navigation for all four exits happens on closeEditorEvents.
                                    onCloseRequest = rulesViewModel::requestCloseEditor,
                                    onLabelChange = rulesViewModel::setEditorLabel,
                                    onConditionKindChange = rulesViewModel::setEditorConditionKind,
                                    onDeviceSelect = rulesViewModel::setEditorDevice,
                                    onPlugKindToggle = rulesViewModel::toggleEditorPlugKind,
                                    onPolicySelect = rulesViewModel::setEditorPolicy,
                                    onSave = rulesViewModel::saveEditor,
                                    // The editor's own delete: it closes this editor, unlike the
                                    // list's, which must never touch whatever draft is open.
                                    onDelete = rulesViewModel::deleteEditingRule,
                                    onConfirmDiscard = rulesViewModel::confirmDiscardEditor,
                                    onKeepEditing = rulesViewModel::keepEditing,
                                    onRequestBluetoothPermission = requestBluetooth,
                                )
                            }
                        }
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
                        SettingsDestination.BATTERY -> {
                            val teaser = ChargeTeaserState.from(state.stats, state.batteryReadout)
                            // A live charge already carries its (bounded) curve — and what it
                            // recorded — in the teaser; only a finished one has to be read, and only
                            // while the hub is on screen.
                            val liveSession = (teaser as? ChargeTeaserState.Live)?.session
                            val lastSessionId = (teaser as? ChargeTeaserState.Last)?.summary?.id
                            LaunchedEffect(lastSessionId) { statsViewModel.setHubSession(lastSessionId) }
                            val lastCurve by statsViewModel.hubCurve.collectAsState()
                            val shownCurve = liveSession?.curve
                                ?: if (lastSessionId != null) lastCurve.curve else emptyList()
                            // Tile tappability rides on this, never on the decimated curve above.
                            val shownAvailability = liveSession?.availability
                                ?: if (lastSessionId != null) {
                                    lastCurve.availability
                                } else {
                                    CurveMetricAvailability.NONE
                                }
                            BatteryHubScreen(
                                readout = state.batteryReadout,
                                captureEnabled = state.stats.enabled,
                                teaser = teaser,
                                showProBadge = shouldShowUpgradePromo(state.upgrade),
                                onBack = { destination = SettingsDestination.DASHBOARD },
                                onOpenHistory = { destination = SettingsDestination.CHARGE_HISTORY },
                                // Enable-only: turning recording back off lives in Settings › Charging history.
                                onEnableCapture = {
                                    captureGateOrigin = SettingsDestination.BATTERY
                                    statsViewModel.requestEnableCapture()
                                },
                                onOpenSession = { id -> openSession(id, SettingsDestination.BATTERY) },
                                // The same fold the dashboard's charging card reads, so the two
                                // surfaces can never quote different charge times.
                                chargeTime = state.chargeTime,
                                // A tile only offers the tap when that metric has samples in the
                                // shown charge, so there is always a session id to pair it with.
                                onOpenMetric = { metric ->
                                    val sessionId = liveSession?.id ?: lastSessionId
                                    if (sessionId != null) {
                                        statsViewModel.openMetric(sessionId, metric)
                                        destination = SettingsDestination.BATTERY_METRIC_DETAIL
                                    }
                                },
                                curve = shownCurve,
                                availability = shownAvailability,
                            )
                        }
                        SettingsDestination.BATTERY_METRIC_DETAIL -> {
                            val metricDetail by statsViewModel.metricDetailState.collectAsState()
                            BatteryMetricDetailScreen(
                                state = metricDetail,
                                onBack = {
                                    statsViewModel.closeMetric()
                                    destination = SettingsDestination.BATTERY
                                },
                            )
                        }
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
                                // Live wattage only for the session that is actually charging now. The
                                // detail query is by id and unrestricted, so it can resolve a dangling
                                // open row from an earlier boot that the boot-scoped live query rejects
                                // — attributing the current draw to it would be a fabrication.
                                readout = state.batteryReadout.takeIf {
                                    val live = state.stats.live
                                    live != null && statsDetail?.summary?.id == live.id
                                },
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
        ENABLE_CHARGE_RULE,
    }

    companion object {
        const val EXTRA_REQUEST_NOTIFICATIONS = "request_notifications"
        const val EXTRA_OPEN_UPGRADE = "open_upgrade"
        private const val SUPPORT_EMAIL = "support@darken.eu"
    }
}
