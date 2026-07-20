package eu.darken.amply.main.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import eu.darken.amply.R
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MainActivityTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    private fun str(id: Int) = composeRule.activity.getString(id)

    @Test
    fun firstLaunchWelcomeThenCaveats() {
        // Page 1 is the welcome page; the interactive setup guide moved to the dashboard and must
        // not appear during onboarding.
        composeRule.onNodeWithText(str(R.string.onboarding_welcome_title)).assertIsDisplayed()
        composeRule.onNodeWithText("Option 1 · Shizuku").assertDoesNotExist()
        composeRule.onNodeWithText("Option 2 · Computer").assertDoesNotExist()

        // Next reveals the caveats page with both caveat cards, still without the setup guide.
        composeRule.onNodeWithText(str(R.string.onboarding_next_action)).performScrollTo().performClick()
        composeRule.onNodeWithText(str(R.string.onboarding_caveat_support_title)).assertIsDisplayed()
        composeRule.onNodeWithText(str(R.string.onboarding_caveat_setup_title)).assertIsDisplayed()
        composeRule.onNodeWithText(str(R.string.onboarding_continue_action)).assertIsDisplayed()
        composeRule.onNodeWithText("Option 1 · Shizuku").assertDoesNotExist()

        // Back returns to the welcome page.
        composeRule.onNodeWithText(str(R.string.onboarding_back_action)).performScrollTo().performClick()
        composeRule.onNodeWithText(str(R.string.onboarding_welcome_title)).assertIsDisplayed()
    }
}
