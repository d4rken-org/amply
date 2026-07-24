// Chart screenshot content. These composables render the dual-axis LineChart from crafted fixtures so
// the screenshotTest source set can capture them to PNGs on the JVM (no device). They live in the debug
// source set so they never ship in a release build, and each has an IDE @Preview for quick iteration.
// They render into their own ChartScreenshotsKt/ reference dir and share nothing with the Play Store flow.
package eu.darken.amply.screenshots

import android.content.res.Configuration
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import eu.darken.amply.common.compose.PreviewWrapper
import eu.darken.amply.common.compose.chart.ChartAxis
import eu.darken.amply.common.compose.chart.ChartPoint
import eu.darken.amply.common.compose.chart.ChartSeries
import eu.darken.amply.common.compose.chart.LineChart
import eu.darken.amply.common.compose.chart.YAxisSide
import kotlin.math.roundToInt

// -- Content composables (one per screenshot) --------------------------------------------------

// Three monotonically rising series so all three end labels cluster near the top and must be
// collision-separated.
@Composable
internal fun ChartCollidingEndsContent() = PreviewWrapper {
    val n = 9
    DualAxisChart(
        percent = ramp(n, 60f, 92f),
        power = ramp(n, 4_000f, 12_000f),
        temp = ramp(n, 300f, 360f),
    )
}

// Constant 100% level: the left axis must not produce a tick above 100.
@Composable
internal fun ChartConstant100Content() = PreviewWrapper {
    val n = 9
    DualAxisChart(
        percent = List(n) { 100f },
        power = ramp(n, 12_000f, 3_000f),
        temp = ramp(n, 300f, 340f),
    )
}

// Power series entirely absent: no right axis / no power end label, the rest unaffected.
@Composable
internal fun ChartPowerAllNullContent() = PreviewWrapper {
    val n = 9
    DualAxisChart(
        percent = ramp(n, 40f, 90f),
        power = List(n) { null },
        temp = ramp(n, 300f, 340f),
    )
}

// Power drops out for the last samples: its leader must anchor to the true last point, not the plot edge.
@Composable
internal fun ChartPowerTrailingNullsContent() = PreviewWrapper {
    val n = 9
    val power = ramp(n, 15_000f, 6_000f).toMutableList()
    power[n - 1] = null
    power[n - 2] = null
    DualAxisChart(
        percent = ramp(n, 40f, 90f),
        power = power,
        temp = ramp(n, 300f, 340f),
    )
}

// Narrow width: the degradation ladder should keep the plot at least 96dp wide.
@Composable
internal fun ChartNarrowContent() = PreviewWrapper {
    Box(Modifier.width(320.dp)) {
        DefaultDualAxisChart()
    }
}

// Large font scale: gutters and end labels must grow with the measured text.
@Composable
internal fun ChartFontScaleContent() = PreviewWrapper {
    val base = LocalDensity.current
    CompositionLocalProvider(LocalDensity provides Density(base.density, fontScale = 2f)) {
        DefaultDualAxisChart()
    }
}

// RTL locale: the chart draws LTR (physical) and the x-label row uses absolute padding, so both stay
// aligned under the plot.
@Composable
internal fun ChartRtlContent() = PreviewWrapper {
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
        DefaultDualAxisChart()
    }
}

// Compact, axis-less variant (dashboard live card): end labels only, no axes, no time labels.
@Composable
internal fun ChartCompactContent() = PreviewWrapper {
    val n = 9
    val xs = xAxis(n)
    LineChart(
        series = listOf(
            ChartSeries(
                label = "Level",
                color = MaterialTheme.colorScheme.primary,
                points = points(xs, ramp(n, 55f, 87f)),
                endLabel = "87%",
            ),
            ChartSeries(
                label = "Power",
                color = MaterialTheme.colorScheme.tertiary,
                points = points(xs, ramp(n, 15_000f, 7_800f)),
                endLabel = "7.8 W",
            ),
        ),
        emptyLabel = "No curve data",
        chartHeight = 84.dp,
    )
}

// -- Shared renderer + fixture helpers ---------------------------------------------------------

@Composable
private fun DefaultDualAxisChart() = DualAxisChart(
    percent = ramp(9, 40f, 90f),
    power = ramp(9, 18_000f, 6_000f),
    temp = ramp(9, 300f, 320f),
)

@Composable
private fun DualAxisChart(
    percent: List<Float?>,
    power: List<Float?>,
    temp: List<Float?>,
    modifier: Modifier = Modifier,
) {
    val n = maxOf(percent.size, power.size, temp.size)
    val xs = xAxis(n)
    LineChart(
        modifier = modifier,
        series = listOf(
            ChartSeries(
                label = "Level",
                color = MaterialTheme.colorScheme.primary,
                points = points(xs, percent),
                endLabel = percent.lastOrNull { it != null }?.let { "${it.roundToInt()}%" },
                axisSide = YAxisSide.LEFT,
            ),
            ChartSeries(
                label = "Power",
                color = MaterialTheme.colorScheme.tertiary,
                points = points(xs, power),
                endLabel = power.lastOrNull { it != null }?.let { "%.1f W".format(it / 1_000f) },
                axisSide = YAxisSide.RIGHT,
            ),
            ChartSeries(
                label = "Temperature (shape only)",
                color = MaterialTheme.colorScheme.error,
                points = points(xs, temp),
                endLabel = temp.lastOrNull { it != null }?.let { "%.1f °C".format(it / 10f) },
            ),
        ),
        emptyLabel = "No curve data",
        leftAxis = ChartAxis(formatter = { "${it.roundToInt()}%" }, tickTarget = 4, bounds = 0f..100f, minStep = 1f),
        rightAxis = ChartAxis(
            formatter = { "%.1f W".format(it / 1_000f) },
            tickTarget = 4,
            maxLabels = 2,
            bounds = 0f..250_000f,
            minStep = 100f,
        ),
        xAxisFormatter = { "${(it / 60_000f).roundToInt()}m" },
    )
}

private fun xAxis(n: Int) = (0 until n).map { it * 300_000f }

private fun points(xs: List<Float>, values: List<Float?>) =
    xs.mapIndexed { i, x -> ChartPoint(x, values.getOrNull(i)) }

/** Linear ramp from [from] to [to] across [n] samples (a monotonic fixture curve). */
private fun ramp(n: Int, from: Float, to: Float): List<Float?> =
    (0 until n).map { i -> from + (to - from) * i / (n - 1) }

// -- IDE previews (design-time only; the screenshotTest wrappers drive the actual capture) ------

@Preview(name = "Colliding ends", showBackground = true)
@Composable
private fun PreviewChartCollidingEnds() = ChartCollidingEndsContent()

@Preview(name = "Constant 100%", showBackground = true)
@Composable
private fun PreviewChartConstant100() = ChartConstant100Content()

@Preview(name = "Power all null", showBackground = true)
@Composable
private fun PreviewChartPowerAllNull() = ChartPowerAllNullContent()

@Preview(name = "Power trailing nulls", showBackground = true)
@Composable
private fun PreviewChartPowerTrailingNulls() = ChartPowerTrailingNullsContent()

@Preview(name = "Narrow 320dp", showBackground = true)
@Composable
private fun PreviewChartNarrow() = ChartNarrowContent()

@Preview(name = "Font scale 2x", showBackground = true)
@Composable
private fun PreviewChartFontScale() = ChartFontScaleContent()

@Preview(name = "RTL", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun PreviewChartRtl() = ChartRtlContent()

@Preview(name = "Compact axis-less", showBackground = true)
@Composable
private fun PreviewChartCompact() = ChartCompactContent()
