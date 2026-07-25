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
 * A live curve is only worth drawing once the session has both enough points and enough elapsed time
 * to have a shape — before that it is a flat or single-point line that reads as "nothing is
 * happening". Shared by the dashboard's charging card and the hub's teaser so the two can never
 * disagree about when a curve appears.
 */
fun shouldShowLiveCurve(curvePoints: Int, elapsedMillis: Long): Boolean =
    curvePoints >= MIN_CURVE_POINTS && elapsedMillis >= CHART_MIN_ELAPSED_MILLIS

/** Withhold the live curve until the session has a few minutes of points to draw a meaningful shape. */
const val CHART_MIN_ELAPSED_MILLIS = 180_000L

private const val MIN_CURVE_POINTS = 2

/**
 * The shared level / power / temperature charge curve. On the session-detail screen ([showAxes] true) it
 * carries a real left Y-axis in battery-% (nice ticks + gridlines) and a sparse right Y-axis in watts,
 * with end-of-curve value labels; temperature stays self-normalized (shape only, so it can share the plot
 * without a third axis). The dashboard's live card ([showAxes] false) drops both axes and the time labels
 * — elapsed time already shows in the card header — keeping only the end labels for a compact readout.
 */
@Composable
fun StatsCurveChart(
    curve: List<ChargeCurvePoint>,
    modifier: Modifier = Modifier,
    chartHeight: Dp = 180.dp,
    showAxes: Boolean = true,
) {
    val percentColor = MaterialTheme.colorScheme.primary
    val powerColor = MaterialTheme.colorScheme.tertiary
    val tempColor = MaterialTheme.colorScheme.error

    val percents = curve.mapNotNull { it.percent }
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

    val descriptionPairs = listOfNotNull(
        percentEnd?.let { "$percentLabel: $it" },
        powerEnd?.let { "$powerLabel: $it" },
        tempEnd?.let { "$tempLabel: $it" },
    )
    val chartDescription = descriptionPairs.takeIf { it.isNotEmpty() }?.joinToString(", ")

    LineChart(
        modifier = modifier,
        series = listOf(
            ChartSeries(
                label = percentLabel,
                color = percentColor,
                points = curve.map { ChartPoint(it.elapsedFromStartMillis.toFloat(), it.percent?.toFloat()) },
                rangeLabel = StatsFormat.percentSpan(percents.minOrNull(), percents.maxOrNull()),
                endLabel = percentEnd,
                axisSide = if (showAxes) YAxisSide.LEFT else null,
            ),
            ChartSeries(
                label = powerLabel,
                color = powerColor,
                points = curve.map { ChartPoint(it.elapsedFromStartMillis.toFloat(), it.powerMilliwatts?.toFloat()) },
                rangeLabel = StatsFormat.powerSpan(powers.minOrNull(), powers.maxOrNull()),
                endLabel = powerEnd,
                axisSide = if (showAxes) YAxisSide.RIGHT else null,
            ),
            ChartSeries(
                label = tempLabel,
                color = tempColor,
                points = curve.map { ChartPoint(it.elapsedFromStartMillis.toFloat(), it.temperatureTenthsC?.toFloat()) },
                rangeLabel = StatsFormat.temperatureSpan(temps.minOrNull(), temps.maxOrNull()),
                endLabel = tempEnd,
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
    StatsCurveChart(curve = previewCurve(), chartHeight = 84.dp, showAxes = false)
}
