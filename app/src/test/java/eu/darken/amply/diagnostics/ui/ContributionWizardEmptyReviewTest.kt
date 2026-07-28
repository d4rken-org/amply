package eu.darken.amply.diagnostics.ui

import android.app.Application
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import eu.darken.amply.R
import eu.darken.amply.charging.core.access.BackendStatus
import eu.darken.amply.charging.core.access.SettingNamespace
import eu.darken.amply.common.ca.toCaString
import eu.darken.amply.diagnostics.core.Disclosure
import eu.darken.amply.diagnostics.core.SettingId
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Guards the surfaces a contributor actually sees when a capture session produced nothing: a report with no rows is
 * worthless to a maintainer, so the wizard must say so rather than presenting a normal happy-path Next.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ContributionWizardEmptyReviewTest {

    @get:Rule
    val compose = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<Application>()

    private fun string(res: Int): String = context.getString(res)

    private val ready = BackendStatus(available = true, granted = true, detail = "ready".toCaString())

    private fun render(state: ContributionUiState, onRestart: () -> Unit = {}) {
        compose.setContent {
            ContributionWizardScreen(
                state = state,
                onExit = {},
                onRefreshStatus = {},
                onOpenShizuku = {},
                onAllowShizuku = {},
                onFeatureNameChange = {},
                onRomVersionChange = {},
                onNotesChange = {},
                onPendingLabelChange = {},
                onOpenNativeSettings = {},
                onCaptureMode = {},
                onSetEffect = { _, _ -> },
                onUndoLast = {},
                onRestart = onRestart,
                onRevealRow = {},
                onToggleInclude = {},
                onNext = {},
                onBack = {},
                onOpenIssue = {},
                onCopyReport = {},
                onEmail = {},
            )
        }
    }

    private fun reviewState(review: List<ReviewRowUi>) = ContributionUiState(
        step = WizardStep.REVIEW,
        shizuku = ready,
        modes = listOf(ModeSummary("Intelligent charging"), ModeSummary("Charge fully")),
        review = review,
    )

    private val populatedRow = ReviewRowUi(
        id = SettingId(SettingNamespace.SECURE, "charge_optimization_mode"),
        disclosure = Disclosure.AUTO,
        revealed = true,
        included = true,
        values = listOf("0", "1"),
    )

    /** An unknown key: redacted, not revealed, not included — the default state of a new device's real mapping row. */
    private val withheldRow = ReviewRowUi(
        id = SettingId(SettingNamespace.SECURE, "vendor_unknown_charge_key"),
        disclosure = Disclosure.REDACTED,
        revealed = false,
        included = false,
        values = null,
    )

    @Test
    fun `an empty review explains itself and cannot be delivered`() {
        render(reviewState(emptyList()))

        compose.onNodeWithText(string(R.string.contribution_review_empty_title)).assertExists()
        // No "send it anyway" path: a report with no settings never reaches an issue or the support inbox.
        compose.onNodeWithText(string(R.string.contribution_next)).assertIsNotEnabled()
    }

    @Test
    fun `a review with rows but nothing included cannot be delivered`() {
        render(reviewState(listOf(withheldRow)))

        compose.onNodeWithText(string(R.string.contribution_review_nothing_included_title)).assertExists()
        compose.onNodeWithText(string(R.string.contribution_next)).assertIsNotEnabled()
    }

    @Test
    fun `a populated review can be delivered`() {
        render(reviewState(listOf(populatedRow)))

        compose.onNodeWithText(string(R.string.contribution_next)).assertIsEnabled()
        compose.onNodeWithText(string(R.string.contribution_review_empty_title)).assertDoesNotExist()
        compose.onNodeWithText(string(R.string.contribution_review_nothing_included_title)).assertDoesNotExist()
    }

    // Tall screen: at Robolectric's 470px default the card's action lands behind the bottom bar and the click would
    // hit "Back" instead. Same precedent as DashboardScreenGestureTest.
    @Config(qualifiers = "+h2400dp")
    @Test
    fun `start over is reachable from an empty review`() {
        var restarted = false
        render(reviewState(emptyList()), onRestart = { restarted = true })

        compose.onNodeWithText(string(R.string.contribution_restart)).performClick()

        restarted shouldBe true
    }

    @Test
    fun `a single captured mode cannot advance to review`() {
        render(
            ContributionUiState(
                step = WizardStep.CAPTURE,
                shizuku = ready,
                modes = listOf(ModeSummary("Intelligent charging")),
            ),
        )

        compose.onNodeWithText(string(R.string.contribution_next)).assertIsNotEnabled()
    }

    @Test
    fun `two captured modes can advance to review`() {
        render(
            ContributionUiState(
                step = WizardStep.CAPTURE,
                shizuku = ready,
                modes = listOf(ModeSummary("Intelligent charging"), ModeSummary("Charge fully")),
            ),
        )

        compose.onNodeWithText(string(R.string.contribution_next)).assertIsEnabled()
    }
}
