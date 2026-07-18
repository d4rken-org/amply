package eu.darken.amply.main.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MainActivityTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun firstLaunchRendersSetupGuide() {
        composeRule.onNodeWithText("Welcome to Amply").assertIsDisplayed()
        composeRule.onNodeWithText("Set up charge control").assertIsDisplayed()
        composeRule.onNodeWithText("Option 1 · Shizuku").assertIsDisplayed()
        composeRule.onNodeWithText("Option 2 · Computer").assertIsDisplayed()
    }
}
