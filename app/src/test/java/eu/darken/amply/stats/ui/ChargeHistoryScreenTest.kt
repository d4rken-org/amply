package eu.darken.amply.stats.ui

import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollToIndex
import eu.darken.amply.stats.core.ChargeSessionSummary
import eu.darken.amply.stats.core.ChargingType
import eu.darken.amply.stats.core.StatsSealReason
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

/** The list asks for the next page only near its end, and only while more history may exist. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ChargeHistoryScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private val sessions = List(50) { index ->
        ChargeSessionSummary(
            id = index + 1L,
            startedAtWallMillis = 1_000_000L - index * 10_000L,
            endedAtWallMillis = 1_005_000L - index * 10_000L,
            durationMillis = 5_000L,
            startPercent = 40,
            endPercent = 80,
            chargingType = ChargingType.AC,
            avgPowerMilliwatts = 9_000,
            peakPowerMilliwatts = 18_000,
            minTemperatureTenthsC = 300,
            avgTemperatureTenthsC = 310,
            maxTemperatureTenthsC = 330,
            limitHit = false,
            partial = false,
            fullReachedAtWallMillis = null,
            sealReason = StatsSealReason.UNPLUGGED,
        )
    }

    private var loadMoreCalls = 0

    private fun render(hasMore: Boolean) {
        compose.setContent {
            ChargeHistoryScreen(
                state = ChargeHistoryState.Ready(sessions, hasMore = hasMore),
                onBack = {},
                onOpenSession = {},
                onLoadMore = { loadMoreCalls++ },
                onClearData = {},
            )
        }
    }

    @Test
    fun `scrolling to the end of a full window asks for more`() {
        render(hasMore = true)
        compose.waitForIdle()
        loadMoreCalls shouldBe 0

        compose.onNode(hasScrollAction()).performScrollToIndex(sessions.lastIndex)
        compose.waitForIdle()

        loadMoreCalls shouldBeGreaterThan 0
        compose.onNodeWithTag(CHARGE_HISTORY_LOADING_MORE_TEST_TAG).assertExists()
    }

    @Test
    fun `scrolling to the end of exhausted history asks for nothing`() {
        render(hasMore = false)

        compose.onNode(hasScrollAction()).performScrollToIndex(sessions.lastIndex)
        compose.waitForIdle()

        loadMoreCalls shouldBe 0
        compose.onNodeWithTag(CHARGE_HISTORY_LOADING_MORE_TEST_TAG).assertDoesNotExist()
    }
}
