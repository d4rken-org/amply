package eu.darken.amply.main.ui.dashboard

import androidx.compose.runtime.Composable

/**
 * The screen takes ~30 callbacks; funnel fixtures through one renderer so a test only describes the
 * state and the callbacks it actually asserts on.
 */
@Composable
internal fun DashboardScreenUnderTest(
    state: DashboardUiState,
    onOpenShizuku: () -> Unit = {},
    onAllowShizuku: () -> Unit = {},
    onUpgrade: () -> Unit = {},
) {
    DashboardScreen(
        state = state,
        adbCommand = "",
        onRefresh = {},
        onSettings = {},
        onStartFull = {},
        onRestore = {},
        onApply = {},
        onQuickFullChargeChange = {},
        onAlarmEnabledChange = {},
        onAlarmTargetChange = {},
        onFixNotifications = {},
        onOpenBatteryHub = {},
        onRetryCapture = {},
        onPinWidget = {},
        onAddTile = {},
        onDismissQuickAccess = {},
        onDismissInterruption = {},
        onNativeSettings = {},
        onOpenShizuku = onOpenShizuku,
        onAllowShizuku = onAllowShizuku,
        onGrantWss = {},
        onCopyAdb = {},
        onCopyWebUsbLink = {},
        onOpenContribution = {},
        onPrepareSupportReport = {},
        onCopySupportReport = {},
        onOpenSupportIssue = {},
        onEmailSupport = {},
        onHelp = {},
        onUpgrade = onUpgrade,
    )
}
