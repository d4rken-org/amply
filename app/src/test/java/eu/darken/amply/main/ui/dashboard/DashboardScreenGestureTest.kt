package eu.darken.amply.main.ui.dashboard

import android.app.Application
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ApplicationProvider
import eu.darken.amply.R
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

    private fun string(res: Int): String =
        ApplicationProvider.getApplicationContext<Application>().getString(res)

    @Test
    fun `gesture card gear opens the reconnect settings`() {
        var opened = false
        compose.setContent {
            DashboardScreen(
                state = DashboardUiState(
                    onboardingComplete = true,
                    quickFullChargeEnabled = true,
                ),
                adbCommand = "",
                onRefresh = {},
                onSettings = {},
                onStartFull = {},
                onRestore = {},
                onApply = {},
                onQuickFullChargeChange = {},
                onOpenReconnectSettings = { opened = true },
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

        val gear = hasContentDescription(string(R.string.dashboard_reconnect_settings_action))
        compose.onNode(hasScrollAction()).performScrollToNode(gear)
        compose.onNodeWithContentDescription(string(R.string.dashboard_reconnect_settings_action))
            .performClick()

        compose.runOnIdle { opened shouldBe true }
    }
}
