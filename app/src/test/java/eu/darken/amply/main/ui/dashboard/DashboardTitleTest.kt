package eu.darken.amply.main.ui.dashboard

import android.app.Application
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import eu.darken.amply.R
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

/**
 * The title names the tier only for a settled, upgraded entitlement. Unresolved is the state of every
 * cold start, so it must read exactly like free — a title that claims "Pro" for a frame and takes it
 * back is worse than one that never claimed it.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DashboardTitleTest {
    @get:Rule
    val compose = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<Application>()

    private fun string(res: Int): String = context.getString(res)

    // Composed the way the title composes it, so the flavor's tier word (Pro / FOSS) is whatever this
    // build ships.
    private fun upgradedTitle(): String = context.getString(
        R.string.app_name_upgraded_template,
        string(R.string.app_name),
        string(R.string.app_name_upgrade_postfix),
    )

    private fun render(upgrade: UpgradeSnapshot?) {
        compose.setContent {
            DashboardScreenUnderTest(state = DashboardUiState(onboardingComplete = true, upgrade = upgrade))
        }
    }

    @Test
    fun `an unresolved entitlement shows the plain app name`() {
        render(upgrade = null)
        compose.onNodeWithText(string(R.string.app_name)).assertExists()
        compose.onNodeWithText(upgradedTitle()).assertDoesNotExist()
    }

    @Test
    fun `a settled free entitlement shows the plain app name`() {
        render(upgrade = UpgradeSnapshot(isPro = false, isSettled = true))
        compose.onNodeWithText(string(R.string.app_name)).assertExists()
        compose.onNodeWithText(upgradedTitle()).assertDoesNotExist()
    }

    @Test
    fun `an upgraded entitlement names the tier`() {
        render(upgrade = UpgradeSnapshot(isPro = true, isSettled = true))
        compose.onNodeWithText(upgradedTitle()).assertExists()
    }
}
