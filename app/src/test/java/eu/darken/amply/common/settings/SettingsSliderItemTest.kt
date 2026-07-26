package eu.darken.amply.common.settings

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

/**
 * ### Known coverage gap — read before adding to this class
 *
 * The central property of [SettingsSliderItem] — *many* drag frames update the label but produce
 * **exactly one** write on release ([androidx.compose.material3.Slider]'s `onValueChangeFinished`) —
 * is **NOT covered here**. Synthesized touch input could not be made to drive the slider in this
 * setup: gesture coordinates are derived from the reported semantics node, and Material3 wraps the
 * slider in `IncreaseHorizontalSemanticsBounds`, which reports a node 10dp wider per side than the
 * area the tap/drag pointer inputs actually occupy. Presses anchored to that node's edges land
 * outside the interactive region, so `onValueChange` never fires and the value never moves.
 *
 * What that leaves: the commit-on-release behavior and its same-value guard are held by **code
 * review**, not by a test. The tests below cover the reachable parts — a value change arriving
 * through the semantics action commits once, a no-op interaction commits nothing, and an external
 * `value` change re-syncs the local drag state and the label. Do not read them as covering the drag
 * property, and do not rename them to imply it.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SettingsSliderItemTest {
    @get:Rule
    val compose = createComposeRule()

    /** Material's [androidx.compose.material3.Slider] is the only node carrying progress semantics here. */
    private fun slider() = compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo))

    private fun sliderReports(value: Int) = SemanticsMatcher("slider reports $value") { node ->
        node.config.getOrNull(SemanticsProperties.ProgressBarRangeInfo)?.current == value.toFloat()
    }

    /**
     * Covers the semantics path only (accessibility / `SetProgress`), which commits on its own —
     * it is NOT a stand-in for the drag-then-release path, see the class doc.
     */
    @Test
    fun `a semantics value change commits once`() {
        val commits = mutableListOf<Int>()
        compose.setContent {
            SettingsSliderItem(
                title = "Keep history for",
                valueLabel = { days -> "$days days" },
                value = MIN_DAYS,
                range = MIN_DAYS..MAX_DAYS,
                onValueChange = { commits += it },
            )
        }

        compose.onNodeWithText("$MIN_DAYS days").assertExists()

        slider().performSemanticsAction(SemanticsActions.SetProgress) { it(MAX_DAYS.toFloat()) }

        compose.runOnIdle { commits shouldContainExactly listOf(MAX_DAYS) }
        // The local drag state and its label followed the write.
        compose.onNodeWithText("$MAX_DAYS days").assertExists()
        slider().assert(sliderReports(MAX_DAYS))
    }

    @Test
    fun `a release without a value change commits nothing`() {
        val commits = mutableListOf<Int>()
        compose.setContent {
            SettingsSliderItem(
                title = "Keep history for",
                valueLabel = { days -> "$days days" },
                value = MIN_DAYS,
                range = MIN_DAYS..MAX_DAYS,
                onValueChange = { commits += it },
            )
        }

        // Press and release at the reported node's left edge, where the thumb already sits. Note
        // this may not reach the slider's pointer input at all (see the class doc), so the second
        // interaction carries the weight: a semantics change to the value it already has is
        // rejected by the slider and must not reach onValueChange either.
        slider().performTouchInput { click(centerLeft) }
        slider().performSemanticsAction(SemanticsActions.SetProgress) { it(MIN_DAYS.toFloat()) }

        compose.runOnIdle { commits.shouldBeEmpty() }
        compose.onNodeWithText("$MIN_DAYS days").assertExists()
        slider().assert(sliderReports(MIN_DAYS))
    }

    @Test
    fun `an external value change re-syncs slider and label`() {
        var days by mutableStateOf(MIN_DAYS)
        compose.setContent {
            SettingsSliderItem(
                title = "Keep history for",
                valueLabel = { value -> "$value days" },
                value = days,
                range = MIN_DAYS..MAX_DAYS,
                onValueChange = {},
            )
        }

        compose.onNodeWithText("$MIN_DAYS days").assertExists()
        slider().assert(sliderReports(MIN_DAYS))

        compose.runOnIdle { days = MAX_DAYS }

        compose.onNodeWithText("$MIN_DAYS days").assertDoesNotExist()
        compose.onNodeWithText("$MAX_DAYS days").assertExists()
        slider().assert(sliderReports(MAX_DAYS))
    }

    companion object {
        private const val MIN_DAYS = 3
        private const val MAX_DAYS = 14
    }
}
