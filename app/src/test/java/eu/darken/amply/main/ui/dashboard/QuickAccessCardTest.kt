package eu.darken.amply.main.ui.dashboard

import android.app.Application
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
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
 * The badge belongs to the card, not to its two buttons: inside the buttons it appeared twice and ate
 * the width their labels need.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class QuickAccessCardTest {
    @get:Rule
    val compose = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<Application>()

    private fun string(res: Int): String = context.getString(res)

    private fun render(showProBadge: Boolean) {
        compose.setContent {
            QuickAccessCard(
                widgetAdded = false,
                tileAdded = false,
                tileRequestPending = false,
                showProBadge = showProBadge,
                onPinWidget = {},
                onAddTile = {},
                onDismiss = {},
            )
        }
    }

    @Test
    fun `a gated card carries exactly one badge`() {
        render(showProBadge = true)
        compose.onAllNodesWithTag(UpgradeScreenTags.PRO_BADGE).assertCountEquals(1)
    }

    @Test
    fun `the badge sits in the header, above the buttons that used to carry it`() {
        render(showProBadge = true)
        val badgeTop = compose.onNodeWithTag(UpgradeScreenTags.PRO_BADGE).getUnclippedBoundsInRoot().top
        val widgetTop = compose.onNodeWithText(string(R.string.dashboard_quickaccess_add_widget))
            .getUnclippedBoundsInRoot().top
        val tileTop = compose.onNodeWithText(string(R.string.dashboard_quickaccess_add_tile))
            .getUnclippedBoundsInRoot().top
        (badgeTop < widgetTop) shouldBe true
        (badgeTop < tileTop) shouldBe true
    }

    @Test
    fun `an upgraded card carries no badge at all`() {
        render(showProBadge = false)
        compose.onAllNodesWithTag(UpgradeScreenTags.PRO_BADGE).assertCountEquals(0)
    }

    @Test
    fun `the dismiss action survives the badge in the header`() {
        render(showProBadge = true)
        compose.onNodeWithContentDescription(string(R.string.dashboard_quickaccess_dismiss)).assertExists()
    }
}
