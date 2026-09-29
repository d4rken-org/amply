package eu.darken.amply.main.ui.setup

import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import eu.darken.amply.R
import eu.darken.amply.charging.core.ChargeObservation
import eu.darken.amply.charging.core.ChargingState
import eu.darken.amply.charging.core.access.AccessSnapshot
import eu.darken.amply.charging.core.access.BackendStatus
import eu.darken.amply.charging.core.access.shizuku.ManagerBackend
import eu.darken.amply.common.ca.toCaString
import eu.darken.amply.main.ui.dashboard.DashboardUiState
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AccessSetupGuideTest {
    @get:Rule
    val compose = createComposeRule()

    private fun string(res: Int): String =
        ApplicationProvider.getApplicationContext<Application>().getString(res)

    private fun setContent(state: DashboardUiState, onCopyWebUsbLink: () -> Unit = {}) {
        compose.setContent {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                AccessSetupGuide(
                    state = state,
                    adbCommand = "adb shell pm grant eu.darken.amply android.permission.WRITE_SECURE_SETTINGS",
                    onOpenShizuku = {},
                    onAllowShizuku = {},
                    onGrantWss = {},
                    onCopyAdb = {},
                    onCopyWebUsbLink = onCopyWebUsbLink,
                )
            }
        }
    }

    @Test
    fun `web helper copy invokes its callback when setup is needed`() {
        var copied = false
        setContent(DashboardUiState(onboardingComplete = false)) { copied = true }

        compose.onNodeWithText(string(R.string.setup_access_copy_webusb))
            .performScrollTo()
            .assertIsDisplayed()
            .performClick()

        compose.runOnIdle { copied shouldBe true }
    }

    @Test
    fun `web helper option is hidden once access is ready`() {
        val granted = DashboardUiState(
            charging = ChargingState(
                access = AccessSnapshot(
                    direct = BackendStatus(available = true, granted = true, detail = "granted".toCaString()),
                    shizuku = BackendStatus(available = false, granted = false, detail = "not connected".toCaString()),
                ),
            ),
        )
        setContent(granted)

        compose.onNodeWithText(string(R.string.setup_access_copy_webusb)).assertDoesNotExist()
    }

    private fun needsSetup(shizuku: BackendStatus) = DashboardUiState(
        charging = ChargingState(
            access = AccessSnapshot(
                direct = BackendStatus(available = true, granted = false, detail = "not granted".toCaString()),
                shizuku = shizuku,
            ),
        ),
    )

    @Test
    fun `a detected Porter is named in the option and its action`() {
        setContent(
            needsSetup(
                BackendStatus(
                    available = true,
                    granted = false,
                    detail = "not granted".toCaString(),
                    manager = ManagerBackend.PORTER,
                ),
            ),
        )

        compose.onNodeWithText("Option 1 · Porter (recommended)").assertExists()
        compose.onNodeWithText("Allow Amply in Porter").assertExists()
    }

    @Test
    fun `an unidentified manager that is not running reads as either`() {
        setContent(
            needsSetup(
                BackendStatus(available = false, granted = false, detail = "not running".toCaString(), installed = true),
            ),
        )

        compose.onNodeWithText("Option 1 · Shizuku or Porter (recommended)").assertExists()
        compose.onNodeWithText("Open Shizuku or Porter").assertExists()
    }

    @Test
    fun `with nothing installed the action sets up Porter`() {
        setContent(
            needsSetup(
                BackendStatus(available = false, granted = false, detail = "missing".toCaString(), installed = false),
            ),
        )

        compose.onNodeWithText(string(R.string.contribution_install_shizuku)).assertExists()
        compose.onNodeWithText("How to set up Porter").assertExists()
    }

    @Test
    fun `card renders nothing on unsupported devices`() {
        val unsupported = DashboardUiState(
            charging = ChargingState(
                observation = ChargeObservation.Unsupported("Unsupported device".toCaString()),
            ),
        )
        setContent(unsupported)

        compose.onNodeWithText(string(R.string.setup_access_copy_webusb)).assertDoesNotExist()
    }
}
