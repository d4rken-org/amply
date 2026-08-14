package eu.darken.amply.main.ui.battery

import android.app.Application
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import eu.darken.amply.R
import eu.darken.amply.upgrade.ui.UpgradeScreenTags
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

/**
 * The badge states a condition of the feature, so it belongs to the title — inside the button it read
 * as part of the action.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CaptureOptInCardTest {
    @get:Rule
    val compose = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<Application>()

    private fun string(res: Int): String = context.getString(res)

    private fun render(showProBadge: Boolean) {
        compose.setContent {
            CaptureOptInCard(onEnable = {}, showProBadge = showProBadge)
        }
    }

    @Test
    fun `the badge sits beside the title, above the action`() {
        render(showProBadge = true)
        compose.onAllNodesWithTag(UpgradeScreenTags.PRO_BADGE).assertCountEquals(1)

        val badge = compose.onNodeWithTag(UpgradeScreenTags.PRO_BADGE).getUnclippedBoundsInRoot()
        val title = compose.onNodeWithText(string(R.string.stats_capture_title)).getUnclippedBoundsInRoot()
        val action = compose.onNodeWithText(string(R.string.stats_capture_optin_action))
            .getUnclippedBoundsInRoot()

        // Same line as the title...
        (badge.top < title.bottom) shouldBe true
        (title.right <= badge.left) shouldBe true
        // ...and nowhere near the button.
        (badge.bottom < action.top) shouldBe true
    }

    @Test
    fun `an upgraded card carries no badge at all`() {
        render(showProBadge = false)
        compose.onAllNodesWithTag(UpgradeScreenTags.PRO_BADGE).assertCountEquals(0)
        compose.onNodeWithText(string(R.string.stats_capture_optin_action)).assertExists()
    }
}
