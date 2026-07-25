package eu.darken.amply.main.ui.battery

import android.app.Application
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import eu.darken.amply.R
import eu.darken.amply.stats.core.ChargeCurvePoint
import eu.darken.amply.stats.core.StatsLiveSession
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

/**
 * The hub teaser shares the charging card's curve gate and elapsed clock, so the wiring is asserted on
 * both surfaces — a shared helper that only one caller exercises is a shared helper in name only.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ChargeTeaserTest {
    @get:Rule
    val compose = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<Application>()

    private fun string(res: Int): String = context.getString(res)

    private val session = StatsLiveSession(
        id = 3,
        startedAtWallMillis = 0L,
        startedElapsedRealtimeMillis = 0L,
        startPercent = 40,
        partial = false,
        curve = emptyList(),
    )

    private fun render(session: StatsLiveSession, currentPercent: Int? = 78) {
        compose.setContent {
            ChargeTeaser(
                state = ChargeTeaserState.Live(session),
                onOpenSession = {},
                currentPercent = currentPercent,
                nowElapsedRealtimeMillis = 3_600_000L,
            )
        }
    }

    @Test
    fun `the legend defers its range to the headline instead of repeating it`() {
        // The plotted window covers 60..66 while the session runs 40 → 78. The headline owns the range
        // here, so the legend must state neither: not the headline's copy, and not the window's span —
        // the latter is what an omitted (rather than explicitly null) range would fall back to.
        val curve = (0..6).map { i ->
            ChargeCurvePoint(
                elapsedFromStartMillis = 3_000_000L + i * 60_000L,
                percent = 60 + i,
                powerMilliwatts = 12_000 - i * 500,
                temperatureTenthsC = 300 + i,
            )
        }
        render(session.copy(curve = curve))
        val level = string(R.string.stats_curve_series_percent)

        compose.onNodeWithText("40% → 78%").assertExists()
        compose.onNodeWithText(level).assertExists()
        compose.onNodeWithText("$level  40→78%").assertDoesNotExist()
        compose.onNodeWithText("$level  60→66%").assertDoesNotExist()
    }

    @Test
    fun `a flat curve draws nothing`() {
        val flat = (0..6).map { i ->
            ChargeCurvePoint(
                elapsedFromStartMillis = i * 60_000L,
                percent = 80,
                powerMilliwatts = 12_000,
                temperatureTenthsC = 300,
            )
        }
        render(session.copy(startPercent = 80, curve = flat), currentPercent = 80)

        compose.onNodeWithText(string(R.string.stats_curve_series_percent), substring = true)
            .assertDoesNotExist()
    }
}
