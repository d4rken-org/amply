package eu.darken.amply.main.ui.battery

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import eu.darken.amply.R
import eu.darken.amply.common.compose.AmplyCard
import eu.darken.amply.common.compose.AmplyCardDefaults
import eu.darken.amply.common.compose.AmplyPreview
import eu.darken.amply.common.compose.PreviewWrapper
import eu.darken.amply.common.compose.chart.ChartAxis
import eu.darken.amply.common.compose.chart.ChartPoint
import eu.darken.amply.common.compose.chart.ChartSeries
import eu.darken.amply.common.compose.chart.LineChart
import eu.darken.amply.common.compose.chart.YAxisSide
import eu.darken.amply.stats.core.ChargeCurvePoint
import eu.darken.amply.stats.core.MetricStats
import eu.darken.amply.stats.ui.StatsFormat

/**
 * One metric of one charge session: its latest recorded value, its curve, its real min / average /
 * max, and a plain-language explainer.
 *
 * [stats] never comes from [curve] — the curve is decimated for plotting, so a brief extreme is
 * simply not in it, and a "Maximum" taken from the plotted points would not be the session's. The
 * repository computes both from the same raw samples.
 *
 * A null [state] is "the selection is still resolving" (a spinner), [BatteryMetricDetailState.sessionMissing]
 * is "that session is gone" (retention, or cleared data) and gets a notice rather than an eternal
 * spinner, and a null [stats] hides the statistics row rather than printing zeros.
 */
data class BatteryMetricDetailState(
    val metric: BatteryMetric,
    val sessionMissing: Boolean,
    val curve: List<ChargeCurvePoint>,
    val stats: MetricStats?,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BatteryMetricDetailScreen(
    state: BatteryMetricDetailState?,
    onBack: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        state?.metric?.titleRes?.let { stringResource(it) }
                            ?: stringResource(R.string.stats_detail_title),
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.action_back),
                        )
                    }
                },
            )
        },
    ) { padding ->
        if (state == null) {
            Centered(padding) { CircularProgressIndicator() }
            return@Scaffold
        }
        if (state.sessionMissing) {
            Centered(padding) {
                Text(
                    stringResource(R.string.stats_detail_missing),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            return@Scaffold
        }

        LazyColumn(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { CurveCard(state) }
            if (state.stats != null) {
                item { StatsCard(state.metric, state.stats) }
            }
            item { ExplainerCard(state.metric) }
        }
    }
}

@Composable
private fun Centered(padding: PaddingValues, content: @Composable () -> Unit) = Box(
    Modifier
        .padding(padding)
        .fillMaxSize(),
    contentAlignment = Alignment.Center,
) { content() }

@Composable
private fun CurveCard(state: BatteryMetricDetailState) {
    val metric = state.metric
    val color = MaterialTheme.colorScheme.primary
    val label = stringResource(metric.titleRes)
    val latest = state.curve.lastOrNull { metric.select(it) != null }?.let { metric.select(it) }

    AmplyCard(verticalArrangement = Arrangement.spacedBy(AmplyCardDefaults.ItemSpacing)) {
        Text(
            metric.format(latest) ?: stringResource(R.string.battery_value_not_reported),
            style = MaterialTheme.typography.headlineMedium,
        )
        LineChart(
            // One series, so it owns the left axis outright — StatsCurveChart is hard-wired to the
            // three-series charge curve and can't render a single metric on its own scale.
            series = listOf(
                ChartSeries(
                    label = label,
                    color = color,
                    points = state.curve.map {
                        ChartPoint(it.elapsedFromStartMillis.toFloat(), metric.select(it)?.toFloat())
                    },
                    endLabel = metric.format(latest),
                    axisSide = YAxisSide.LEFT,
                ),
            ),
            emptyLabel = stringResource(R.string.stats_curve_empty),
            xAxisFormatter = { millis -> StatsFormat.elapsedAxis(millis.toLong()) },
            xAxisContentDescription = stringResource(R.string.stats_curve_axis_time_desc),
            leftAxis = metric.chartAxis(),
            chartContentDescription = metric.format(latest)?.let { "$label: $it" },
        )
    }
}

/**
 * The metric's Y-axis. Bounds exist only where the quantity is physically bounded; [ChartAxis.minStep]
 * is the display resolution of the metric's own formatter, so two ticks can never format identically.
 */
private fun BatteryMetric.chartAxis(): ChartAxis = when (this) {
    BatteryMetric.LEVEL -> ChartAxis(formatter = ::formatAxis, bounds = 0f..100f, minStep = 1f)
    // Same ceiling the charge-power calculator treats as implausible.
    BatteryMetric.POWER -> ChartAxis(formatter = ::formatAxis, bounds = 0f..250_000f, minStep = 100f)
    // 10 mV: the voltage formatter shows two decimals of a volt.
    BatteryMetric.VOLTAGE -> ChartAxis(formatter = ::formatAxis, minStep = 10f)
    // 1 mA: the current formatter rounds to whole milliamps.
    BatteryMetric.CURRENT -> ChartAxis(formatter = ::formatAxis, minStep = 1_000f)
    // A tenth of a degree, the unit temperature is stored and formatted in.
    BatteryMetric.TEMPERATURE -> ChartAxis(formatter = ::formatAxis, minStep = 1f)
}

@Composable
private fun StatsCard(metric: BatteryMetric, stats: MetricStats) {
    AmplyCard(verticalArrangement = Arrangement.spacedBy(AmplyCardDefaults.ItemSpacing)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            StatColumn(stringResource(R.string.battery_metric_min), metric.format(stats.min))
            StatColumn(stringResource(R.string.battery_metric_avg), metric.format(stats.avg))
            StatColumn(stringResource(R.string.battery_metric_max), metric.format(stats.max))
        }
    }
}

