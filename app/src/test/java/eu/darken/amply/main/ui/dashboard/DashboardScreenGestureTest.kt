package eu.darken.amply.main.ui.dashboard

import android.app.Application
import android.os.BatteryManager
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ApplicationProvider
import eu.darken.amply.R
import eu.darken.amply.battery.core.BatteryReadout
import eu.darken.amply.charging.core.ChargeObservation
import eu.darken.amply.charging.core.ChargingState
import eu.darken.amply.common.ca.toCaString
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
                onPinWidget = {},
                onAddTile = {},
                onDismissQuickAccess = {},
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
