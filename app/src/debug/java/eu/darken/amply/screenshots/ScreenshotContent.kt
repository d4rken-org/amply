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
import eu.darken.amply.charging.core.SettingProbe
import eu.darken.amply.charging.core.access.AccessSnapshot
import eu.darken.amply.charging.core.access.BackendStatus
import eu.darken.amply.common.ca.toCaString
import eu.darken.amply.common.compose.PreviewWrapper
import eu.darken.amply.common.theming.ThemeState
import eu.darken.amply.battery.core.BatteryReadout
import eu.darken.amply.fullcharge.core.ChargeSessionRecord
import eu.darken.amply.main.ui.dashboard.DashboardScreen
import eu.darken.amply.main.ui.dashboard.DashboardUiState
import eu.darken.amply.main.ui.dashboard.StatsDashboardState
import eu.darken.amply.stats.core.ChargeCurvePoint
import eu.darken.amply.stats.core.StatsLiveSession
import eu.darken.amply.main.ui.settings.ChargingSettingsScreen
import eu.darken.amply.main.ui.settings.GeneralSettingsScreen

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
    ChargingSettingsScreen(
        gestureEnabled = true,
        anyLevelEnabled = false,
        canEnableGesture = true,
        onBack = {},
        onGestureEnabledChange = {},
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
        onAlarmEnabledChange = {},
        onAlarmTargetChange = {},
        onFixNotifications = {},
        onOpenBatteryHub = {},
        onRetryCapture = {},
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
        onDismissInterruption = {},
        // Fixture sessions start at elapsed-realtime 0, so pin "now" to give them a believable age:
        // otherwise every shot claims a charge that started this instant, and the charge curve — which
        // is withheld until a session has a few minutes of shape — never appears at all.
        nowElapsedRealtimeMillis = LIVE_SESSION_AGE_MILLIS,
    )
}

/** 1h 15m into a charge: long enough for the curve to have earned its place on the card. */
private const val LIVE_SESSION_AGE_MILLIS = 4_500_000L

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

// The charging card renders a live reading in every state, so a state without a batteryReadout shows
// "Not reported" three times over, and one without stats shows the capture promo. Both would be a
// misleading thing to ship as a store screenshot — these fixtures give the showcase states real data.
private fun holdingAtLimit() = BatteryReadout(
    levelPercent = 80,
    // Held at the limit: connected but not taking charge, which is the whole point of the app.
    status = android.os.BatteryManager.BATTERY_STATUS_NOT_CHARGING,
    plugged = android.os.BatteryManager.BATTERY_PLUGGED_AC,
    health = android.os.BatteryManager.BATTERY_HEALTH_GOOD,
    technology = "Li-ion",
    temperatureTenthsC = 298,
    voltageMillivolts = 4_120,
    currentNowMicroamps = 0,
    chargeCounterMicroampHours = 3_800_000,
    cycleCount = 142,
)

private fun charging() = holdingAtLimit().copy(
    levelPercent = 72,
    status = android.os.BatteryManager.BATTERY_STATUS_CHARGING,
    temperatureTenthsC = 312,
    currentNowMicroamps = 2_050_000,
)

// A real charge, not three straight lines: the compact card self-normalizes every series, so linear
// fixture data makes level and temperature resolve to the identical shape and draw on top of each
// other — three legend entries, two visible curves. These follow an actual CC/CV charge instead:
// level tapers as it approaches the limit, power falls away with it, temperature peaks mid-charge.
private val LEVEL_CURVE = listOf(41, 45, 49, 53, 57, 61, 64, 67, 70, 73, 75, 77, 78, 79, 80)
private val POWER_CURVE = listOf(
    19_000, 18_800, 18_400, 17_900, 17_100, 16_000, 14_500, 12_500,
    10_500, 8_500, 6_800, 5_200, 4_000, 3_200, 2_500,
)
private val TEMPERATURE_CURVE = listOf(
    300, 304, 308, 311, 313, 314, 315, 315, 314, 314, 313, 313, 312, 312, 311,
)

private fun liveStats() = StatsDashboardState(
    enabled = true,
    live = StatsLiveSession(
        id = 1,
        startedAtWallMillis = 0L,
        startedElapsedRealtimeMillis = 0L,
        startPercent = LEVEL_CURVE.first(),
        partial = false,
        curve = LEVEL_CURVE.indices.map { i ->
            ChargeCurvePoint(
                elapsedFromStartMillis = i * 300_000L,
                percent = LEVEL_CURVE[i],
                powerMilliwatts = POWER_CURVE[i],
                temperatureTenthsC = TEMPERATURE_CURVE[i],
            )
        },
    ),
)

// Pixel, set up and verified at 80%: the headline showcase.
private fun readyState() = DashboardUiState(
    onboardingComplete = true,
    quickFullChargeEnabled = true,
    quickAccessChecked = true,
    batteryReadout = holdingAtLimit(),
    stats = liveStats(),
    charging = ChargingState(
        device = pixelDevice(),
        adapterName = "Pixel Charge Control".toCaString(),
        adapterId = "pixel",
        adapterDetail = "Charging changes take about 15 seconds to reach the hardware".toCaString(),
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
    // Charging past the limit for once — this shot is about the one-time full charge.
    batteryReadout = charging(),
    stats = liveStats(),
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
    batteryReadout = holdingAtLimit(),
    stats = liveStats(),
    charging = ChargingState(
        device = DeviceInfo(
            "samsung",
            "SM-X210",
            36,
            "preview",
            oneUiVersion = 80000,
            protectBatteryProbe = SettingProbe.PRESENT,
        ),
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
    // Capture is off here: this shot is about the setup guide, and the charging card showing its
    // "turn on charge recording" hint is the honest pre-setup state.
    onboardingComplete = true,
    batteryReadout = charging(),
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
