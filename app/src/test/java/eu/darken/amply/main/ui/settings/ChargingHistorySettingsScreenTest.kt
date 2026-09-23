package eu.darken.amply.main.ui.settings

import android.app.Application
import android.text.format.Formatter
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.core.app.ApplicationProvider
import eu.darken.amply.R
import eu.darken.amply.stats.core.StatsRetention
import io.kotest.matchers.collections.shouldContainExactly
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

/**
 * The slider is driven through `SetProgress`, which commits in the same step, so an in-progress,
 * uncommitted drag cannot be held here. The label and footer are instead checked after a commit the
 * screen does not feed back into `retentionDays`: whatever they show then comes from the slider's
 * own position, not from the persisted value.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ChargingHistorySettingsScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<Application>()

    private val finiteFooter get() = context.getString(R.string.stats_retention_footer)
    private val foreverFooter get() = context.getString(R.string.stats_retention_footer_forever)
    private val foreverLabel get() = context.getString(R.string.stats_retention_forever)
    private fun daysLabel(days: Int) = context.getString(R.string.stats_retention_value, days)

    private fun slider() = compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo))

    private val storageTitle get() = context.getString(R.string.stats_storage_title)

    private fun render(
        retentionDays: Int,
        commits: MutableList<Int> = mutableListOf(),
        storageBytes: Long? = null,
    ) {
        compose.setContent {
            ChargingHistorySettingsScreen(
                captureEnabled = true,
                retentionDays = retentionDays,
                storageBytes = storageBytes,
                onBack = {},
                onCaptureEnabledChange = {},
                onRetentionChange = { commits += it },
            )
        }
    }

    @Test
    fun `a finite preset shows its days and the deletion footer`() {
        render(retentionDays = 14)

        compose.onNodeWithText(daysLabel(14)).assertExists()
        compose.onNodeWithText(finiteFooter).assertExists()
        compose.onNodeWithText(foreverFooter).assertDoesNotExist()
    }

    @Test
    fun `forever shows the forever label and footer`() {
        render(retentionDays = StatsRetention.FOREVER)

        compose.onNodeWithText(foreverLabel).assertExists()
        compose.onNodeWithText(foreverFooter).assertExists()
        compose.onNodeWithText(finiteFooter).assertDoesNotExist()
    }

    @Test
    fun `a value between presets renders at the next preset up`() {
        render(retentionDays = 10)

        compose.onNodeWithText(daysLabel(14)).assertExists()
        compose.onNodeWithText(finiteFooter).assertExists()
    }

    @Test
    fun `moving to the last stop commits forever and switches label and footer`() {
        val commits = mutableListOf<Int>()
        render(retentionDays = 14, commits = commits)

        slider().performSemanticsAction(SemanticsActions.SetProgress) {
            it(StatsRetention.PRESETS.lastIndex.toFloat())
        }

        compose.runOnIdle { commits shouldContainExactly listOf(StatsRetention.FOREVER) }
        compose.onNodeWithText(foreverLabel).assertExists()
        compose.onNodeWithText(foreverFooter).assertExists()
        compose.onNodeWithText(finiteFooter).assertDoesNotExist()
    }

    @Test
    fun `moving from forever to the 30 day stop commits 30 and switches label and footer`() {
        val commits = mutableListOf<Int>()
        render(retentionDays = StatsRetention.FOREVER, commits = commits)

        slider().performSemanticsAction(SemanticsActions.SetProgress) {
            it(StatsRetention.PRESETS.indexOf(30).toFloat())
        }

        compose.runOnIdle { commits shouldContainExactly listOf(30) }
        compose.onNodeWithText(daysLabel(30)).assertExists()
        compose.onNodeWithText(finiteFooter).assertExists()
        compose.onNodeWithText(foreverFooter).assertDoesNotExist()
    }

    @Test
    fun `no storage size hides the storage row`() {
        render(retentionDays = 14, storageBytes = null)

        compose.onNodeWithText(storageTitle).assertDoesNotExist()
    }

    @Test
    fun `a storage size shows the formatted value in a row that is not clickable`() {
        val bytes = 12_345_678L
        render(retentionDays = 14, storageBytes = bytes)

        compose.onNodeWithText(storageTitle).assertExists().assertHasNoClickAction()
        compose.onNodeWithText(Formatter.formatShortFileSize(context, bytes)).assertExists()
    }
}
