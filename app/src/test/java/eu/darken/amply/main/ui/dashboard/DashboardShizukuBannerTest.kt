package eu.darken.amply.main.ui.dashboard

import android.app.Application
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import eu.darken.amply.R
import eu.darken.amply.charging.core.BackendKind
import eu.darken.amply.charging.core.ChargeObservation
import eu.darken.amply.charging.core.ChargePolicy
import eu.darken.amply.charging.core.ChargingState
import eu.darken.amply.charging.core.access.AccessSnapshot
import eu.darken.amply.charging.core.access.BackendStatus
import eu.darken.amply.common.ca.toCaString
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The banner is one tap target for one action, and which action that is depends on whether Shizuku is
 * already running. The tall qualifier renders the whole list so the banner — which ends it — is
 * composed.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "+h2400dp")
class DashboardShizukuBannerTest {
    @get:Rule
    val compose = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<Application>()

    private fun string(res: Int): String = context.getString(res)

    private var opened = 0
    private var allowed = 0

    // A Shizuku-only adapter (OnePlus/ColorOS): control is enabled but writes need Shizuku, which is
    // not connected — the branch that renders the "Shizuku required" banner.
    private fun render(shizukuRunning: Boolean) {
        compose.setContent {
            DashboardScreen(
                state = DashboardUiState(
                    onboardingComplete = true,
                    charging = ChargingState(
                        controlEnabled = true,
                        writeRequiresShizuku = true,
                        syncVerification = true,
                        access = AccessSnapshot(
                            direct = BackendStatus(
                                available = true,
                                granted = true,
                                detail = "granted".toCaString(),
                            ),
                            shizuku = BackendStatus(
                                available = shizukuRunning,
                                granted = false,
                                detail = "not connected".toCaString(),
                            ),
                        ),
                        observation = ChargeObservation.Verified(
                            ChargePolicy.FixedLimit(80),
                            BackendKind.DIRECT_WSS,
                        ),
                    ),
                ),
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
                onOpenShizuku = { opened++ },
                onAllowShizuku = { allowed++ },
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

    private fun tapBanner() =
        compose.onNodeWithText(string(R.string.dashboard_shizuku_required_body)).performClick()

    @Test
    fun `with Shizuku not running the card opens Shizuku`() {
        render(shizukuRunning = false)
        compose.onNodeWithText(string(R.string.dashboard_shizuku_open)).assertExists()
        tapBanner()
        compose.runOnIdle {
            opened shouldBe 1
            allowed shouldBe 0
        }
    }

    @Test
    fun `with Shizuku running the card asks for access`() {
        render(shizukuRunning = true)
        compose.onNodeWithText(string(R.string.dashboard_shizuku_allow)).assertExists()
        tapBanner()
        compose.runOnIdle {
            allowed shouldBe 1
            opened shouldBe 0
        }
    }
}
