package eu.darken.amply.main.ui.dashboard

import android.app.Application
import android.os.BatteryManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ApplicationProvider
import eu.darken.amply.R
import eu.darken.amply.battery.core.BatteryReadout
import eu.darken.amply.charging.core.BackendKind
import eu.darken.amply.charging.core.ChargeObservation
import eu.darken.amply.charging.core.ChargePolicy
import eu.darken.amply.charging.core.ChargingState
import eu.darken.amply.charging.core.access.AccessSnapshot
import eu.darken.amply.charging.core.access.BackendStatus
import eu.darken.amply.common.ca.toCaString
import eu.darken.amply.fullcharge.core.InterruptionEvent
import eu.darken.amply.fullcharge.core.InterruptionOutcome
import eu.darken.amply.fullcharge.core.InterruptionReason
import eu.darken.amply.stats.core.StatsLiveSession
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DashboardScreenGestureTest {
    @get:Rule
    val compose = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<Application>()

    private fun string(res: Int): String = context.getString(res)

    // The hero no longer carries a battery line or a click action to be identified by, so it anchors
    // on its test tag instead.
    private fun heroCard() = hasTestTag(HERO_CARD_TEST_TAG)

    private fun render(
        state: DashboardUiState,
            onOpenReconnectSettings: () -> Unit = {},
    ) {
        compose.setContent {
            DashboardScreenForTest(
                state = state,
                onOpenReconnectSettings = onOpenReconnectSettings,
            )
        }
    }

    @Test
    fun `gesture card gear opens the reconnect settings`() {
        var opened = false
        render(
            state = DashboardUiState(
                onboardingComplete = true,
                quickFullChargeEnabled = true,
            ),
            onOpenReconnectSettings = { opened = true },
        )

        val gear = hasContentDescription(string(R.string.dashboard_reconnect_settings_action))
        compose.onNode(hasScrollAction()).performScrollToNode(gear)
        compose.onNodeWithContentDescription(string(R.string.dashboard_reconnect_settings_action))
            .performClick()

        compose.runOnIdle { opened shouldBe true }
    }

    @Test
    fun `the hero states the policy and is not a navigation surface`() {
        render(
            state = DashboardUiState(
                onboardingComplete = true,
                batteryReadout = BatteryReadout(
                    levelPercent = 82,
                    status = BatteryManager.BATTERY_STATUS_CHARGING,
                    plugged = BatteryManager.BATTERY_PLUGGED_AC,
                    temperatureTenthsC = 314,
                ),
            ),
        )

        // It says what the battery is doing...
        compose.onNodeWithText(context.getString(R.string.dashboard_effect_charging, "82%")).assertExists()
        // ...but tapping "Limited to 80%" must not navigate anywhere.
        compose.onNode(heroCard()).assertHasNoClickAction()
    }

    @Test
    fun `an unreported plug state claims neither connected nor on battery`() {
        render(state = DashboardUiState(onboardingComplete = true))
        compose.onNodeWithText(string(R.string.dashboard_effect_unknown)).assertExists()
    }

    @Test
    fun `the reading is shown on a supported device`() {
        render(state = DashboardUiState(onboardingComplete = true))
        // Default state has no access granted, so the setup guide sits between the hero and the card.
        scrollToChargingCard()
        compose.onNodeWithTag(CHARGING_CARD_TEST_TAG).assertExists()
    }

    private fun liveSession() = StatsLiveSession(
        id = 1,
        startedAtWallMillis = 0L,
        startedElapsedRealtimeMillis = 0L,
        startPercent = 40,
        partial = false,
        curve = emptyList(),
    )

    private fun scrollToChargingCard() =
        compose.onNode(hasScrollAction()).performScrollToNode(hasTestTag(CHARGING_CARD_TEST_TAG))

    // Access already granted, so the (large) setup guide doesn't sit between the hero and the promoted
    // stats card — otherwise the promotion assertions would fail on layout, not on order.
    private fun readyAccess() = AccessSnapshot(
        direct = BackendStatus(available = true, granted = true, detail = "granted".toCaString()),
        shizuku = BackendStatus(available = true, granted = true, detail = "connected".toCaString()),
    )

    private fun supportedCharging() = ChargingState(
        controlEnabled = true,
        access = readyAccess(),
        observation = ChargeObservation.Verified(ChargePolicy.FixedLimit(80), BackendKind.SHIZUKU),
    )

    private fun pluggedReadout(status: Int? = BatteryManager.BATTERY_STATUS_CHARGING) = BatteryReadout(
        levelPercent = 78,
        status = status,
        plugged = BatteryManager.BATTERY_PLUGGED_AC,
    )

    private fun topOf(matcher: SemanticsMatcher) = compose.onNode(matcher).getUnclippedBoundsInRoot().top

    private fun chargingCardTop() = compose.onNodeWithTag(CHARGING_CARD_TEST_TAG).getUnclippedBoundsInRoot().top

    @Test
    fun `live charge shows in the stats card while plugged in`() {
        render(
            state = DashboardUiState(
                onboardingComplete = true,
                charging = supportedCharging(),
                stats = StatsDashboardState(enabled = true, live = liveSession()),
                batteryReadout = pluggedReadout(),
            ),
        )
        // Promoted to the second slot, so it is on screen without scrolling.
        compose.onNodeWithTag(CHARGING_CARD_TEST_TAG).assertIsDisplayed()
        compose.onNodeWithText(string(R.string.dashboard_charging_title_charging)).assertExists()
        // One surface: while live-titled there is no second "Battery statistics" card anywhere.
        compose.onAllNodesWithText(string(R.string.dashboard_charging_title_idle)).assertCountEquals(0)
        compose.onAllNodesWithText(string(R.string.dashboard_charging_title_charging)).assertCountEquals(1)
    }

    // Ordering assertions need every compared card composed at once, which a LazyColumn only guarantees
    // inside the viewport — on the default screen a failure would mean "scrolled out", not "wrong
    // order". The tall qualifier renders the whole list; "reachable without scrolling" is asserted
    // separately, at the real screen height, where the fold actually means something.
    @Test
    @Config(qualifiers = "+h2400dp")
    fun `the charging card sits second, above the charge controls and the alarm`() {
        render(
            state = DashboardUiState(
                onboardingComplete = true,
                charging = supportedCharging(),
                stats = StatsDashboardState(enabled = true, live = liveSession()),
                batteryReadout = pluggedReadout(),
            ),
        )
        val chargingTop = chargingCardTop()
        // Second, not first: the hero keeps the top slot.
        (topOf(heroCard()) < chargingTop) shouldBe true
        (chargingTop < topOf(hasText(string(R.string.dashboard_fullcharge_idle_title)))) shouldBe true
        // ...and it really left the tail.
        (chargingTop < topOf(hasText(string(R.string.dashboard_alarm_title)))) shouldBe true
    }

    @Test
    fun `holding at a limit still counts as on the charger`() {
        render(
            state = DashboardUiState(
                onboardingComplete = true,
                charging = supportedCharging(),
                stats = StatsDashboardState(enabled = true, live = liveSession()),
                // A device parked at its limit reports NOT_CHARGING while still plugged: promotion
                // follows the plug, never the charge status.
                batteryReadout = pluggedReadout(status = BatteryManager.BATTERY_STATUS_NOT_CHARGING),
            ),
        )
        compose.onNodeWithTag(CHARGING_CARD_TEST_TAG).assertIsDisplayed()
    }

    @Test
    fun `the reading shows even with capture switched off`() {
        render(
            state = DashboardUiState(
                onboardingComplete = true,
                charging = supportedCharging(),
                // Capture off: the card is still the telemetry surface, so it still occupies its slot
                // and still reports the charger — only the session body becomes a hint.
                stats = StatsDashboardState(enabled = false),
                batteryReadout = pluggedReadout(),
            ),
        )
        compose.onNodeWithText(string(R.string.dashboard_charging_history_hint)).assertIsDisplayed()
        compose.onNodeWithText(string(R.string.dashboard_charging_title_charging)).assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = "+h2400dp")
    fun `the setup guide and interruption warning stay above the charging card`() {
        render(
            state = DashboardUiState(
                onboardingComplete = true,
                // No access yet → the setup guide renders; plus a restore that is still owed.
                charging = ChargingState(
                    controlEnabled = true,
                    observation = ChargeObservation.Verified(ChargePolicy.FixedLimit(80), BackendKind.SHIZUKU),
                ),
                interruption = InterruptionEvent(
                    occurredAtMillis = 0L,
                    reason = InterruptionReason.USER_STOPPED,
                    outcome = InterruptionOutcome.RESTORED_LATE,
                    workId = "tok",
                ),
                stats = StatsDashboardState(enabled = true, live = liveSession()),
                batteryReadout = pluggedReadout(),
            ),
        )
        val interruptionTop = topOf(hasText(string(R.string.dashboard_interruption_title_restored)))
        val guideTop = topOf(hasText(string(R.string.setup_access_setup_title)))
        val chargingTop = chargingCardTop()
        (interruptionTop < guideTop) shouldBe true
        (guideTop < chargingTop) shouldBe true
    }

    @Test
    fun `stale open session does not claim charging while unplugged`() {
        render(
            state = DashboardUiState(
                onboardingComplete = true,
                stats = StatsDashboardState(enabled = true, live = liveSession()),
                // Not plugged: the raw readout wins over the stale open row.
                batteryReadout = BatteryReadout(levelPercent = 78, plugged = 0),
            ),
        )
        // Scroll to the (idle) stats card first so the negative assertion can't pass merely
        // because the slot is off-screen and uncomposed.
        scrollToChargingCard()
        compose.onNodeWithText(string(R.string.dashboard_charging_title_charging)).assertDoesNotExist()
        compose.onNodeWithText(string(R.string.dashboard_stats_recording_empty)).assertExists()
    }

    @Test
    fun `plugged in without a recorder row shows the starting state`() {
        render(
            state = DashboardUiState(
                onboardingComplete = true,
                charging = supportedCharging(),
                stats = StatsDashboardState(enabled = true),
                batteryReadout = pluggedReadout(),
            ),
        )
        compose.onNodeWithText(string(R.string.dashboard_charging_title_charging)).assertIsDisplayed()
        compose.onNodeWithText(string(R.string.dashboard_stats_live_starting)).assertExists()
    }

    @Test
    fun `a failed capture start offers its retry action`() {
        render(
            state = DashboardUiState(
                onboardingComplete = true,
                charging = supportedCharging(),
                stats = StatsDashboardState(enabled = true, startFailed = true),
                batteryReadout = pluggedReadout(),
            ),
        )
        compose.onNodeWithText(string(R.string.dashboard_stats_live_start_failed)).assertIsDisplayed()
        compose.onNodeWithText(string(R.string.dashboard_stats_retry_action)).assertIsDisplayed()
    }

    // The card used to jump between slots on plug/unplug, so the list visibly reshuffled under the
    // user. Asserted inside one live composition rather than across two renders, because that is where
    // a reintroduced slot swap would actually show up.
    @Test
    @Config(qualifiers = "+h2400dp")
    fun `plugging in and out never moves the charging card`() {
        val plugged = mutableStateOf(false)
        compose.setContent {
            DashboardScreenForTest(
                state = DashboardUiState(
                    onboardingComplete = true,
                    charging = supportedCharging(),
                    stats = StatsDashboardState(enabled = true, live = liveSession()),
                    batteryReadout = BatteryReadout(
                        levelPercent = 78,
                        plugged = if (plugged.value) BatteryManager.BATTERY_PLUGGED_AC else 0,
                    ),
                ),
            )
        }
        val alarm = hasText(string(R.string.dashboard_alarm_title))

        compose.onAllNodesWithTag(CHARGING_CARD_TEST_TAG).assertCountEquals(1)
        (chargingCardTop() < topOf(alarm)) shouldBe true

        compose.runOnIdle { plugged.value = true }
        compose.onAllNodesWithTag(CHARGING_CARD_TEST_TAG).assertCountEquals(1)
        (chargingCardTop() < topOf(alarm)) shouldBe true

        compose.runOnIdle { plugged.value = false }
        compose.onAllNodesWithTag(CHARGING_CARD_TEST_TAG).assertCountEquals(1)
        (chargingCardTop() < topOf(alarm)) shouldBe true
    }

    @Test
    @Config(qualifiers = "+h2400dp")
    fun `unsupported devices place the charging card right under the hero`() {
        render(
            state = DashboardUiState(
                onboardingComplete = true,
                charging = ChargingState(
                    observation = ChargeObservation.Unsupported("Not a supported device".toCaString()),
                ),
                stats = StatsDashboardState(enabled = true, live = liveSession()),
                batteryReadout = pluggedReadout(),
            ),
        )
        // The unsupported branch has no interruption card or setup guide, so the card follows the hero
        // directly — ahead of the OEM guide and the alarm card that end this branch.
        compose.onNodeWithText(string(R.string.dashboard_charging_title_charging)).assertExists()
        val chargingTop = chargingCardTop()
        (topOf(heroCard()) < chargingTop) shouldBe true
        (chargingTop < topOf(hasText(string(R.string.setup_oem_guide_title)))) shouldBe true
        (chargingTop < topOf(hasText(string(R.string.dashboard_alarm_title)))) shouldBe true
    }

    @Test
    fun `the reading is shown on an unsupported device`() {
        render(
            state = DashboardUiState(
                onboardingComplete = true,
                charging = ChargingState(
                    observation = ChargeObservation.Unsupported("Not a supported device".toCaString()),
                ),
            ),
        )
        compose.onNodeWithTag(CHARGING_CARD_TEST_TAG).assertExists()
    }
}

// The screen takes ~30 callbacks; funnel every fixture through one renderer so a test only describes the
// state it cares about. Kept composable (rather than inlined into setContent) so a test can drive state
// changes through a live composition.
@Composable
private fun DashboardScreenForTest(
    state: DashboardUiState,
    onOpenReconnectSettings: () -> Unit = {},
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
        onOpenReconnectSettings = onOpenReconnectSettings,
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
        onOpenShizuku = {},
        onAllowShizuku = {},
        onGrantWss = {},
        onCopyAdb = {},
        onCopyWebUsbLink = {},
        onOpenContribution = {},
        onPrepareSupportReport = {},
        onCopySupportReport = {},
        onOpenSupportIssue = {},
        onEmailSupport = {},
        onHelp = {},
    )
}
