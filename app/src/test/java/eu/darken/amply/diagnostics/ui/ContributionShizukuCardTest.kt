package eu.darken.amply.diagnostics.ui

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import eu.darken.amply.charging.core.access.BackendStatus
import eu.darken.amply.charging.core.access.shizuku.ManagerBackend
import eu.darken.amply.common.ca.toCaString
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

/** The intro step's access card names the detected manager, and both when none was identified. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ContributionShizukuCardTest {

    @get:Rule
    val compose = createComposeRule()

    private fun render(shizuku: BackendStatus?) {
        compose.setContent {
            ContributionWizardScreen(
                state = ContributionUiState(step = WizardStep.INTRO, shizuku = shizuku),
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
                onRestart = {},
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

    @Test
    fun `a Porter waiting for permission is named in the title and button`() {
        render(
            BackendStatus(
                available = true,
                granted = false,
                detail = "not granted".toCaString(),
                manager = ManagerBackend.PORTER,
            ),
        )
        compose.onNodeWithText("Porter needed").assertExists()
        compose.onNodeWithText("Allow Amply in Porter").assertExists()
    }

    @Test
    fun `a stopped Shizuku is named in the open button`() {
        render(
            BackendStatus(
                available = false,
                granted = false,
                installed = true,
                detail = "not running".toCaString(),
                manager = ManagerBackend.SHIZUKU,
            ),
        )
        compose.onNodeWithText("Open Shizuku").assertExists()
    }

    @Test
    fun `an unidentified manager reads as either`() {
        render(
            BackendStatus(
                available = false,
                granted = false,
                installed = true,
                detail = "not running".toCaString(),
            ),
        )
        compose.onNodeWithText("Shizuku or Porter needed").assertExists()
        compose.onNodeWithText("Open Shizuku or Porter").assertExists()
    }

    @Test
    fun `with nothing installed the card points at Porter's setup`() {
        render(
            BackendStatus(
                available = false,
                granted = false,
                installed = false,
                detail = "missing".toCaString(),
            ),
        )
        compose.onNodeWithText("How to set up Porter").assertExists()
    }

    @Test
    fun `while probing the card names neither`() {
        render(null)
        compose.onNodeWithText("Shizuku or Porter needed").assertExists()
        compose.onNodeWithText("Checking privileged access…").assertExists()
    }
}
