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
import eu.darken.amply.main.ui.dashboard.DashboardScreen
import eu.darken.amply.main.ui.dashboard.DashboardViewModel
import eu.darken.amply.main.ui.dashboard.shouldMonitorAccess
import eu.darken.amply.main.ui.onboarding.OnboardingScreen
import eu.darken.amply.main.ui.settings.AcknowledgementsScreen
import eu.darken.amply.main.ui.settings.GeneralSettingsScreen
import eu.darken.amply.main.ui.settings.ReconnectGestureSettingsScreen
import eu.darken.amply.main.ui.settings.SettingsDestination
import eu.darken.amply.main.ui.settings.SettingsScreen
import eu.darken.amply.main.ui.settings.SettingsViewModel
import eu.darken.amply.main.ui.settings.SupportScreen

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    private val viewModel: DashboardViewModel by viewModels()
    private val settingsViewModel: SettingsViewModel by viewModels()
    private val contributionViewModel: ContributionWizardViewModel by viewModels()

    // Compose-observable so a widget launch that reuses an already-running activity (SINGLE_TOP →
    // onNewIntent, which does not re-run LaunchedEffect(Unit)) still triggers the permission flow.
    private val pendingNotificationRequest = mutableStateOf(false)

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        consumeNotificationRequest(intent)
        setIntent(intent)
    }

    // Read the request flag once and strip it, so a later recreation (config change) can't replay it.
    private fun consumeNotificationRequest(intent: Intent) {
        if (intent.getBooleanExtra(EXTRA_REQUEST_NOTIFICATIONS, false)) {
            intent.removeExtra(EXTRA_REQUEST_NOTIFICATIONS)
            pendingNotificationRequest.value = true
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        consumeNotificationRequest(intent)
        enableEdgeToEdge()
        setContent {
            val themeState by settingsViewModel.themeState.collectAsState()
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
                    val leaveWizard = {
                        contributionViewModel.exitWizard()
                        destination = wizardOrigin
                    }
                    val enterWizard = { origin: SettingsDestination ->
                        wizardOrigin = origin
                        destination = SettingsDestination.DIAGNOSTICS
                    }
                var notificationAction by remember { mutableStateOf<NotificationAction?>(null) }
                val notificationLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestPermission(),
                ) { granted ->
                    if (granted) {
                        when (notificationAction) {
                            NotificationAction.START_FULL_CHARGE -> viewModel.startFullCharge()
                            NotificationAction.ENABLE_QUICK_GESTURE -> viewModel.setQuickFullChargeEnabled(true)
                            null -> Unit
                        }
                    }
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
                        }
                    }
                }

                LaunchedEffect(pendingNotificationRequest.value) {
                    if (pendingNotificationRequest.value) {
                        pendingNotificationRequest.value = false
                        runWithNotifications(NotificationAction.START_FULL_CHARGE)
                    }
                }
                LifecycleResumeEffect(Unit) {
                    viewModel.refresh()
                    // Also re-checks widget placement after returning from the launcher's pin dialog.
                    viewModel.refreshQuickAccessPresence()
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
                            // RECONNECT_GESTURE is entered from the dashboard card, not the settings hub.
                            SettingsDestination.SETTINGS,
                            SettingsDestination.RECONNECT_GESTURE -> destination = SettingsDestination.DASHBOARD
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
                            onOpenReconnectSettings = {
                                destination = SettingsDestination.RECONNECT_GESTURE
                            },
                            onPinWidget = viewModel::requestPinWidget,
                            onAddTile = viewModel::requestAddTile,
                            onDismissQuickAccess = viewModel::dismissQuickAccess,
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
                        )
                        SettingsDestination.SETTINGS -> SettingsScreen(
                            onBack = { destination = SettingsDestination.DASHBOARD },
                            onGeneral = { destination = SettingsDestination.GENERAL },
                            // Offered whenever this device is one we want contribution data for (unsupported/lab),
                            // regardless of whether Shizuku is installed yet — the wizard nudges the install.
                            showDiagnostics = state.charging.contributionWanted,
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
                        SettingsDestination.DIAGNOSTICS -> ContributionWizardScreen(
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
                        SettingsDestination.RECONNECT_GESTURE -> ReconnectGestureSettingsScreen(
                            gestureEnabled = state.quickFullChargeEnabled,
                            anyLevelEnabled = state.quickFullChargeAnyLevel,
                            onBack = { destination = SettingsDestination.DASHBOARD },
                            onAnyLevelChange = viewModel::setQuickFullChargeAnyLevel,
                        )
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
    }

    companion object {
        const val EXTRA_REQUEST_NOTIFICATIONS = "request_notifications"
        private const val SUPPORT_EMAIL = "support@darken.eu"
    }
}
