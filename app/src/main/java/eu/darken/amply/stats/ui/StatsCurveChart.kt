package eu.darken.amply.stats.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import eu.darken.amply.R
import eu.darken.amply.common.compose.chart.ChartPoint
import eu.darken.amply.common.compose.chart.ChartSeries
import eu.darken.amply.common.compose.chart.LineChart
import eu.darken.amply.stats.core.ChargeCurvePoint

/**
 * The shared level / power / temperature charge curve, with per-series value ranges in the legend and
 * an elapsed-time x axis. Used by both the session-detail screen (full curve) and the dashboard's live
 * card (compact, bounded window) so both present units the same way.
 */
@Composable
fun StatsCurveChart(
    curve: List<ChargeCurvePoint>,
    modifier: Modifier = Modifier,
    chartHeight: Dp = 180.dp,
) {
    val percentColor = MaterialTheme.colorScheme.primary
    val powerColor = MaterialTheme.colorScheme.tertiary
    val tempColor = MaterialTheme.colorScheme.error

    val percents = curve.mapNotNull { it.percent }
    val powers = curve.mapNotNull { it.powerMilliwatts }
    val temps = curve.mapNotNull { it.temperatureTenthsC }

    LineChart(
        modifier = modifier,
        series = listOf(
            ChartSeries(
                label = stringResource(R.string.stats_curve_series_percent),
                color = percentColor,
                points = curve.map { ChartPoint(it.elapsedFromStartMillis.toFloat(), it.percent?.toFloat()) },
                rangeLabel = StatsFormat.percentSpan(percents.minOrNull(), percents.maxOrNull()),
            ),
            ChartSeries(
                label = stringResource(R.string.stats_curve_series_power),
                color = powerColor,
                points = curve.map { ChartPoint(it.elapsedFromStartMillis.toFloat(), it.powerMilliwatts?.toFloat()) },
                rangeLabel = StatsFormat.powerSpan(powers.minOrNull(), powers.maxOrNull()),
            ),
            ChartSeries(
                label = stringResource(R.string.stats_curve_series_temperature),
                color = tempColor,
                points = curve.map { ChartPoint(it.elapsedFromStartMillis.toFloat(), it.temperatureTenthsC?.toFloat()) },
                rangeLabel = StatsFormat.temperatureSpan(temps.minOrNull(), temps.maxOrNull()),
            ),
        ),
        emptyLabel = stringResource(R.string.stats_curve_empty),
        chartHeight = chartHeight,
        xAxisFormatter = { StatsFormat.elapsedAxis(it.toLong()) },
        xAxisContentDescription = stringResource(R.string.stats_curve_axis_time_desc),
    )
}
