package eu.darken.amply.stats.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import eu.darken.amply.R
import eu.darken.amply.common.compose.AmplyPreview
import eu.darken.amply.common.compose.PreviewWrapper
import eu.darken.amply.common.compose.chart.ChartAxis
import eu.darken.amply.common.compose.chart.ChartPoint
import eu.darken.amply.common.compose.chart.ChartSeries
import eu.darken.amply.common.compose.chart.LineChart
import eu.darken.amply.common.compose.chart.YAxisSide
import eu.darken.amply.stats.core.ChargeCurvePoint
import kotlin.math.roundToInt

/**
 * A live curve is only worth drawing once the session has both enough elapsed time and actual
 * variation to show. Without the variation check a session held at an OEM limit draws flat lines —
 * and because the compact chart self-normalizes each series, a series with no range renders at the
 * canvas midpoint, so those flat lines stack into what looks like a plotted trend but is really the
 * "no range" fallback.
 *
 * Variation must come from an **adjacent** pair of non-null samples in one series, not merely two
 * distinct values anywhere in it: `LineChart` breaks its path at nulls, so `[40, null, 41]` becomes
 * two single-point segments and draws no line at all. (A series whose *only* non-null sample is a
 * single point does render, as a dot — but a lone dot is not a curve either.)
 *
 * Shared by the dashboard's charging card and the hub's teaser so the two can never disagree about
 * when a curve appears.
 */
fun shouldShowLiveCurve(curve: List<ChargeCurvePoint>, elapsedMillis: Long): Boolean =
    elapsedMillis >= CHART_MIN_ELAPSED_MILLIS && curve.hasDrawableVariation()

private fun List<ChargeCurvePoint>.hasDrawableVariation(): Boolean =
    hasVariation { it.percent } ||
        hasVariation { it.powerMilliwatts } ||
        hasVariation { it.temperatureTenthsC }

private fun List<ChargeCurvePoint>.hasVariation(select: (ChargeCurvePoint) -> Int?): Boolean =
    zipWithNext().any { (first, second) ->
        val a = select(first)
        val b = select(second)
        a != null && b != null && a != b
    }

/** Withhold the live curve until the session has a few minutes of points to draw a meaningful shape. */
const val CHART_MIN_ELAPSED_MILLIS = 180_000L

/**
 * The shared level / power / temperature charge curve. On the session-detail screen ([showAxes] true) it
 * carries a real left Y-axis in battery-% (nice ticks + gridlines) and a sparse right Y-axis in watts,
 * with end-of-curve value labels; temperature stays self-normalized (shape only, so it can share the plot
 * without a third axis).
 *
 * The compact live variant ([showAxes] false) drops the axes, the time labels **and** the end-of-curve
 * labels: its hosts already render the current level, power and temperature above the chart, and the end
 * labels come from the last recorded sample, which lags that reading by a recorder tick — so they read as
 * a contradiction rather than a readout. Dropping them also frees the right gutter, letting the curve use
 * the full width. The chart stays described for accessibility either way.
 *
 * [percentRangeLabel] is the level series' legend range. It **defaults to the plotted curve's own span**,
 * which is what a full session curve wants; a live host whose curve is a bounded recent window passes its
 * *session* range instead, and passes `null` when it has none — an explicitly absent range shows no label
 * rather than silently falling back to the window's span, which would make one legend entry mean two
 * different things depending on data availability.
 */
