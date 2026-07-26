package eu.darken.amply.main.ui.settings

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
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
class ChargingSettingsScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private fun string(res: Int): String =
        ApplicationProvider.getApplicationContext<Application>().getString(res)

    @Test
    fun `the gesture toggle forwards the change`() {
        var changed: Boolean? = null
        compose.setContent {
            ChargingSettingsScreen(
                gestureEnabled = false,
                anyLevelEnabled = false,
                canEnableGesture = true,
                onBack = {},
                onGestureEnabledChange = { changed = it },
                onAnyLevelChange = {},
            )
        }

        compose.onNodeWithText(string(R.string.settings_reconnect_enabled_title)).performClick()

        compose.runOnIdle { changed shouldBe true }
    }

    // Enabling must be impossible exactly where the dashboard card forbids it, but the row stays
    // visible — an already-enabled gesture on such a device must remain switchable off.
    @Test
    fun `the gesture toggle is inert but visible when the device cannot use it`() {
        var changed: Boolean? = null
        compose.setContent {
            ChargingSettingsScreen(
                gestureEnabled = false,
                anyLevelEnabled = false,
                canEnableGesture = false,
                onBack = {},
                onGestureEnabledChange = { changed = it },
                onAnyLevelChange = {},
            )
        }

        compose.onNodeWithText(string(R.string.settings_reconnect_enabled_title)).assertIsDisplayed()
        compose.onNodeWithText(string(R.string.settings_reconnect_enabled_unavailable)).assertIsDisplayed()
        compose.onNodeWithText(string(R.string.settings_reconnect_enabled_title)).performClick()

        compose.runOnIdle { changed shouldBe null }
    }

    @Test
    fun `an enabled gesture can still be switched off on an unsupported device`() {
        var changed: Boolean? = null
        compose.setContent {
            ChargingSettingsScreen(
                gestureEnabled = true,
                anyLevelEnabled = false,
                canEnableGesture = false,
                onBack = {},
                onGestureEnabledChange = { changed = it },
                onAnyLevelChange = {},
            )
        }

        compose.onNodeWithText(string(R.string.settings_reconnect_enabled_title)).performClick()

        compose.runOnIdle { changed shouldBe false }
    }

    @Test
    fun `any-level toggle forwards the change`() {
        var changed: Boolean? = null
        compose.setContent {
            ChargingSettingsScreen(
                gestureEnabled = true,
                anyLevelEnabled = false,
                canEnableGesture = true,
                onBack = {},
                onGestureEnabledChange = {},
                onAnyLevelChange = { changed = it },
            )
        }

        compose.onNodeWithText(string(R.string.settings_reconnect_any_level_title)).performClick()

        compose.runOnIdle { changed shouldBe true }
    }

    @Test
    fun `any-level toggle is inert while the gesture is disabled`() {
        var changed: Boolean? = null
        compose.setContent {
            ChargingSettingsScreen(
                gestureEnabled = false,
                anyLevelEnabled = false,
                canEnableGesture = true,
                onBack = {},
                onGestureEnabledChange = {},
                onAnyLevelChange = { changed = it },
            )
        }

        compose.onNodeWithText(string(R.string.settings_reconnect_any_level_title)).performClick()

        compose.runOnIdle { changed shouldBe null }
    }

    @Test
    fun `top bar back navigates`() {
        var backed = false
        compose.setContent {
            ChargingSettingsScreen(
                gestureEnabled = true,
                anyLevelEnabled = true,
                canEnableGesture = true,
                onBack = { backed = true },
                onGestureEnabledChange = {},
                onAnyLevelChange = {},
            )
        }

        compose.onNodeWithContentDescription(string(R.string.action_back)).performClick()

        compose.runOnIdle { backed shouldBe true }
    }
}
