package eu.darken.amply.main.ui.battery

import android.app.Application
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import eu.darken.amply.R
import eu.darken.amply.stats.core.ChargeCurvePoint
import eu.darken.amply.stats.core.MetricStats
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The per-metric detail screen. The statistics row is the load-bearing part: it renders the values
 * the repository computed from the raw samples, never anything re-derived from the plotted curve.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "+h2400dp")
class BatteryMetricDetailScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<Application>()

    private fun string(res: Int): String = context.getString(res)

    private val curve = (0..8).map { i ->
        ChargeCurvePoint(
            elapsedFromStartMillis = i * 300_000L,
            percent = 60 + i * 2,
            powerMilliwatts = 12_000 - i * 500,
            temperatureTenthsC = 300 + i,
            voltageMillivolts = 4_000 + i * 10,
            currentNowMicroamps = 2_000_000 - i * 100_000,
        )
    }

    private fun render(state: BatteryMetricDetailState?) {
        compose.setContent { BatteryMetricDetailScreen(state = state, onBack = {}) }
    }

    @Test
    fun `the title, the latest value and the statistics all render`() {
        render(
            BatteryMetricDetailState(
                metric = BatteryMetric.LEVEL,
                sessionMissing = false,
                curve = curve,
                // Deliberately wider than the plotted curve: these come from the raw samples, and a
                // screen that recomputed them from the curve would print 60/68/76 instead.
                stats = MetricStats(min = 41, avg = 68, max = 93, sampleCount = 120),
            ),
        )
        compose.onAllNodesWithText(string(R.string.battery_metric_level_title)).onFirst().assertExists()
        // The last recorded point of the curve, as the headline.
        compose.onNodeWithText("76%").assertExists()
        compose.onNodeWithText("41%").assertExists()
        compose.onNodeWithText("68%").assertExists()
        compose.onNodeWithText("93%").assertExists()
        compose.onNodeWithText(string(R.string.battery_metric_min).uppercase()).assertExists()
        compose.onNodeWithText(string(R.string.battery_metric_max).uppercase()).assertExists()
    }

    @Test
    fun `temperature renders in its own unit`() {
        render(
            BatteryMetricDetailState(
                metric = BatteryMetric.TEMPERATURE,
                sessionMissing = false,
                curve = curve,
                stats = MetricStats(min = 298, avg = 305, max = 331, sampleCount = 120),
            ),
        )
        compose.onNodeWithText("29.8 °C").assertExists()
        compose.onNodeWithText("33.1 °C").assertExists()
    }

    @Test
    fun `an empty curve shows the chart's own empty label and no statistics`() {
        render(
            BatteryMetricDetailState(
                metric = BatteryMetric.POWER,
                sessionMissing = false,
                curve = emptyList(),
                stats = null,
            ),
        )
        compose.onNodeWithText(string(R.string.stats_curve_empty)).assertExists()
        // Absent statistics hide the row rather than printing zeros.
        compose.onAllNodesWithText(string(R.string.battery_metric_avg).uppercase())
            .assertCountEquals(0)
        // The explainer is still there — it does not depend on any recorded data.
        compose.onNodeWithText(string(R.string.battery_metric_power_explainer)).assertExists()
    }

    @Test
    fun `a session that no longer resolves shows the missing notice`() {
        render(
            BatteryMetricDetailState(
                metric = BatteryMetric.LEVEL,
                sessionMissing = true,
                curve = emptyList(),
                stats = null,
            ),
        )
        compose.onNodeWithText(string(R.string.stats_detail_missing)).assertExists()
        compose.onAllNodesWithText(string(R.string.battery_metric_level_explainer))
            .assertCountEquals(0)
    }

    @Test
    fun `an unresolved selection shows a spinner, not an empty screen`() {
        render(null)
        compose.onAllNodesWithText(string(R.string.stats_detail_missing)).assertCountEquals(0)
        compose.onAllNodesWithText(string(R.string.stats_curve_empty)).assertCountEquals(0)
    }
}
