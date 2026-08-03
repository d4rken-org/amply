package eu.darken.amply.main.ui.setup

import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import eu.darken.amply.R
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

/**
 * Guards which contribution paths an unsupported device is offered. The metadata-only GitHub path is worth a public
 * issue only where the metadata names something to chase; for a device whose every probe came back empty it would
 * name no family, marker or key at all.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class UnsupportedDeviceCardTest {

    @get:Rule
    val compose = createComposeRule()

    private fun string(res: Int): String =
        ApplicationProvider.getApplicationContext<Application>().getString(res)

    private fun render(
        hasSupportLead: Boolean,
        reportPreview: String? = "manufacturer=samsung\nadapter=samsung-lab",
        onPrepareReport: () -> Unit = {},
    ) {
        compose.setContent {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                UnsupportedDeviceCard(
                    manufacturer = "Samsung",
                    hasSupportLead = hasSupportLead,
                    reportPreview = reportPreview,
                    onOpenWizard = {},
                    onPrepareReport = onPrepareReport,
                    onCopyReport = {},
                    onOpenIssue = {},
                    onEmail = {},
                    onHelp = {},
                )
            }
        }
    }

    @Test
    fun `a device with a lead is offered the metadata-only report`() {
        render(hasSupportLead = true)

        compose.onNodeWithText(string(R.string.setup_unsupported_request_action)).assertExists()
    }

    @Test
    fun `a device without a lead is not offered the metadata-only report`() {
        render(hasSupportLead = false)

        compose.onNodeWithText(string(R.string.setup_unsupported_request_action)).assertDoesNotExist()
    }

    @Test
    fun `the wizard and email paths stay available without a lead`() {
        render(hasSupportLead = false)

        compose.onNodeWithText(string(R.string.setup_unsupported_wizard_action)).assertExists()
        compose.onNodeWithText(string(R.string.setup_unsupported_email_action)).assertExists()
    }

    @Test
    fun `opening the metadata path requests the report and confirms first`() {
        var prepared = false
        render(hasSupportLead = true, onPrepareReport = { prepared = true })

        compose.onNodeWithText(string(R.string.setup_unsupported_request_action)).performClick()

        prepared shouldBe true
        // Confirmation, not a direct hand-off: nothing reaches GitHub until the user sees the exact report.
        compose.onNodeWithText(string(R.string.setup_unsupported_dialog_title)).assertExists()
        compose.onNodeWithText(string(R.string.setup_unsupported_dialog_open)).assertIsEnabled()
    }

    @Test
    fun `the confirmation cannot be accepted until the report snapshot has arrived`() {
        // Collection is async: until it lands, what GitHub would receive isn't the previewed text yet.
        render(hasSupportLead = true, reportPreview = null)

        compose.onNodeWithText(string(R.string.setup_unsupported_request_action)).performClick()

        compose.onNodeWithText(string(R.string.setup_unsupported_dialog_open)).assertIsNotEnabled()
        compose.onNodeWithText(string(R.string.setup_unsupported_dialog_copy)).assertIsNotEnabled()
    }
}
