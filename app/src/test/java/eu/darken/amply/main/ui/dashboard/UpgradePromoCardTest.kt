package eu.darken.amply.main.ui.dashboard

import android.app.Application
import androidx.compose.ui.test.junit4.createComposeRule
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

/**
 * The card has one action and the whole surface performs it. The action label must stay a label: a
 * nested button inside a clickable card is either a dead zone or a double fire.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class UpgradePromoCardTest {
    @get:Rule
    val compose = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<Application>()

    private fun string(res: Int): String = context.getString(res)

    private var upgrades = 0

    private fun render() {
        compose.setContent { UpgradePromoCard(onUpgrade = { upgrades++ }) }
    }

    @Test
    fun `tapping the card body upgrades`() {
        render()
        compose.onNodeWithText(string(R.string.dashboard_upgrade_body)).performClick()
        compose.runOnIdle { upgrades shouldBe 1 }
    }

    @Test
    fun `tapping the action label fires the card's action exactly once`() {
        render()
        compose.onNodeWithText(string(R.string.dashboard_upgrade_action)).performClick()
        compose.runOnIdle { upgrades shouldBe 1 }
    }

    @Test
    fun `the title is part of the same single tap target`() {
        render()
        compose.onNodeWithText(string(R.string.dashboard_upgrade_title)).performClick()
        compose.runOnIdle { upgrades shouldBe 1 }
    }
}
