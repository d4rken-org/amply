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
import eu.darken.amply.stats.ui.STATS_CARD_TEST_TAG
import eu.darken.amply.stats.ui.StatsDashboardState
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

    // The hero is a single merged clickable node whose concatenated text always contains the battery
    // line's literal prefix. Derived from the resource with an empty arg ("Battery: ") so it tracks
    // the string and is independent of the (locale-dependent) reading. `onNode` with this matcher
    // fails on ambiguity, so an accidental second battery surface would surface as a test failure.
    private fun heroCard() =
        hasText(context.getString(R.string.dashboard_battery_line, ""), substring = true) and hasClickAction()

    private fun render(
        state: DashboardUiState,
        onOpenBatteryDetail: () -> Unit = {},
        onOpenReconnectSettings: () -> Unit = {},
    ) {
        compose.setContent {
            DashboardScreenForTest(
                state = state,
                onOpenBatteryDetail = onOpenBatteryDetail,
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
    fun `tapping the status card opens battery details`() {
        var opened = false
        render(
            state = DashboardUiState(
                onboardingComplete = true,
                batteryReadout = BatteryReadout(
                    levelPercent = 82,
                    status = BatteryManager.BATTERY_STATUS_CHARGING,
                    temperatureTenthsC = 314,
                ),
            ),
            onOpenBatteryDetail = { opened = true },
        )

        compose.onNode(heroCard()).performClick()

        compose.runOnIdle { opened shouldBe true }
    }

    @Test
    fun `battery reading is shown on a supported device`() {
        render(state = DashboardUiState(onboardingComplete = true))
        compose.onNode(heroCard()).assertExists()
    }

    private fun liveSession() = StatsLiveSession(
        id = 1,
        startedAtWallMillis = 0L,
        startedElapsedRealtimeMillis = 0L,
        startPercent = 40,
        partial = false,
        curve = emptyList(),
    )

    private fun scrollToStatsCard() =
        compose.onNode(hasScrollAction()).performScrollToNode(hasTestTag(STATS_CARD_TEST_TAG))

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

    private fun pluggedReadout(status: Int? = null) = BatteryReadout(
        levelPercent = 78,
        status = status,
        plugged = BatteryManager.BATTERY_PLUGGED_AC,
    )

    private fun topOf(matcher: SemanticsMatcher) = compose.onNode(matcher).getUnclippedBoundsInRoot().top

    private fun statsCardTop() = compose.onNodeWithTag(STATS_CARD_TEST_TAG).getUnclippedBoundsInRoot().top

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
        compose.onNodeWithTag(STATS_CARD_TEST_TAG).assertIsDisplayed()
        compose.onNodeWithText(string(R.string.dashboard_stats_live_title)).assertExists()
        // One surface: while live-titled there is no second "Battery statistics" card anywhere.
        compose.onAllNodesWithText(string(R.string.dashboard_stats_title)).assertCountEquals(0)
        compose.onAllNodesWithText(string(R.string.dashboard_stats_live_title)).assertCountEquals(1)
    }

    // Ordering assertions need every compared card composed at once, which a LazyColumn only guarantees
    // inside the viewport — on the default screen a failure would mean "scrolled out", not "wrong
    // order". The tall qualifier renders the whole list; "reachable without scrolling" is asserted
    // separately, at the real screen height, where the fold actually means something.
    @Test
    @Config(qualifiers = "+h2400dp")
    fun `plugged in promotes the stats card out of the tail and above the charge controls`() {
        render(
            state = DashboardUiState(
                onboardingComplete = true,
                charging = supportedCharging(),
                stats = StatsDashboardState(enabled = true, live = liveSession()),
                batteryReadout = pluggedReadout(),
            ),
        )
        val statsTop = statsCardTop()
        // Second, not first: the hero keeps the top slot.
        (topOf(heroCard()) < statsTop) shouldBe true
        (statsTop < topOf(hasText(string(R.string.dashboard_fullcharge_idle_title)))) shouldBe true
        // ...and it really left the tail.
        (statsTop < topOf(hasText(string(R.string.dashboard_alarm_title)))) shouldBe true
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
        compose.onNodeWithTag(STATS_CARD_TEST_TAG).assertIsDisplayed()
    }

    @Test
    fun `capture off is promoted too while plugged in`() {
        render(
            state = DashboardUiState(
                onboardingComplete = true,
                charging = supportedCharging(),
                // Promo (capture disabled) and the loading/unavailable states share the slot with the
                // live card, so it never moves under the user as the stats DB answers.
                stats = StatsDashboardState(enabled = false),
                batteryReadout = pluggedReadout(),
            ),
        )
        compose.onNodeWithText(string(R.string.dashboard_stats_promo)).assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = "+h2400dp")
    fun `the setup guide and interruption warning stay above the promoted card`() {
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
        val statsTop = statsCardTop()
        (interruptionTop < guideTop) shouldBe true
        (guideTop < statsTop) shouldBe true
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
        scrollToStatsCard()
        compose.onNodeWithText(string(R.string.dashboard_stats_live_title)).assertDoesNotExist()
        compose.onNodeWithText(string(R.string.dashboard_stats_recording_empty)).assertExists()
    }

    @Test
    fun `plugged in without a recorder row shows the starting state and is promoted`() {
        render(
            state = DashboardUiState(
                onboardingComplete = true,
                charging = supportedCharging(),
                stats = StatsDashboardState(enabled = true),
                batteryReadout = pluggedReadout(),
            ),
        )
        compose.onNodeWithText(string(R.string.dashboard_stats_live_title)).assertIsDisplayed()
        compose.onNodeWithText(string(R.string.dashboard_stats_live_starting)).assertExists()
    }

    @Test
    fun `a failed capture start is promoted with its retry action`() {
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

    // The interesting case is the card actually moving inside a live composition (plugging in), not two
    // separate renders: that is what the LazyColumn keys have to survive, and it must never leave two
    // stats cards behind.
    @Test
    @Config(qualifiers = "+h2400dp")
    fun `plugging in and out moves the single stats card between its slots`() {
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

        compose.onAllNodesWithTag(STATS_CARD_TEST_TAG).assertCountEquals(1)
        (topOf(alarm) < statsCardTop()) shouldBe true

        compose.runOnIdle { plugged.value = true }
        compose.onAllNodesWithTag(STATS_CARD_TEST_TAG).assertCountEquals(1)
        (statsCardTop() < topOf(alarm)) shouldBe true

        compose.runOnIdle { plugged.value = false }
        compose.onAllNodesWithTag(STATS_CARD_TEST_TAG).assertCountEquals(1)
        (topOf(alarm) < statsCardTop()) shouldBe true
    }

    @Test
    fun `unplugged keeps the stats card in its tail slot below the alarm card`() {
        render(
            state = DashboardUiState(
                onboardingComplete = true,
                charging = supportedCharging(),
                stats = StatsDashboardState(enabled = true, live = liveSession()),
                batteryReadout = BatteryReadout(levelPercent = 78, plugged = 0),
            ),
        )
        // Off the charger the card is a teaser, not a live readout: it stays in the shared tail,
        // directly after the (adjacent, so reliably co-composed) alarm card.
        scrollToStatsCard()
        val alarmTop = topOf(hasText(string(R.string.dashboard_alarm_title)))
        (alarmTop < statsCardTop()) shouldBe true
    }

    @Test
    @Config(qualifiers = "+h2400dp")
    fun `unsupported devices promote the live charge too`() {
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
        compose.onNodeWithText(string(R.string.dashboard_stats_live_title)).assertExists()
        val statsTop = statsCardTop()
        (topOf(heroCard()) < statsTop) shouldBe true
        (statsTop < topOf(hasText(string(R.string.setup_oem_guide_title)))) shouldBe true
        (statsTop < topOf(hasText(string(R.string.dashboard_alarm_title)))) shouldBe true
    }

    @Test
    fun `battery reading is shown on an unsupported device`() {
        render(
            state = DashboardUiState(
                onboardingComplete = true,
                charging = ChargingState(
                    observation = ChargeObservation.Unsupported("Not a supported device".toCaString()),
                ),
            ),
        )
        compose.onNode(heroCard()).assertExists()
    }
}

// The screen takes ~30 callbacks; funnel every fixture through one renderer so a test only describes the
// state it cares about. Kept composable (rather than inlined into setContent) so a test can drive state
// changes through a live composition.
@Composable
private fun DashboardScreenForTest(
    state: DashboardUiState,
    onOpenBatteryDetail: () -> Unit = {},
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
        onOpenBatteryDetail = onOpenBatteryDetail,
        onOpenStats = {},
        onOpenLiveSession = {},
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
