package eu.darken.amply.main.ui.dashboard

import android.app.Application
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ApplicationProvider
import eu.darken.amply.R
import eu.darken.amply.charging.core.BackendKind
import eu.darken.amply.charging.core.ChargeObservation
import eu.darken.amply.charging.core.ChargePolicy
import eu.darken.amply.charging.core.ChargingState
import eu.darken.amply.charging.core.enforcement.EnforcementStatus
import eu.darken.amply.common.ca.toCaString
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

/**
 * The dashboard's side of the enforcement gate: a device may not present a settings read-back as
 * protection, and a device whose controls are withheld must say why and offer the way out.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DashboardEnforcementCardTest {
    @get:Rule
    val compose = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<Application>()

    private fun string(res: Int): String = context.getString(res)

    private fun state(status: EnforcementStatus?, controlEnabled: Boolean) = DashboardUiState(
        onboardingComplete = true,
        charging = ChargingState(
            adapterId = "lineageos-chargingcontrol-v1",
            controlEnabled = controlEnabled,
            enforcement = status,
            syncVerification = true,
            observation = if (controlEnabled) {
                ChargeObservation.Verified(ChargePolicy.FixedLimit(80), BackendKind.SHIZUKU)
            } else {
                ChargeObservation.Unsupported("not verified".toCaString())
            },
        ),
    )

    private fun render(state: DashboardUiState, onStartVerification: () -> Unit = {}) {
        compose.setContent {
            DashboardScreenUnderTest(state = state, onStartVerification = onStartVerification)
        }
    }

    private fun scrollToCard() {
        compose.onNode(hasScrollAction()).performScrollToNode(hasTestTag(ENFORCEMENT_CARD_TEST_TAG))
    }

    /** Scoped to the hero, so a string that also appears on a card further down can't satisfy it. */
    private fun heroText(res: Int) = compose.onNode(
        hasText(string(res)) and hasAnyAncestor(hasTestTag(HERO_CARD_TEST_TAG)),
    )

    @Test
    fun `a candidate device explains itself and offers the opt-in`() {
        var started = false
        render(state(EnforcementStatus.CANDIDATE, controlEnabled = false)) { started = true }

        scrollToCard()
        compose.onNodeWithText(string(R.string.dashboard_enforcement_candidate_title)).assertExists()
        compose.onNodeWithText(string(R.string.dashboard_enforcement_candidate_action)).performClick()
        started shouldBe true
    }

    @Test
    fun `a candidate device is not announced as unsupported`() {
        // The adapter matched — control is merely switched off until the user accepts it unconfirmed.
        // The generic unsupported copy here would contradict the card directly below the hero.
        render(state(EnforcementStatus.CANDIDATE, controlEnabled = false))

        compose.onNode(hasScrollAction()).performScrollToNode(hasTestTag(HERO_CARD_TEST_TAG))
        heroText(R.string.dashboard_status_unsupported).assertDoesNotExist()
        heroText(R.string.dashboard_enforcement_candidate_hero_title).assertExists()
        heroText(R.string.dashboard_enforcement_candidate_hero_body).assertExists()
    }

    @Test
    fun `a refuted device names the refutation instead of calling itself unsupported`() {
        render(state(EnforcementStatus.REFUTED, controlEnabled = false))

        compose.onNode(hasScrollAction()).performScrollToNode(hasTestTag(HERO_CARD_TEST_TAG))
        heroText(R.string.dashboard_status_unsupported).assertDoesNotExist()
        heroText(R.string.dashboard_enforcement_refuted_hero_title).assertExists()
        // The card below owns the explanation; the hero must not repeat its paragraph.
        heroText(R.string.dashboard_enforcement_refuted_body).assertDoesNotExist()
    }

    @Test
    fun `a probe refusal keeps the generic unsupported wording`() {
        // No tier at all (secondary user): nothing licenses the tier-specific copy here.
        render(state(status = null, controlEnabled = false))

        compose.onNode(hasScrollAction()).performScrollToNode(hasTestTag(HERO_CARD_TEST_TAG))
        heroText(R.string.dashboard_status_unsupported).assertExists()
    }

    @Test
    fun `an unconfirmed device is not shown as confirmed`() {
        render(state(EnforcementStatus.UNVERIFIED, controlEnabled = true))

        // The card is present…
        scrollToCard()
        compose.onNodeWithText(string(R.string.dashboard_enforcement_unverified_title)).assertExists()
        // …and the hero qualifies the verified read-back instead of leaving it to stand alone.
        compose.onNode(hasScrollAction()).performScrollToNode(hasTestTag(HERO_CARD_TEST_TAG))
        compose.onNodeWithText(string(R.string.dashboard_enforcement_unverified_note)).assertExists()
    }

    @Test
    fun `a refuted device is warned about`() {
        render(state(EnforcementStatus.REFUTED, controlEnabled = false))

        scrollToCard()
        compose.onNodeWithText(string(R.string.dashboard_enforcement_refuted_title)).assertExists()
    }

    @Test
    fun `a confirmed device shows no card at all`() {
        render(state(EnforcementStatus.CONFIRMED, controlEnabled = true))

        compose.onNodeWithTag(ENFORCEMENT_CARD_TEST_TAG).assertDoesNotExist()
        compose.onNodeWithText(string(R.string.dashboard_enforcement_unverified_note)).assertDoesNotExist()
    }

    @Test
    fun `a secondary user is offered no opt-in`() {
        // The registry leaves enforcement unset when the probe itself refused control (here: the
        // Lineage keys are device-wide, so control is main-user only). Offering the opt-in there
        // would enable controls that cannot write anything.
        var started = false
        render(state(status = null, controlEnabled = false)) { started = true }

        compose.onNodeWithTag(ENFORCEMENT_CARD_TEST_TAG).assertDoesNotExist()
        compose.onNodeWithText(string(R.string.dashboard_enforcement_candidate_action)).assertDoesNotExist()
        started shouldBe false
    }

    @Test
    fun `an adapter the question does not apply to shows no card at all`() {
        render(state(status = null, controlEnabled = true))

        compose.onNodeWithTag(ENFORCEMENT_CARD_TEST_TAG).assertDoesNotExist()
        compose.onNodeWithText(string(R.string.dashboard_enforcement_unverified_note)).assertDoesNotExist()
    }
}
