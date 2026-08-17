package eu.darken.amply.main.ui.settings

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
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
class ChargingSettingsScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private val context: Application = ApplicationProvider.getApplicationContext()

    private fun string(res: Int): String = context.getString(res)

    private fun string(res: Int, arg: Any): String = context.getString(res, arg)

    private val richPolicies = listOf(
        ChargePolicy.FixedLimit(80),
        ChargePolicy.FixedLimit(90),
        ChargePolicy.Adaptive,
        ChargePolicy.Unrestricted,
    )

    private fun screen(
        gestureEnabled: Boolean = true,
        anyLevelEnabled: Boolean = false,
        canEnableGesture: Boolean = true,
        availablePolicies: List<ChargePolicy> = emptyList(),
        selectedPolicyIds: List<String> = emptyList(),
        onGestureEnabledChange: (Boolean) -> Unit = {},
        onAnyLevelChange: (Boolean) -> Unit = {},
        onNotificationPolicyToggle: (ChargePolicy, Boolean) -> Unit = { _, _ -> },
        onBack: () -> Unit = {},
    ) = compose.setContent {
        ChargingSettingsScreen(
            gestureEnabled = gestureEnabled,
            anyLevelEnabled = anyLevelEnabled,
            canEnableGesture = canEnableGesture,
            availablePolicies = availablePolicies,
            selectedPolicyIds = selectedPolicyIds,
            onBack = onBack,
            onGestureEnabledChange = onGestureEnabledChange,
            onAnyLevelChange = onAnyLevelChange,
            onNotificationPolicyToggle = onNotificationPolicyToggle,
        )
    }

    @Test
    fun `the gesture toggle forwards the change`() {
        var changed: Boolean? = null
        screen(gestureEnabled = false, onGestureEnabledChange = { changed = it })

        compose.onNodeWithText(string(R.string.settings_reconnect_enabled_title)).performClick()

        compose.runOnIdle { changed shouldBe true }
    }

    // Enabling must be impossible exactly where the dashboard card forbids it, but the row stays
    // visible — an already-enabled gesture on such a device must remain switchable off.
    @Test
    fun `the gesture toggle is inert but visible when the device cannot use it`() {
        var changed: Boolean? = null
        screen(
            gestureEnabled = false,
            canEnableGesture = false,
            onGestureEnabledChange = { changed = it },
        )

        compose.onNodeWithText(string(R.string.settings_reconnect_enabled_title)).assertIsDisplayed()
        compose.onNodeWithText(string(R.string.settings_reconnect_enabled_unavailable)).assertIsDisplayed()
        compose.onNodeWithText(string(R.string.settings_reconnect_enabled_title)).performClick()

        compose.runOnIdle { changed shouldBe null }
    }

    @Test
    fun `an enabled gesture can still be switched off on an unsupported device`() {
        var changed: Boolean? = null
        screen(canEnableGesture = false, onGestureEnabledChange = { changed = it })

        compose.onNodeWithText(string(R.string.settings_reconnect_enabled_title)).performClick()

        compose.runOnIdle { changed shouldBe false }
    }

    @Test
    fun `any-level toggle forwards the change`() {
        var changed: Boolean? = null
        screen(onAnyLevelChange = { changed = it })

        compose.onNodeWithText(string(R.string.settings_reconnect_any_level_title)).performClick()

        compose.runOnIdle { changed shouldBe true }
    }

    @Test
    fun `any-level toggle is inert while the gesture is disabled`() {
        var changed: Boolean? = null
        screen(gestureEnabled = false, onAnyLevelChange = { changed = it })

        compose.onNodeWithText(string(R.string.settings_reconnect_any_level_title)).performClick()

        compose.runOnIdle { changed shouldBe null }
    }

    @Test
    fun `top bar back navigates`() {
        var backed = false
        screen(onBack = { backed = true })

        compose.onNodeWithContentDescription(string(R.string.action_back)).performClick()

        compose.runOnIdle { backed shouldBe true }
    }

    @Test
    fun `the notification-button section is hidden where there is nothing to pick`() {
        screen(
            availablePolicies = listOf(ChargePolicy.FixedLimit(80), ChargePolicy.Unrestricted),
            selectedPolicyIds = listOf("fixed:80", "unrestricted"),
        )

        compose.onNodeWithText(string(R.string.settings_reconnect_notification_actions_category))
            .assertDoesNotExist()
    }

    @Test
    fun `the notification-button section is hidden before an adapter resolves`() {
        screen(availablePolicies = emptyList(), selectedPolicyIds = emptyList())

        compose.onNodeWithText(string(R.string.settings_reconnect_notification_actions_category))
            .assertDoesNotExist()
    }

    @Test
    fun `the notification-button section is hidden where the gesture is unavailable`() {
        screen(
            gestureEnabled = false,
            canEnableGesture = false,
            availablePolicies = richPolicies,
            selectedPolicyIds = listOf("fixed:80", "unrestricted"),
        )

        compose.onNodeWithText(string(R.string.settings_reconnect_notification_actions_category))
            .assertDoesNotExist()
    }

    @Test
    fun `the notification-button section shows one row per policy`() {
        screen(
            availablePolicies = richPolicies,
            selectedPolicyIds = listOf("fixed:80", "unrestricted"),
        )

        compose.onNodeWithText(string(R.string.settings_reconnect_notification_actions_category))
            .assertExists()
        richPolicies.forEach {
            compose.onNodeWithText(policyLabel(it)).assertExists()
        }
    }

    @Test
    fun `toggling a row reports its policy`() {
        var toggled: Pair<ChargePolicy, Boolean>? = null
        screen(
            availablePolicies = richPolicies,
            selectedPolicyIds = listOf("fixed:80", "unrestricted"),
            onNotificationPolicyToggle = { policy, selected -> toggled = policy to selected },
        )

        compose.onNodeWithText(policyLabel(ChargePolicy.Adaptive)).performScrollTo().performClick()

        compose.runOnIdle { toggled shouldBe (ChargePolicy.Adaptive to true) }
    }

    @Test
    fun `the last selected row cannot be unchecked`() {
        var toggled: Pair<ChargePolicy, Boolean>? = null
        screen(
            availablePolicies = richPolicies,
            selectedPolicyIds = listOf("adaptive"),
            onNotificationPolicyToggle = { policy, selected -> toggled = policy to selected },
        )

        compose.onNodeWithText(policyLabel(ChargePolicy.Adaptive)).performScrollTo().performClick()

        compose.runOnIdle { toggled shouldBe null }
    }

    @Test
    fun `no fourth row can be checked once three are selected`() {
        var toggled: Pair<ChargePolicy, Boolean>? = null
        screen(
            availablePolicies = richPolicies,
            selectedPolicyIds = listOf("fixed:80", "adaptive", "unrestricted"),
            onNotificationPolicyToggle = { policy, selected -> toggled = policy to selected },
        )

        compose.onNodeWithText(policyLabel(ChargePolicy.FixedLimit(90))).performScrollTo().performClick()

        compose.runOnIdle { toggled shouldBe null }
        // A selected row stays interactive: swapping one out is how a fourth becomes available.
        compose.onNodeWithText(policyLabel(ChargePolicy.Adaptive)).performScrollTo().performClick()
        compose.runOnIdle { toggled shouldBe (ChargePolicy.Adaptive to false) }
    }

    @Test
    fun `the rows are inert while the gesture is off`() {
        var toggled: Pair<ChargePolicy, Boolean>? = null
        screen(
            gestureEnabled = false,
            availablePolicies = richPolicies,
            selectedPolicyIds = listOf("fixed:80", "unrestricted"),
            onNotificationPolicyToggle = { policy, selected -> toggled = policy to selected },
        )

        compose.onNodeWithText(policyLabel(ChargePolicy.Adaptive)).performScrollTo().performClick()

        compose.runOnIdle { toggled shouldBe null }
    }

    private fun policyLabel(policy: ChargePolicy): String = when (policy) {
        is ChargePolicy.FixedLimit -> string(R.string.charging_policy_fixed_label, policy.percent)
        ChargePolicy.Adaptive -> string(R.string.charging_policy_adaptive_label)
        ChargePolicy.Unrestricted -> string(R.string.charging_policy_unrestricted_label)
        ChargePolicy.PauseAtFull -> string(R.string.charging_policy_pause_at_full_label)
    }
}