@Composable
fun StatsCurveChart(
    curve: List<ChargeCurvePoint>,
    modifier: Modifier = Modifier,
    chartHeight: Dp = 180.dp,
    showAxes: Boolean = true,
    percentRangeLabel: String? = curvePercentSpan(curve),
) {
    val percentColor = MaterialTheme.colorScheme.primary
    val powerColor = MaterialTheme.colorScheme.tertiary
    val tempColor = MaterialTheme.colorScheme.error

    val powers = curve.mapNotNull { it.powerMilliwatts }
    val temps = curve.mapNotNull { it.temperatureTenthsC }

    val lastPercent = curve.lastOrNull { it.percent != null }?.percent
    val lastPower = curve.lastOrNull { it.powerMilliwatts != null }?.powerMilliwatts
    val lastTemp = curve.lastOrNull { it.temperatureTenthsC != null }?.temperatureTenthsC

    val percentLabel = stringResource(R.string.stats_curve_series_percent)
    val powerLabel = stringResource(R.string.stats_curve_series_power)
    // Axes mode marks temperature as shape-only (it has no axis); the compact card names it plainly.
    val tempLabel = stringResource(
        if (showAxes) R.string.stats_curve_series_temperature_shape_only else R.string.stats_curve_series_temperature,
    )

    val percentEnd = lastPercent?.let { "$it%" }
    val powerEnd = StatsFormat.power(lastPower)
    val tempEnd = StatsFormat.temperature(lastTemp)

    val leftAxis = if (showAxes) {
        ChartAxis(
            formatter = { "${it.roundToInt()}%" },
            tickTarget = 4,
            bounds = 0f..100f,
            minStep = 1f,
        )
    } else {
        null
    }
    val rightAxis = if (showAxes) {
        ChartAxis(
            formatter = { StatsFormat.power(it.roundToInt())!! },
            tickTarget = 4,
            maxLabels = 2,
            bounds = 0f..250_000f,
            minStep = 100f,
        )
    } else {
        null
    }

    // Only the axes variant describes its endpoints. Dropping the compact end labels but still
    // announcing those same lagging values would move the contradiction from the screen into the
    // screen reader; the compact chart's legend is real text, so it is already read aloud.
    val chartDescription = if (showAxes) {
        listOfNotNull(
            percentEnd?.let { "$percentLabel: $it" },
            powerEnd?.let { "$powerLabel: $it" },
            tempEnd?.let { "$tempLabel: $it" },
        ).takeIf { it.isNotEmpty() }?.joinToString(", ")
    } else {
        null
    }

    LineChart(
        modifier = modifier,
        series = listOf(
            ChartSeries(
                label = percentLabel,
                color = percentColor,
                points = curve.map { ChartPoint(it.elapsedFromStartMillis.toFloat(), it.percent?.toFloat()) },
                rangeLabel = percentRangeLabel,
                endLabel = if (showAxes) percentEnd else null,
                axisSide = if (showAxes) YAxisSide.LEFT else null,
            ),
            ChartSeries(
                label = powerLabel,
                color = powerColor,
                points = curve.map { ChartPoint(it.elapsedFromStartMillis.toFloat(), it.powerMilliwatts?.toFloat()) },
                rangeLabel = StatsFormat.powerSpan(powers.minOrNull(), powers.maxOrNull()),
                endLabel = if (showAxes) powerEnd else null,
                axisSide = if (showAxes) YAxisSide.RIGHT else null,
            ),
            ChartSeries(
                label = tempLabel,
                color = tempColor,
                points = curve.map { ChartPoint(it.elapsedFromStartMillis.toFloat(), it.temperatureTenthsC?.toFloat()) },
                rangeLabel = StatsFormat.temperatureSpan(temps.minOrNull(), temps.maxOrNull()),
                endLabel = if (showAxes) tempEnd else null,
            ),
        ),
        emptyLabel = stringResource(R.string.stats_curve_empty),
        chartHeight = chartHeight,
        xAxisFormatter = if (showAxes) {
            { millis: Float -> StatsFormat.elapsedAxis(millis.toLong()) }
        } else {
            null
        },
        xAxisContentDescription = if (showAxes) stringResource(R.string.stats_curve_axis_time_desc) else null,
        leftAxis = leftAxis,
        rightAxis = rightAxis,
        chartContentDescription = chartDescription,
    )
}

/** The level span of the plotted samples — the default legend range when a host supplies none. */
private fun curvePercentSpan(curve: List<ChargeCurvePoint>): String? {
    val percents = curve.mapNotNull { it.percent }
    return StatsFormat.percentSpan(percents.minOrNull(), percents.maxOrNull())
}

private fun previewCurve() = (0..12).map { i ->
    ChargeCurvePoint(
        elapsedFromStartMillis = i * 300_000L,
        percent = (42 + i * 4).coerceAtMost(100),
        powerMilliwatts = (18_000 - i * 1_100).coerceAtLeast(3_000),
        temperatureTenthsC = 300 + i,
    )
}

@AmplyPreview
@Composable
private fun StatsCurveChartAxesPreview() = PreviewWrapper {
    StatsCurveChart(curve = previewCurve(), showAxes = true)
}

@AmplyPreview
@Composable
private fun StatsCurveChartCompactPreview() = PreviewWrapper {
    // As a live host renders it: no axes, no end labels, and a session range the bounded window can't
    // derive on its own (the curve starts at 42%, the session at 12%).
    StatsCurveChart(
        curve = previewCurve(),
        chartHeight = 84.dp,
        showAxes = false,
        percentRangeLabel = StatsFormat.percentSpan(12, 90),
    )
}
