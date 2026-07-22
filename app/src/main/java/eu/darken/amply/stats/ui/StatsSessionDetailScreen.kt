package eu.darken.amply.stats.ui

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
import androidx.compose.material3.Card
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
import eu.darken.amply.common.compose.AmplyPreview
import eu.darken.amply.common.compose.PreviewWrapper
import eu.darken.amply.common.compose.chart.ChartPoint
import eu.darken.amply.common.compose.chart.ChartSeries
import eu.darken.amply.common.compose.chart.LineChart
import eu.darken.amply.stats.core.ChargeCurvePoint
import eu.darken.amply.stats.core.ChargeSessionSummary
import eu.darken.amply.stats.core.ChargingType
import eu.darken.amply.stats.core.StatsSealReason

/**
 * One session's charge curve (percent / power / temperature over time) plus its numeric summary.
 * State-hoisted; a null [state] shows a spinner while the ViewModel resolves the selection.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatsSessionDetailScreen(
    state: StatsDetailState?,
    onBack: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.stats_detail_title)) },
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
        val summary = state?.summary
        if (state == null || summary == null) {
            Box(
                Modifier
                    .padding(padding)
                    .fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
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
            item { CurveCard(state.curve) }
            item { SummaryCard(summary) }
            if (summary.partial) {
                item {
                    Text(
                        stringResource(R.string.stats_detail_partial_note),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 4.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun CurveCard(curve: List<ChargeCurvePoint>) {
    val percentColor = MaterialTheme.colorScheme.primary
    val powerColor = MaterialTheme.colorScheme.tertiary
    val tempColor = MaterialTheme.colorScheme.error
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                stringResource(R.string.stats_detail_curve_title),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(bottom = 4.dp),
            )
            LineChart(
                series = listOf(
                    ChartSeries(
                        label = stringResource(R.string.stats_curve_series_percent),
                        color = percentColor,
                        points = curve.map { ChartPoint(it.elapsedFromStartMillis.toFloat(), it.percent?.toFloat()) },
                    ),
                    ChartSeries(
                        label = stringResource(R.string.stats_curve_series_power),
                        color = powerColor,
                        points = curve.map { ChartPoint(it.elapsedFromStartMillis.toFloat(), it.powerMilliwatts?.toFloat()) },
                    ),
                    ChartSeries(
                        label = stringResource(R.string.stats_curve_series_temperature),
                        color = tempColor,
                        points = curve.map { ChartPoint(it.elapsedFromStartMillis.toFloat(), it.temperatureTenthsC?.toFloat()) },
                    ),
                ),
                emptyLabel = stringResource(R.string.stats_curve_empty),
            )
        }
    }
}

@Composable
private fun SummaryCard(summary: ChargeSessionSummary) {
    val notReported = stringResource(R.string.battery_value_not_reported)
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            DetailRow(stringResource(R.string.stats_detail_level), StatsFormat.percentRange(summary.startPercent, summary.endPercent))
            DetailRow(stringResource(R.string.stats_detail_duration), StatsFormat.duration(summary.durationMillis) ?: notReported)
            DetailRow(stringResource(R.string.stats_detail_charging_type), stringResource(chargingTypeLabel(summary.chargingType)))
            DetailRow(stringResource(R.string.stats_detail_avg_power), StatsFormat.power(summary.avgPowerMilliwatts) ?: notReported)
            DetailRow(stringResource(R.string.stats_detail_peak_power), StatsFormat.power(summary.peakPowerMilliwatts) ?: notReported)
            DetailRow(
                stringResource(R.string.stats_detail_temperature),
                StatsFormat.temperatureRange(summary.minTemperatureTenthsC, summary.maxTemperatureTenthsC) ?: notReported,
            )
            DetailRow(
                stringResource(R.string.stats_detail_limit_likely),
                stringResource(if (summary.limitHit) R.string.stats_value_yes else R.string.stats_value_no),
            )
        }
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
    }
}

@AmplyPreview
@Composable
private fun StatsSessionDetailScreenPreview() = PreviewWrapper {
    val start = 0L
    val curve = (0..20).map { i ->
        ChargeCurvePoint(
            elapsedFromStartMillis = start + i * 180_000L,
            percent = (42 + i * 3).coerceAtMost(100),
            powerMilliwatts = (25_000 - i * 900).coerceAtLeast(1_500),
            temperatureTenthsC = 300 + i * 2,
        )
    }
    StatsSessionDetailScreen(
        state = StatsDetailState(
            summary = ChargeSessionSummary(
                id = 1,
                startedAtWallMillis = System.currentTimeMillis() - 3_600_000,
                endedAtWallMillis = System.currentTimeMillis(),
                durationMillis = 3_600_000,
                startPercent = 42,
                endPercent = 100,
                chargingType = ChargingType.AC,
                avgPowerMilliwatts = 12_000,
                peakPowerMilliwatts = 25_000,
                minTemperatureTenthsC = 300,
                avgTemperatureTenthsC = 320,
                maxTemperatureTenthsC = 340,
                limitHit = false,
                partial = false,
                fullReachedAtWallMillis = System.currentTimeMillis() - 60_000,
                sealReason = StatsSealReason.UNPLUGGED,
            ),
            curve = curve,
        ),
        onBack = {},
    )
}
