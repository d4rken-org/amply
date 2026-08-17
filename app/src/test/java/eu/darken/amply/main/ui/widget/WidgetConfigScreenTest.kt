package eu.darken.amply.main.ui.widget

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import eu.darken.amply.R
import eu.darken.amply.charging.core.ChargePolicy
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WidgetConfigScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private val context: Application = ApplicationProvider.getApplicationContext()

    private fun string(res: Int): String = context.getString(res)

    private val ready = WidgetConfigState.Ready(
        availablePolicies = listOf(
            ChargePolicy.FixedLimit(80),
            ChargePolicy.FixedLimit(90),
            ChargePolicy.Adaptive,
            ChargePolicy.Unrestricted,
        ),
        selectedPolicyIds = listOf("fixed:80", "unrestricted"),
    )

    private fun screen(
        state: WidgetConfigState,
        onToggle: (ChargePolicy, Boolean) -> Unit = { _, _ -> },
        onConfirm: () -> Unit = {},
        onDone: () -> Unit = {},
        onRetry: () -> Unit = {},
        onUpgrade: () -> Unit = {},
    ) = compose.setContent {
        WidgetConfigScreen(
            state = state,
            onToggle = onToggle,
            onConfirm = onConfirm,
            onDone = onDone,
            onRetry = onRetry,
            onUpgrade = onUpgrade,
        )
    }

    @Test
    fun `the picker shows the resolved selection`() {
        screen(ready)

        compose.onNodeWithText(string(R.string.charging_policy_adaptive_label)).assertIsDisplayed()
        compose.onNodeWithText(string(R.string.charging_policy_unrestricted_label)).assertIsDisplayed()
        compose.onNodeWithText(string(R.string.widget_config_confirm_action)).assertExists()
    }

    @Test
    fun `toggling a row reports its policy`() {
        var toggled: Pair<ChargePolicy, Boolean>? = null
        screen(ready, onToggle = { policy, selected -> toggled = policy to selected })

        compose.onNodeWithText(string(R.string.charging_policy_adaptive_label))
            .performScrollTo()
            .performClick()

        compose.runOnIdle { toggled shouldBe (ChargePolicy.Adaptive to true) }
    }

    @Test
    fun `confirming reports once`() {
        var confirmed = 0
        screen(ready, onConfirm = { confirmed++ })

        compose.onNodeWithText(string(R.string.widget_config_confirm_action)).performScrollTo().performClick()

        compose.runOnIdle { confirmed shouldBe 1 }
    }

    @Test
    fun `confirming is impossible while a save is in flight`() {
        var confirmed = 0
        screen(ready.copy(saving = true), onConfirm = { confirmed++ })

        compose.onNodeWithText(string(R.string.widget_config_confirm_action))
            .performScrollTo()
            .assertIsNotEnabled()
            .performClick()

        compose.runOnIdle { confirmed shouldBe 0 }
    }

    @Test
    fun `a failed save is reported and stays on the picker`() {
        screen(ready.copy(saveFailed = true))

        compose.onNodeWithText(string(R.string.widget_config_save_failed)).assertExists()
        compose.onNodeWithText(string(R.string.widget_config_confirm_action)).assertExists()
    }

    /**
     * The property that keeps a widget placeable: on API 26–30 the host discards a widget whose
     * configuration ends cancelled, so no state may leave the user without a confirming exit.
     */
    private fun assertOffersWayOut(state: WidgetConfigState) {
        var done = 0
        screen(state, onDone = { done++ })

        compose.onNodeWithText(string(R.string.widget_config_done_action)).performClick()

        compose.runOnIdle { done shouldBe 1 }
    }

    @Test
    fun `the loading state offers a way out that keeps the widget`() = assertOffersWayOut(WidgetConfigState.Loading)

    @Test
    fun `the locked state offers a way out that keeps the widget`() = assertOffersWayOut(WidgetConfigState.Locked)

    @Test
    fun `the error state offers a way out that keeps the widget`() = assertOffersWayOut(WidgetConfigState.Error)

    @Test
    fun `an uncontrollable device offers a way out that keeps the widget`() =
        assertOffersWayOut(WidgetConfigState.Unavailable)

    @Test
    fun `a device with nothing to configure offers a way out that keeps the widget`() =
        assertOffersWayOut(WidgetConfigState.NotConfigurable)

    @Test
    fun `the locked state offers the upgrade next to the way out`() {
        var upgraded = 0
        var done = 0
        screen(WidgetConfigState.Locked, onDone = { done++ }, onUpgrade = { upgraded++ })

        compose.onNodeWithText(string(R.string.widget_config_locked)).assertIsDisplayed()
        compose.onNodeWithText(string(R.string.widget_config_upgrade_action)).performClick()
        compose.onNodeWithText(string(R.string.widget_config_done_action)).performClick()

        compose.runOnIdle {
            upgraded shouldBe 1
            done shouldBe 1
        }
    }

    @Test
    fun `the error state can be retried`() {
        var retried = 0
        screen(WidgetConfigState.Error, onRetry = { retried++ })

        compose.onNodeWithText(string(R.string.widget_config_error)).assertIsDisplayed()
        compose.onNodeWithText(string(R.string.widget_config_retry_action)).performClick()

        compose.runOnIdle { retried shouldBe 1 }
    }
}
