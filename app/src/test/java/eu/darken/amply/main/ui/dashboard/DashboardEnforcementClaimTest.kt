package eu.darken.amply.main.ui.dashboard

import android.app.Application
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import eu.darken.amply.R
import eu.darken.amply.charging.core.BackendKind
import eu.darken.amply.charging.core.ChargeObservation
import eu.darken.amply.charging.core.ChargePolicy
import eu.darken.amply.charging.core.ChargingState
import eu.darken.amply.common.ca.toCaString
import io.kotest.matchers.string.shouldContain
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

/**
 * The hero must not claim the battery is being protected when all it knows is that the mode is set.
 * A Xiaomi 13T with Adaptive configured and read back charged 59%→100% untouched (2026-08-16),
 * because HyperOS only engages Intelligent charging inside a learned overnight window.
 *
 * Asserted through the strings: both hero icons pass `contentDescription = null`, so the
 * checkmark-vs-shield branch itself is not reachable from Robolectric.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DashboardEnforcementClaimTest {
    @get:Rule
    val compose = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<Application>()

    private fun string(res: Int): String = context.getString(res)

    private fun render(
        observation: ChargeObservation,
        capAwaitsHardwareConfirmation: Boolean = false,
        adapterDetail: Int? = null,
    ) {
        compose.setContent {
            DashboardScreenUnderTest(
                state = DashboardUiState(
                    onboardingComplete = true,
                    charging = ChargingState(
                        controlEnabled = true,
                        observation = observation,
                        capAwaitsHardwareConfirmation = capAwaitsHardwareConfirmation,
                        adapterDetail = adapterDetail?.toCaString(),
                    ),
                ),
            )
        }
    }

    private fun readbackThrough(backend: BackendKind): String =
        context.getString(R.string.dashboard_detail_readback, backend.name.replace('_', ' ').lowercase())

    private fun conditionalReadbackThrough(backend: BackendKind): String = context.getString(
        R.string.dashboard_detail_readback_conditional,
        backend.name.replace('_', ' ').lowercase(),
    )

    @Test
    fun `a settings readback of adaptive says the system chooses when it applies`() {
        render(ChargeObservation.Verified(ChargePolicy.Adaptive, BackendKind.SHIZUKU))

        compose.onNodeWithText(conditionalReadbackThrough(BackendKind.SHIZUKU)).assertExists()
        compose.onNodeWithText(readbackThrough(BackendKind.SHIZUKU)).assertDoesNotExist()
        compose.onNodeWithText(string(R.string.dashboard_detail_hw_confirmed)).assertDoesNotExist()
    }

    @Test
    fun `a settings readback of adaptive never claims the policy is active`() {
        render(ChargeObservation.Verified(ChargePolicy.Adaptive, BackendKind.SHIZUKU))

        val active = context.getString(
            R.string.dashboard_status_verified_active,
            string(R.string.dashboard_policy_adaptive),
        )
        compose.onNodeWithText(active).assertDoesNotExist()
        // The mode is still named — it genuinely is the configured policy.
        compose.onNodeWithText(string(R.string.dashboard_policy_adaptive)).assertExists()
    }

    @Test
    fun `hardware-confirmed adaptive still reads as active`() {
        // Pixel state 5: the adaptive profile is engaged, which is real evidence.
        render(ChargeObservation.Verified(ChargePolicy.Adaptive, BackendKind.BATTERY_HARDWARE))

        val active = context.getString(
            R.string.dashboard_status_verified_active,
            string(R.string.dashboard_policy_adaptive),
        )
        compose.onNodeWithText(active).assertExists()
        compose.onNodeWithText(string(R.string.dashboard_detail_hw_confirmed)).assertExists()
        compose.onNodeWithText(conditionalReadbackThrough(BackendKind.BATTERY_HARDWARE)).assertDoesNotExist()
    }

    @Test
    fun `a fixed limit readback keeps the plain provenance line`() {
        // Non-regression for every unconditional policy on every sync-readback adapter.
        render(ChargeObservation.Verified(ChargePolicy.FixedLimit(80), BackendKind.SHIZUKU))

        compose.onNodeWithText(readbackThrough(BackendKind.SHIZUKU)).assertExists()
        compose.onNodeWithText(conditionalReadbackThrough(BackendKind.SHIZUKU)).assertDoesNotExist()
    }

    @Test
    fun `a hardware-confirmed cap on a latching rom keeps the affirmative card`() {
        // The state this whole change must leave reachable: a plug-latched adapter reads its cap back
        // through Shizuku (it has no other read path), and once the hardware confirms it, the card is
        // the ordinary affirmative one. Keying the withholding on the observation's backend instead of
        // on the repository's flag would make that permanently unreachable, and a test feeding only
        // BATTERY_HARDWARE observations would not notice.
        render(
            ChargeObservation.Verified(ChargePolicy.FixedLimit(80), BackendKind.SHIZUKU),
            capAwaitsHardwareConfirmation = false,
            adapterDetail = R.string.adapter_detail_grapheneos_ready,
        )

        compose.onNodeWithText(string(R.string.dashboard_cap_unconfirmed_note)).assertDoesNotExist()
        compose.onNodeWithText(readbackThrough(BackendKind.SHIZUKU)).assertExists()
        compose.onNodeWithText(string(R.string.adapter_detail_grapheneos_ready)).assertExists()
    }

    @Test
    fun `an unconfirmed cap is shown as set and never as taking effect`() {
        // Same read-back, same policy — only the hardware evidence is missing. The note has to say so,
        // and the adapter's standing "the system picks a change up when you reconnect" line has to go
        // with it: printed together they would answer the note with the effect it withholds.
        render(
            ChargeObservation.Verified(ChargePolicy.FixedLimit(80), BackendKind.SHIZUKU),
            capAwaitsHardwareConfirmation = true,
            adapterDetail = R.string.adapter_detail_grapheneos_ready,
        )

        compose.onNodeWithText(string(R.string.dashboard_cap_unconfirmed_note)).assertExists()
        compose.onNodeWithText(string(R.string.adapter_detail_grapheneos_ready)).assertDoesNotExist()
    }

    @Test
    fun `the adaptive description warns that it may not hold at other times`() {
        // Two assertions on purpose. The render check alone would pass with the old Pixel-flavoured
        // copy, which promised a hold without saying when it lapses; the content check is what
        // actually pins the warning, and fails if the string is reverted or softened.
        string(R.string.dashboard_policy_desc_adaptive) shouldContain "may not hold"

        render(ChargeObservation.Verified(ChargePolicy.Adaptive, BackendKind.SHIZUKU))
        compose.onNodeWithText(string(R.string.dashboard_policy_desc_adaptive)).assertExists()
    }
}
