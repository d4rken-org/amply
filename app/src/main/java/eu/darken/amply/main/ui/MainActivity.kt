package eu.darken.amply.main.ui

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
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
import androidx.lifecycle.compose.LifecycleResumeEffect
import dagger.hilt.android.AndroidEntryPoint
import eu.darken.amply.R
import eu.darken.amply.common.AmplyLinks
import eu.darken.amply.common.theming.AmplyTheme
import eu.darken.amply.battery.ui.BatteryDetailScreen
import eu.darken.amply.diagnostics.ui.DiagnosticsScreen
import eu.darken.amply.diagnostics.ui.DiagnosticsViewModel
import eu.darken.amply.main.ui.dashboard.DashboardScreen
import eu.darken.amply.main.ui.dashboard.DashboardViewModel
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
    private val diagnosticsViewModel: DiagnosticsViewModel by viewModels()

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
                    val diagnosticsState by diagnosticsViewModel.state.collectAsState()
                    var destination by rememberSaveable { mutableStateOf(SettingsDestination.DASHBOARD) }
                var notificationAction by remember { mutableStateOf<NotificationAction?>(null) }
                val notificationLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestPermission(),
                ) { granted ->
                    if (granted) {
                        when (notificationAction) {
                            NotificationAction.START_FULL_CHARGE -> viewModel.startFullCharge()
                            NotificationAction.ENABLE_QUICK_GESTURE -> viewModel.setQuickFullChargeEnabled(true)
                            NotificationAction.ENABLE_CHARGE_ALARM -> viewModel.setChargeAlarmEnabled(true)
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

                    BackHandler(
                        enabled = state.onboardingComplete == true && destination != SettingsDestination.DASHBOARD,
                    ) {
                        destination = when (destination) {
                            // These are entered from the dashboard, not the settings hub.
                            SettingsDestination.SETTINGS,
                            SettingsDestination.RECONNECT_GESTURE,
                            SettingsDestination.BATTERY_DETAIL -> SettingsDestination.DASHBOARD
                            else -> SettingsDestination.SETTINGS
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
                            onAlarmEnabledChange = { enabled ->
                                if (enabled) {
                                    runWithNotifications(NotificationAction.ENABLE_CHARGE_ALARM)
                                } else {
                                    viewModel.setChargeAlarmEnabled(false)
                                }
                            },
                            onAlarmTargetChange = viewModel::setChargeAlarmTarget,
                            onFixNotifications = viewModel::openNotificationSettings,
                            onOpenBatteryDetail = { destination = SettingsDestination.BATTERY_DETAIL },
                            onPinWidget = viewModel::requestPinWidget,
                            onAddTile = viewModel::requestAddTile,
                            onDismissQuickAccess = viewModel::dismissQuickAccess,
                            onNativeSettings = viewModel::openNativeSettings,
                            onOpenShizuku = viewModel::openShizuku,
                            onAllowShizuku = viewModel::requestShizukuPermission,
                            onGrantWss = viewModel::grantWriteSecureSettings,
                            onCopyAdb = viewModel::copyAdbCommand,
                            onPrepareSupportReport = viewModel::prepareDeviceSupportReport,
                            onCopySupportReport = viewModel::copyDeviceSupportReport,
                            onOpenSupportIssue = viewModel::openDeviceSupportIssue,
                            onEmailSupport = viewModel::emailDeviceSupport,
                            onHelp = { destination = SettingsDestination.SUPPORT },
                        )
                        SettingsDestination.SETTINGS -> SettingsScreen(
                            onBack = { destination = SettingsDestination.DASHBOARD },
                            onGeneral = { destination = SettingsDestination.GENERAL },
                            showDiagnostics = state.charging.access?.shizuku?.installed == true,
                            diagnosticsReady = state.charging.access?.shizuku?.ready == true,
                            onDiagnostics = { destination = SettingsDestination.DIAGNOSTICS },
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
                        SettingsDestination.DIAGNOSTICS -> DiagnosticsScreen(
                            state = diagnosticsState,
                            shizuku = state.charging.access?.shizuku,
                            onBack = { destination = SettingsDestination.SETTINGS },
                            onRefresh = viewModel::refresh,
                            onOpenShizuku = viewModel::openShizuku,
                            onAllowShizuku = viewModel::requestShizukuPermission,
                            onCapture = diagnosticsViewModel::captureBaseline,
                            onOpenNativeSettings = viewModel::openNativeSettings,
                            onCompare = diagnosticsViewModel::compare,
                            onShare = ::shareReport,
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
                        SettingsDestination.BATTERY_DETAIL -> BatteryDetailScreen(
                            readout = state.batteryReadout,
                            onBack = { destination = SettingsDestination.DASHBOARD },
                        )
                    }
                }
                }
            }
        }
    }

    private fun shareReport(report: String) {
        startActivity(
            Intent.createChooser(
                Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_SUBJECT, getString(R.string.diagnostics_share_subject))
                    putExtra(Intent.EXTRA_TEXT, report)
                },
                getString(R.string.diagnostics_share),
            ),
        )
    }

    private enum class NotificationAction {
        START_FULL_CHARGE,
        ENABLE_QUICK_GESTURE,
        ENABLE_CHARGE_ALARM,
    }

    companion object {
        const val EXTRA_REQUEST_NOTIFICATIONS = "request_notifications"
    }
}
