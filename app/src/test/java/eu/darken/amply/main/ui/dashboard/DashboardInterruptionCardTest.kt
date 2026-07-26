package eu.darken.amply.main.ui.dashboard

import android.app.Application
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ApplicationProvider
import eu.darken.amply.R
import eu.darken.amply.charging.core.BackendKind
import eu.darken.amply.charging.core.ChargeObservation
import eu.darken.amply.charging.core.ChargePolicy
import eu.darken.amply.charging.core.ChargingState
import eu.darken.amply.common.ca.toCaString
import eu.darken.amply.fullcharge.core.InterruptionEvent
import eu.darken.amply.fullcharge.core.InterruptionOutcome
import eu.darken.amply.fullcharge.core.InterruptionReason
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DashboardInterruptionCardTest {
    @get:Rule
    val compose = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<Application>()

    private fun string(res: Int): String = context.getString(res)

    private val event = InterruptionEvent(
        occurredAtMillis = 0L,
        reason = InterruptionReason.USER_STOPPED,
        outcome = InterruptionOutcome.RESTORED_LATE,
        workId = "tok",
    )

    private fun supportedState(interruption: InterruptionEvent?) = DashboardUiState(
        onboardingComplete = true,
        interruption = interruption,
        charging = ChargingState(
            controlEnabled = true,
            observation = ChargeObservation.Verified(ChargePolicy.FixedLimit(80), BackendKind.SHIZUKU),
        ),
    )

    private fun render(state: DashboardUiState, onDismissInterruption: () -> Unit = {}) {
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
                onAlarmEnabledChange = {},
                onAlarmTargetChange = {},
                onFixNotifications = {},
                onOpenBatteryHub = {},
                onRetryCapture = {},
                onPinWidget = {},
                onAddTile = {},
                onDismissQuickAccess = {},
                onDismissInterruption = onDismissInterruption,
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
    fun `card shows on a supported device with an event`() {
        render(supportedState(event))
        compose.onNodeWithText(string(R.string.dashboard_interruption_title_restored)).assertExists()
    }

    @Test
    fun `card is absent without an event`() {
        render(supportedState(interruption = null))
        compose.onNodeWithText(string(R.string.dashboard_interruption_title_restored)).assertDoesNotExist()
    }

    @Test
    fun `card is absent on an unsupported device`() {
        render(
            DashboardUiState(
                onboardingComplete = true,
                interruption = event,
                charging = ChargingState(
                    observation = ChargeObservation.Unsupported("Not supported".toCaString()),
                ),
            ),
        )
        compose.onNodeWithText(string(R.string.dashboard_interruption_title_restored)).assertDoesNotExist()
    }

    @Test
    fun `card is absent while support is unresolved`() {
        // Default ChargingState: control not enabled yet (still detecting) — the card must stay hidden.
        render(DashboardUiState(onboardingComplete = true, interruption = event))
        compose.onNodeWithText(string(R.string.dashboard_interruption_title_restored)).assertDoesNotExist()
    }

    @Test
    fun `dismiss invokes the callback`() {
        var dismissed = false
        render(supportedState(event), onDismissInterruption = { dismissed = true })

        compose.onNode(hasScrollAction())
            .performScrollToNode(hasText(string(R.string.dashboard_interruption_dismiss_action)))
        compose.onNodeWithText(string(R.string.dashboard_interruption_dismiss_action)).performClick()

        compose.runOnIdle { dismissed shouldBe true }
    }
}
