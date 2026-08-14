package eu.darken.amply.main.ui.settings

import android.app.Application
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import eu.darken.amply.R
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The upgrade row belongs to "Other": it is a destination, not a preference, and heading the whole
 * screen it read as the app's first setting. The tall qualifier renders the entire list, so an
 * ordering failure means the wrong order rather than an item scrolled out of the viewport.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "+h2400dp")
class SettingsScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<Application>()

    private fun string(res: Int): String = context.getString(res)

    // Composed the way the row itself composes it, so the flavor's tier word (Pro / FOSS) is whatever
    // this build ships.
    private fun brandRowTitle(): String = context.getString(
        R.string.app_name_upgraded_template,
        string(R.string.app_name),
        string(R.string.app_name_upgrade_postfix),
    )

    private fun render() {
        compose.setContent {
            SettingsScreen(
                onBack = {},
                isPro = false,
                showProBadge = true,
                onUpgrade = {},
                onGeneral = {},
                gestureEnabled = true,
                onCharging = {},
                captureEnabled = true,
                onChargingHistory = {},
                showDiagnostics = true,
                diagnosticsReady = true,
                onDiagnostics = {},
                onSupport = {},
                onChangelog = {},
                onAcknowledgements = {},
                onPrivacy = {},
            )
        }
    }

    private fun topOf(text: String) = compose.onNodeWithText(text).getUnclippedBoundsInRoot().top

    @Test
    fun `the upgrade row heads the Other category`() {
        render()

        val other = topOf(string(R.string.settings_category_other))
        val brand = topOf(brandRowTitle())
        val support = topOf(string(R.string.settings_support_title))

        (other < brand) shouldBe true
        (brand < support) shouldBe true
    }

    @Test
    fun `the settings rows keep the top of the screen`() {
        render()

        // The first thing under the app bar is a real preference, not the upgrade entry.
        (topOf(string(R.string.settings_general_title)) < topOf(brandRowTitle())) shouldBe true
        (topOf(string(R.string.settings_charging_title)) < topOf(brandRowTitle())) shouldBe true
    }
}
