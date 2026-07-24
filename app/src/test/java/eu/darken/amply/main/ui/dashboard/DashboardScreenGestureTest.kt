package eu.darken.amply.main.ui.dashboard

import android.app.Application
import android.os.BatteryManager
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ApplicationProvider
import eu.darken.amply.R
import eu.darken.amply.battery.core.BatteryReadout
import eu.darken.amply.charging.core.ChargeObservation
import eu.darken.amply.charging.core.ChargingState
import eu.darken.amply.common.ca.toCaString
import eu.darken.amply.stats.core.StatsLiveSession
import eu.darken.amply.stats.ui.STATS_CARD_TEST_TAG
import eu.darken.amply.stats.ui.StatsDashboardState
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
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

    @Test
    fun `live charge shows in the stats card while plugged in`() {
        render(
            state = DashboardUiState(
                onboardingComplete = true,
                stats = StatsDashboardState(enabled = true, live = liveSession()),
                batteryReadout = BatteryReadout(
                    levelPercent = 78,
                    plugged = BatteryManager.BATTERY_PLUGGED_AC,
                ),
            ),
        )
        // Before scrolling the initial viewport shows the hero region: a regression re-inserting
        // the old under-hero live card would surface here (post-scroll it would be disposed by the
        // LazyColumn and invisible to the count below).
        compose.onNodeWithText(string(R.string.dashboard_stats_live_title)).assertDoesNotExist()
        scrollToStatsCard()
        compose.onNodeWithText(string(R.string.dashboard_stats_live_title)).assertExists()
        // One surface: while live-titled there is no second "Battery statistics" card anywhere.
        compose.onAllNodesWithText(string(R.string.dashboard_stats_title)).assertCountEquals(0)
        compose.onAllNodesWithText(string(R.string.dashboard_stats_live_title)).assertCountEquals(1)
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
    fun `plugged in without a recorder row shows the starting state`() {
        render(
            state = DashboardUiState(
                onboardingComplete = true,
                stats = StatsDashboardState(enabled = true),
                batteryReadout = BatteryReadout(
                    levelPercent = 78,
                    plugged = BatteryManager.BATTERY_PLUGGED_AC,
                ),
            ),
        )
        scrollToStatsCard()
        compose.onNodeWithText(string(R.string.dashboard_stats_live_title)).assertExists()
        compose.onNodeWithText(string(R.string.dashboard_stats_live_starting)).assertExists()
    }

    @Test
    fun `stats card sits in its fixed slot below the alarm card`() {
        render(
            state = DashboardUiState(
                onboardingComplete = true,
                stats = StatsDashboardState(enabled = true, live = liveSession()),
                batteryReadout = BatteryReadout(
                    levelPercent = 78,
                    plugged = BatteryManager.BATTERY_PLUGGED_AC,
                ),
            ),
        )
        // Going live must not teleport the card: it stays in the shared tail, directly after the
        // alarm card, instead of jumping under the hero.
        scrollToStatsCard()
        val alarmTop = compose.onNodeWithText(string(R.string.dashboard_alarm_title))
            .getUnclippedBoundsInRoot().top
        val statsTop = compose.onNodeWithTag(STATS_CARD_TEST_TAG)
            .getUnclippedBoundsInRoot().top
        (alarmTop < statsTop) shouldBe true
    }

    @Test
    fun `unsupported devices show the live charge in the same slot`() {
        render(
            state = DashboardUiState(
                onboardingComplete = true,
                charging = ChargingState(
                    observation = ChargeObservation.Unsupported("Not a supported device".toCaString()),
                ),
                stats = StatsDashboardState(enabled = true, live = liveSession()),
                batteryReadout = BatteryReadout(
                    levelPercent = 78,
                    plugged = BatteryManager.BATTERY_PLUGGED_AC,
                ),
            ),
        )
        scrollToStatsCard()
        compose.onNodeWithText(string(R.string.dashboard_stats_live_title)).assertExists()
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
