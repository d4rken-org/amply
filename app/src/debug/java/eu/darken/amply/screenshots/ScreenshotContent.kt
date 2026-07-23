// Play Store screenshot content. These composables render full screens from mock state so the
// screenshotTest source set (app/src/screenshotTest) can capture them to PNGs on the JVM — no device
// or emulator. They live in the debug source set so they never ship in a release build, and each one
// is exercised by an IDE @Preview at the bottom of the file for quick visual iteration.
package eu.darken.amply.screenshots

import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import eu.darken.amply.charging.core.BackendKind
import eu.darken.amply.charging.core.ChargeObservation
import eu.darken.amply.charging.core.ChargePolicy
import eu.darken.amply.charging.core.ChargingState
import eu.darken.amply.charging.core.DeviceInfo
import eu.darken.amply.charging.core.access.AccessSnapshot
import eu.darken.amply.charging.core.access.BackendStatus
import eu.darken.amply.common.ca.toCaString
import eu.darken.amply.common.compose.PreviewWrapper
import eu.darken.amply.common.theming.ThemeState
import eu.darken.amply.fullcharge.core.ChargeSessionRecord
import eu.darken.amply.main.ui.dashboard.DashboardScreen
import eu.darken.amply.main.ui.dashboard.DashboardUiState
import eu.darken.amply.main.ui.settings.GeneralSettingsScreen
import eu.darken.amply.main.ui.settings.ReconnectGestureSettingsScreen

// Device spec shared by every Play Store screenshot. 1080x1920 (9:16) is Play's recommended phone
// size and stays within its "longest side may not exceed 2x the shorter side" rule — 1080x2400
// (2.22:1) would be rejected on upload.
internal const val DS = "spec:width=1080px,height=1920px,dpi=440"

private const val ADB_COMMAND =
    "adb shell pm grant eu.darken.amply android.permission.WRITE_SECURE_SETTINGS"

// -- Content composables (one per screenshot) --------------------------------------------------

@Composable
internal fun DashboardReadyContent() = DashboardShot(readyState())

@Composable
internal fun DashboardActiveContent() = DashboardShot(sessionState())

@Composable
internal fun SamsungMultiModeContent() = DashboardShot(samsungState())

@Composable
internal fun SetupGuideContent() = DashboardShot(setupNeededState())

@Composable
internal fun SettingsContent() = PreviewWrapper {
    GeneralSettingsScreen(
        state = ThemeState(),
        onBack = {},
        onModeChange = {},
        onStyleChange = {},
        onColorChange = {},
    )
}

@Composable
internal fun ReconnectGestureContent() = PreviewWrapper {
    ReconnectGestureSettingsScreen(
        gestureEnabled = true,
        anyLevelEnabled = false,
        onBack = {},
        onAnyLevelChange = {},
    )
}

// -- Shared dashboard renderer + state fixtures ------------------------------------------------

// The dashboard takes ~20 callbacks; funnel every fixture through one renderer so each state builder
// only has to describe the state it cares about.
@Composable
private fun DashboardShot(state: DashboardUiState) = PreviewWrapper {
    DashboardScreen(
        state = state,
        adbCommand = ADB_COMMAND,
        onRefresh = {},
        onSettings = {},
        onStartFull = {},
        onRestore = {},
        onApply = {},
        onQuickFullChargeChange = {},
        onOpenReconnectSettings = {},
        onAlarmEnabledChange = {},
        onAlarmTargetChange = {},
        onFixNotifications = {},
        onOpenBatteryDetail = {},
        onOpenStats = {},
        onPinWidget = {},
        onAddTile = {},
        onDismissQuickAccess = {},
        onNativeSettings = {},
        onOpenShizuku = {},
        onAllowShizuku = {},
        onGrantWss = {},
        onCopyAdb = {},
        onCopyWebUsbLink = {},
        onPrepareSupportReport = {},
        onCopySupportReport = {},
        onOpenContribution = {},
        onOpenSupportIssue = {},
        onEmailSupport = {},
        onHelp = {},
    )
}

private fun pixelDevice() = DeviceInfo("Google", "Pixel 8", 36, "preview")

private fun pixelPolicies() = listOf(
    ChargePolicy.FixedLimit(80),
    ChargePolicy.Adaptive,
    ChargePolicy.Unrestricted,
)

private fun grantedAccess() = AccessSnapshot(
    direct = BackendStatus(
        available = true,
        granted = true,
        detail = "Charge-control access granted".toCaString(),
    ),
    shizuku = BackendStatus(
        available = true,
        granted = true,
        detail = "Shizuku connected".toCaString(),
    ),
)

