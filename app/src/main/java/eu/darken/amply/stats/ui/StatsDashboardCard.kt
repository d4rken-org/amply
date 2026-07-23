package eu.darken.amply.stats.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ShowChart
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import eu.darken.amply.R
import eu.darken.amply.common.compose.AmplyNavigationCard
import eu.darken.amply.common.compose.AmplyPreview
import eu.darken.amply.common.compose.PreviewWrapper
import eu.darken.amply.stats.core.ChargeSessionSummary
import eu.darken.amply.stats.core.ChargingType
import eu.darken.amply.stats.core.StatsSealReason

/**
 * Dashboard entry point for battery statistics, alongside the other permission-free feature cards.
 * A navigation card (no inline toggle): when capture is off it promotes the feature, when on it
 * teases the latest session and count. Tapping it opens the full statistics screen, where the
 * always-on capture is turned on with its persistent-notification caveat.
 */
@Composable
fun StatsDashboardCard(
    enabled: Boolean,
    lastSession: ChargeSessionSummary?,
    sessionCount: Int,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AmplyNavigationCard(
        onClick = onOpen,
        onClickLabel = stringResource(R.string.dashboard_stats_view_action),
        title = stringResource(R.string.dashboard_stats_title),
        icon = Icons.AutoMirrored.Filled.ShowChart,
        modifier = modifier,
    ) {
        when {
            !enabled -> Text(
                stringResource(R.string.dashboard_stats_promo),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            lastSession != null -> {
                val range = StatsFormat.percentRange(lastSession.startPercent, lastSession.endPercent)
                val duration = StatsFormat.duration(lastSession.durationMillis)
                val summary = if (duration != null) "$range  ·  $duration" else range
                Text(
                    stringResource(R.string.dashboard_stats_last, summary),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    pluralStringResource(R.plurals.dashboard_stats_session_count, sessionCount, sessionCount),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            else -> Text(
                stringResource(R.string.dashboard_stats_recording_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@AmplyPreview
@Composable
private fun StatsDashboardCardPreview() = PreviewWrapper {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        StatsDashboardCard(
            enabled = true,
            lastSession = ChargeSessionSummary(
                id = 1,
                startedAtWallMillis = 0,
                endedAtWallMillis = 0,
                durationMillis = 4_320_000,
                startPercent = 42,
                endPercent = 100,
                chargingType = ChargingType.AC,
                avgPowerMilliwatts = 12_000,
                peakPowerMilliwatts = 27_000,
                minTemperatureTenthsC = 280,
                avgTemperatureTenthsC = 305,
                maxTemperatureTenthsC = 330,
                limitHit = false,
                partial = false,
                fullReachedAtWallMillis = 0,
                sealReason = StatsSealReason.UNPLUGGED,
            ),
            sessionCount = 3,
            onOpen = {},
        )
        StatsDashboardCard(enabled = false, lastSession = null, sessionCount = 0, onOpen = {})
        StatsDashboardCard(enabled = true, lastSession = null, sessionCount = 0, onOpen = {})
    }
}
