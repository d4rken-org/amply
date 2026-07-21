package eu.darken.amply.main.ui.settings

import android.app.Application
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
class ReconnectGestureSettingsScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private fun string(res: Int): String =
        ApplicationProvider.getApplicationContext<Application>().getString(res)

    @Test
    fun `any-level toggle forwards the change`() {
        var changed: Boolean? = null
        compose.setContent {
            ReconnectGestureSettingsScreen(
                gestureEnabled = true,
                anyLevelEnabled = false,
                onBack = {},
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
            ReconnectGestureSettingsScreen(
                gestureEnabled = false,
                anyLevelEnabled = false,
                onBack = {},
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
            ReconnectGestureSettingsScreen(
                gestureEnabled = true,
                anyLevelEnabled = true,
                onBack = { backed = true },
                onAnyLevelChange = {},
            )
        }

        compose.onNodeWithContentDescription(string(R.string.action_back)).performClick()

        compose.runOnIdle { backed shouldBe true }
    }
}