@Composable
private fun StatColumn(label: String, value: String?) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            label.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            value ?: stringResource(R.string.battery_value_not_reported),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Medium,
        )
    }
}

@Composable
private fun ExplainerCard(metric: BatteryMetric) {
    AmplyCard(verticalArrangement = Arrangement.spacedBy(AmplyCardDefaults.ItemSpacing)) {
        Text(
            stringResource(metric.titleRes),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(
            stringResource(metric.explainerRes),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun previewState(
    metric: BatteryMetric = BatteryMetric.LEVEL,
    curve: List<ChargeCurvePoint> = previewMetricCurve,
    stats: MetricStats? = MetricStats(min = 42, avg = 68, max = 90, sampleCount = 13),
    sessionMissing: Boolean = false,
) = BatteryMetricDetailState(metric = metric, sessionMissing = sessionMissing, curve = curve, stats = stats)

private val previewMetricCurve = (0..12).map { i ->
    ChargeCurvePoint(
        elapsedFromStartMillis = i * 300_000L,
        percent = (42 + i * 4).coerceAtMost(100),
        powerMilliwatts = (18_000 - i * 1_100).coerceAtLeast(3_000),
        temperatureTenthsC = 300 + i,
        voltageMillivolts = 3_900 + i * 20,
        currentNowMicroamps = 2_400_000 - i * 150_000,
    )
}

@AmplyPreview
@Composable
private fun BatteryMetricDetailScreenPreview() = PreviewWrapper {
    BatteryMetricDetailScreen(state = previewState(), onBack = {})
}

@AmplyPreview
@Composable
private fun BatteryMetricDetailScreenTemperaturePreview() = PreviewWrapper {
    BatteryMetricDetailScreen(
        state = previewState(
            metric = BatteryMetric.TEMPERATURE,
            stats = MetricStats(min = 300, avg = 306, max = 312, sampleCount = 13),
        ),
        onBack = {},
    )
}

@AmplyPreview
@Composable
private fun BatteryMetricDetailScreenEmptyPreview() = PreviewWrapper {
    // Nothing recorded for this metric: the chart says so and the statistics row is absent entirely
    // rather than printing zeros.
    BatteryMetricDetailScreen(
        state = previewState(metric = BatteryMetric.POWER, curve = emptyList(), stats = null),
        onBack = {},
    )
}

@AmplyPreview
@Composable
private fun BatteryMetricDetailScreenMissingPreview() = PreviewWrapper {
    // Retention purged the session while the screen was open.
    BatteryMetricDetailScreen(state = previewState(sessionMissing = true), onBack = {})
}
