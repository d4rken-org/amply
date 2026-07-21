package eu.darken.amply.common.settings

import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SettingsSwitchItemTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `enabled item toggles via row click`() {
        var toggled: Boolean? = null
        compose.setContent {
            SettingsSwitchItem(
                title = "Any charge level",
                subtitle = "subtitle",
                checked = false,
                onCheckedChange = { toggled = it },
            )
        }

        compose.onNodeWithText("Any charge level").performClick()

        compose.runOnIdle { toggled shouldBe true }
    }

    @Test
    fun `disabled item cannot invoke its callback`() {
        var toggled: Boolean? = null
        compose.setContent {
            SettingsSwitchItem(
                title = "Any charge level",
                subtitle = "subtitle",
                checked = false,
                onCheckedChange = { toggled = it },
                enabled = false,
            )
        }

        compose.onNodeWithText("Any charge level").performClick()
        compose.onNode(isToggleable()).assertIsNotEnabled().performClick()

        compose.runOnIdle { toggled shouldBe null }
    }

    @Test
    fun `checked state is reflected by the switch`() {
        compose.setContent {
            SettingsSwitchItem(
                title = "Any charge level",
                subtitle = "subtitle",
                checked = true,
                onCheckedChange = {},
            )
        }

        compose.onNode(isToggleable()).assertIsOn()
    }
}
