package eu.darken.amply.stats.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import android.os.SystemClock
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import eu.darken.amply.R
import eu.darken.amply.battery.core.BatteryReadout
import eu.darken.amply.common.compose.AmplyNavigationCard
import eu.darken.amply.common.compose.AmplyPreview
import eu.darken.amply.common.compose.PreviewWrapper
import eu.darken.amply.stats.core.ChargeCurvePoint
import eu.darken.amply.stats.core.StatsLiveSession
import eu.darken.amply.stats.core.StatsPowerCalculator

/**
 * Dashboard card shown directly under the battery hero while charging: the in-progress charge session
 * at a glance. Session identity, start, "partial" nature, and the compact curve come from the live
 * Room row ([live]); the current level / power / temperature come from the same fresh [battery]
 * readout the hero above uses, so the two never disagree. Tapping the card opens the statistics screen.
 */
@Composable
fun StatsCurrentSessionCard(
    live: StatsLiveSession,
    battery: BatteryReadout,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
    // Monotonic (never negative, immune to clock changes) and shares the curve's clock; recomputed on
    // each recomposition, which the 3s battery readout drives, so "charging for" ticks up on its own.
    nowElapsedRealtimeMillis: Long = SystemClock.elapsedRealtime(),
) {
    AmplyNavigationCard(
        onClick = onOpen,
        onClickLabel = stringResource(R.string.dashboard_stats_view_action),
        title = stringResource(R.string.dashboard_stats_live_title),
        icon = Icons.Filled.BatteryChargingFull,
        modifier = modifier,
    ) {
        val elapsed = StatsFormat.duration(
            (nowElapsedRealtimeMillis - live.startedElapsedRealtimeMillis).coerceAtLeast(0),
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                StatsFormat.percentRange(live.startPercent, battery.levelPercent),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
            )
            if (elapsed != null) {
                Text(
                    elapsed,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        val power = StatsFormat.power(
            StatsPowerCalculator.milliwatts(battery.voltageMillivolts, battery.currentNowMicroamps),
        )
        val temp = StatsFormat.temperature(battery.temperatureTenthsC)
        val metrics = listOfNotNull(power, temp).joinToString("  ·  ")
        if (metrics.isNotEmpty()) {
            Text(
                metrics,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (live.partial) {
            Text(
                stringResource(R.string.dashboard_stats_live_since, StatsFormat.dateTime(live.startedAtWallMillis)),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        StatsCurveChart(curve = live.curve, chartHeight = 110.dp)
    }
}

@AmplyPreview
@Composable
private fun StatsCurrentSessionCardPreview() = PreviewWrapper {
    val curve = (0..12).map { i ->
        ChargeCurvePoint(
            elapsedFromStartMillis = i * 300_000L,
            percent = (42 + i * 3).coerceAtMost(100),
            powerMilliwatts = (18_000 - i * 700).coerceAtLeast(2_000),
            temperatureTenthsC = 300 + i,
        )
    }
    Column {
        StatsCurrentSessionCard(
            live = StatsLiveSession(
                startedAtWallMillis = 0L,
                startedElapsedRealtimeMillis = 0L,
                startPercent = 42,
                partial = false,
                curve = curve,
            ),
            battery = BatteryReadout(
                levelPercent = 78,
                temperatureTenthsC = 312,
                voltageMillivolts = 4_100,
                currentNowMicroamps = 2_050_000,
            ),
            onOpen = {},
            nowElapsedRealtimeMillis = 4_320_000L,
        )
    }
}