// Pixel, set up and verified at 80%: the headline showcase.
private fun readyState() = DashboardUiState(
    onboardingComplete = true,
    quickFullChargeEnabled = true,
    quickAccessChecked = true,
    charging = ChargingState(
        device = pixelDevice(),
        adapterName = "Pixel Charge Control".toCaString(),
        adapterId = "pixel",
        supportedPolicies = pixelPolicies(),
        reconnectSupported = true,
        controlEnabled = true,
        access = grantedAccess(),
        observation = ChargeObservation.Verified(ChargePolicy.FixedLimit(80), BackendKind.SHIZUKU),
    ),
)

// A one-time full charge in progress: session-aware hero plus the restore card.
private fun sessionState() = DashboardUiState(
    onboardingComplete = true,
    session = ChargeSessionRecord(
        restorePolicy = ChargePolicy.FixedLimit(80),
        startedAtMillis = 0L,
        connectedSeen = true,
    ),
    charging = ChargingState(
        device = pixelDevice(),
        adapterName = "Pixel Charge Control".toCaString(),
        adapterId = "pixel",
        supportedPolicies = pixelPolicies(),
        reconnectSupported = true,
        controlEnabled = true,
        access = grantedAccess(),
        observation = ChargeObservation.Verified(ChargePolicy.Unrestricted, BackendKind.BATTERY_HARDWARE),
    ),
)

// Samsung One UI 8 multi-mode: four fixed limits plus pause-at-full, shown as chips.
private fun samsungState() = DashboardUiState(
    onboardingComplete = true,
    charging = ChargingState(
        device = DeviceInfo("samsung", "SM-X210", 36, "preview", oneUiVersion = 80000, hasProtectBattery = true),
        adapterName = "Samsung battery protection".toCaString(),
        adapterId = "samsung-oneui8-v1",
        supportedPolicies = listOf(
            ChargePolicy.FixedLimit(80),
            ChargePolicy.FixedLimit(85),
            ChargePolicy.FixedLimit(90),
            ChargePolicy.FixedLimit(95),
            ChargePolicy.PauseAtFull,
            ChargePolicy.Unrestricted,
        ),
        reconnectSupported = false,
        controlEnabled = true,
        access = AccessSnapshot(
            direct = BackendStatus(
                available = true,
                granted = true,
                detail = "Charge-control access granted".toCaString(),
            ),
            shizuku = BackendStatus(
                available = false,
                granted = false,
                detail = "Shizuku not installed".toCaString(),
            ),
        ),
        observation = ChargeObservation.Verified(ChargePolicy.FixedLimit(80), BackendKind.DIRECT_WSS),
    ),
)

// Pixel before access is granted: the dashboard leads with the setup guide.
private fun setupNeededState() = DashboardUiState(
    onboardingComplete = true,
    charging = ChargingState(
        device = pixelDevice(),
        adapterName = "Pixel Charge Control".toCaString(),
        adapterId = "pixel",
        supportedPolicies = pixelPolicies(),
        reconnectSupported = true,
        controlEnabled = true,
        access = AccessSnapshot(
            direct = BackendStatus(
                available = false,
                granted = false,
                detail = "Charge-control access not granted".toCaString(),
            ),
            shizuku = BackendStatus(
                available = false,
                granted = false,
                detail = "Shizuku not running".toCaString(),
            ),
        ),
        observation = ChargeObservation.NeedsSetup("Grant access to control charging".toCaString()),
    ),
)

// -- IDE previews (design-time only; the screenshotTest wrappers drive the actual capture) ------

@Preview(name = "1 - Dashboard ready", device = DS, showSystemUi = true)
@Composable
private fun PreviewDashboardReady() = DashboardReadyContent()

@Preview(name = "2 - Full charge active", device = DS, uiMode = Configuration.UI_MODE_NIGHT_YES, showSystemUi = true)
@Composable
private fun PreviewDashboardActive() = DashboardActiveContent()

@Preview(name = "3 - Samsung multi-mode", device = DS, showSystemUi = true)
@Composable
private fun PreviewSamsungMultiMode() = SamsungMultiModeContent()

@Preview(name = "4 - Setup guide", device = DS, showSystemUi = true)
@Composable
private fun PreviewSetupGuide() = SetupGuideContent()

@Preview(name = "5 - Settings", device = DS, showSystemUi = true)
@Composable
private fun PreviewSettings() = SettingsContent()

@Preview(name = "6 - Reconnect gesture", device = DS, showSystemUi = true)
@Composable
private fun PreviewReconnectGesture() = ReconnectGestureContent()
