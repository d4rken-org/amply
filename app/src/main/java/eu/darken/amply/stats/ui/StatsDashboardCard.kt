package eu.darken.amply.stats.ui

import android.os.SystemClock
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ShowChart
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import eu.darken.amply.R
import eu.darken.amply.battery.core.BatteryReadout
import eu.darken.amply.common.compose.AmplyNavigationCard
import eu.darken.amply.common.compose.AmplyPreview
import eu.darken.amply.common.compose.PreviewWrapper
import eu.darken.amply.stats.core.ChargeCurvePoint
import eu.darken.amply.stats.core.ChargeSessionSummary
import eu.darken.amply.stats.core.ChargingType
import eu.darken.amply.stats.core.StatsLiveSession
import eu.darken.amply.stats.core.StatsPowerCalculator
import eu.darken.amply.stats.core.StatsSealReason
import kotlinx.coroutines.delay

const val STATS_CARD_TEST_TAG = "dashboard.stats.card"

/**
 * The single dashboard stats card. Its content adapts through [StatsCardPresentation] — promo when
 * capture is off, the in-progress session while on the charger, an honest "no session yet / couldn't
 * start" while connected without a recorder row, and the last-session teaser otherwise. Session
 * identity, start, and the compact curve come from the live Room row; the current level / power /
 * temperature come from the same fresh battery readout the hero uses, so the two never disagree.
 * (Where the card sits on the dashboard is decided separately — see `DashboardScreen`.)
 *
 * Tapping opens the statistics screen, or the in-progress session's detail while live — which is why
 * the live state also carries an explicit "History" action: it is the only state whose surface tap
 * leads somewhere else. Both that and the retry action must not bubble to the card's own navigation.
 */
@Composable
fun StatsDashboardCard(
    presentation: StatsCardPresentation,
    onOpenStats: () -> Unit,
    onOpenLiveSession: (Long) -> Unit,
    onRetryCapture: () -> Unit,
    modifier: Modifier = Modifier,
    // Monotonic (never negative, immune to clock changes) and shares the curve's clock; re-evaluated
    // on each recomposition and backstopped by a minute tick while live (see LiveBody).
    nowElapsedRealtimeMillis: Long = SystemClock.elapsedRealtime(),
) {
    val connected = presentation is StatsCardPresentation.Live ||
        presentation is StatsCardPresentation.ConnectedWithoutSession
    AmplyNavigationCard(
        onClick = when (presentation) {
            is StatsCardPresentation.Live -> ({ onOpenLiveSession(presentation.session.id) })
            else -> onOpenStats
        },
        onClickLabel = stringResource(R.string.dashboard_stats_view_action),
        title = stringResource(
            if (connected) R.string.dashboard_stats_live_title else R.string.dashboard_stats_title,
        ),
        icon = if (connected) Icons.Filled.BatteryChargingFull else Icons.AutoMirrored.Filled.ShowChart,
        modifier = modifier.testTag(STATS_CARD_TEST_TAG),
    ) {
        when (presentation) {
            StatsCardPresentation.Promo -> BodyText(stringResource(R.string.dashboard_stats_promo))
            StatsCardPresentation.Loading -> BodyText(stringResource(R.string.dashboard_stats_loading))
            StatsCardPresentation.Unavailable -> BodyText(stringResource(R.string.dashboard_stats_unavailable))
            is StatsCardPresentation.Live -> LiveBody(presentation, nowElapsedRealtimeMillis, onOpenStats)
            is StatsCardPresentation.ConnectedWithoutSession -> ConnectedBody(presentation, onRetryCapture)
            is StatsCardPresentation.Idle -> IdleBody(presentation)
        }
    }
}

@Composable
private fun BodyText(text: String) = Text(
    text,
    style = MaterialTheme.typography.bodyMedium,
    color = MaterialTheme.colorScheme.onSurfaceVariant,
)

@Composable
private fun ColumnScope.LiveBody(
    live: StatsCardPresentation.Live,
    nowElapsedRealtimeMillis: Long,
    onOpenStats: () -> Unit,
) {
    // Fresh battery readouts normally drive recomposition (~3s), but identical consecutive readouts
    // are conflated upstream — the minute tick keeps "charging for" moving even without new data.
    // maxOf picks whichever clock read is fresher; both are monotonic elapsed-realtime.
    var tickedNow by remember { mutableLongStateOf(nowElapsedRealtimeMillis) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(LIVE_TICK_MILLIS)
            tickedNow = SystemClock.elapsedRealtime()
        }
    }
    val now = maxOf(tickedNow, nowElapsedRealtimeMillis)

    val elapsedMillis = (now - live.session.startedElapsedRealtimeMillis).coerceAtLeast(0)
    val elapsed = StatsFormat.duration(elapsedMillis)
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            StatsFormat.percentRange(live.session.startPercent, live.battery.levelPercent),
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

    val metrics = liveMetricsLine(live.battery)
    if (metrics.isNotEmpty()) BodyText(metrics)

    // A curve is only meaningful once a few minutes of points exist — before that it's a flat/near-
    // degenerate line, so keep the card compact and text-only until then.
    if (elapsedMillis >= CHART_MIN_ELAPSED_MILLIS && live.session.curve.size >= 2) {
        StatsCurveChart(curve = live.session.curve, chartHeight = 84.dp, showAxes = false)
    }

    // While live the card's own tap deep-links into this session, so the past-sessions list needs its
    // own way in. Kept a plain text action (like Retry) rather than a second card.
    TextButton(
        onClick = onOpenStats,
        modifier = Modifier.align(Alignment.End),
    ) {
        Text(stringResource(R.string.dashboard_stats_history_action))
    }

    // Small bottom-right caption noting a mid-charge start (so the start%/elapsed aren't read as a
    // full charge history).
    if (live.session.partial) {
        Text(
            stringResource(R.string.dashboard_stats_live_since, StatsFormat.dateTime(live.session.startedAtWallMillis)),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.align(Alignment.End),
        )
    }
}

@Composable
private fun ColumnScope.ConnectedBody(
    connected: StatsCardPresentation.ConnectedWithoutSession,
    onRetryCapture: () -> Unit,
) {
    BodyText(
        stringResource(
            if (connected.startFailed) {
                R.string.dashboard_stats_live_start_failed
            } else {
                R.string.dashboard_stats_live_starting
            },
        ),
    )
    val metrics = liveMetricsLine(connected.battery)
    if (metrics.isNotEmpty()) BodyText(metrics)
    if (connected.startFailed) {
        TextButton(
            onClick = onRetryCapture,
            modifier = Modifier.align(Alignment.End),
        ) {
            Text(stringResource(R.string.dashboard_stats_retry_action))
        }
    }
}

@Composable
private fun IdleBody(idle: StatsCardPresentation.Idle) {
    val lastSession = idle.lastSession
    if (lastSession != null) {
        val range = StatsFormat.percentRange(lastSession.startPercent, lastSession.endPercent)
        val duration = StatsFormat.duration(lastSession.durationMillis)
        val summary = if (duration != null) "$range  ·  $duration" else range
        BodyText(stringResource(R.string.dashboard_stats_last, summary))
        Text(
            pluralStringResource(R.plurals.dashboard_stats_session_count, idle.sessionCount, idle.sessionCount),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    } else {
        BodyText(stringResource(R.string.dashboard_stats_recording_empty))
    }
}

/** "8.4 W  ·  31.2 °C" from the raw readout; units formatted inline per [StatsFormat] convention. */
private fun liveMetricsLine(battery: BatteryReadout): String = listOfNotNull(
    StatsFormat.power(StatsPowerCalculator.milliwatts(battery.voltageMillivolts, battery.currentNowMicroamps)),
    StatsFormat.temperature(battery.temperatureTenthsC),
).joinToString("  ·  ")

/** Withhold the live curve until the session has a few minutes of points to draw a meaningful shape. */
private const val CHART_MIN_ELAPSED_MILLIS = 180_000L

/** Backstop cadence for the live elapsed text when battery readouts stop changing. */
private const val LIVE_TICK_MILLIS = 60_000L

private val previewCurve = (0..12).map { i ->
    ChargeCurvePoint(
        elapsedFromStartMillis = i * 300_000L,
        percent = (42 + i * 3).coerceAtMost(100),
        powerMilliwatts = (18_000 - i * 700).coerceAtLeast(2_000),
        temperatureTenthsC = 300 + i,
    )
}

private val previewLiveSession = StatsLiveSession(
    id = 1,
    startedAtWallMillis = 0L,
    startedElapsedRealtimeMillis = 0L,
    startPercent = 42,
    partial = true,
    curve = previewCurve,
)

private val previewBattery = BatteryReadout(
    levelPercent = 78,
    plugged = 1,
    temperatureTenthsC = 312,
    voltageMillivolts = 4_100,
    currentNowMicroamps = 2_050_000,
)

private val previewLastSession = ChargeSessionSummary(
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
)

@AmplyPreview
@Composable
private fun StatsDashboardCardPreview() = PreviewWrapper {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        StatsDashboardCard(
            presentation = StatsCardPresentation.Live(session = previewLiveSession, battery = previewBattery),
            onOpenStats = {},
            onOpenLiveSession = {},
            onRetryCapture = {},
            nowElapsedRealtimeMillis = 4_320_000L,
        )
        StatsDashboardCard(
            presentation = StatsCardPresentation.ConnectedWithoutSession(battery = previewBattery, startFailed = false),
            onOpenStats = {},
            onOpenLiveSession = {},
            onRetryCapture = {},
        )
        StatsDashboardCard(
            presentation = StatsCardPresentation.ConnectedWithoutSession(battery = previewBattery, startFailed = true),
            onOpenStats = {},
            onOpenLiveSession = {},
            onRetryCapture = {},
        )
        StatsDashboardCard(
            presentation = StatsCardPresentation.Idle(lastSession = previewLastSession, sessionCount = 3),
            onOpenStats = {},
            onOpenLiveSession = {},
            onRetryCapture = {},
        )
        StatsDashboardCard(
            presentation = StatsCardPresentation.Idle(lastSession = null, sessionCount = 0),
            onOpenStats = {},
            onOpenLiveSession = {},
            onRetryCapture = {},
        )
        StatsDashboardCard(
            presentation = StatsCardPresentation.Promo,
            onOpenStats = {},
            onOpenLiveSession = {},
            onRetryCapture = {},
        )
        StatsDashboardCard(
            presentation = StatsCardPresentation.Loading,
            onOpenStats = {},
            onOpenLiveSession = {},
            onRetryCapture = {},
        )
        StatsDashboardCard(
            presentation = StatsCardPresentation.Unavailable,
            onOpenStats = {},
            onOpenLiveSession = {},
            onRetryCapture = {},
        )
    }
}

@androidx.compose.ui.tooling.preview.Preview(fontScale = 1.5f)
@Composable
private fun StatsDashboardCardLargeFontPreview() = PreviewWrapper {
    StatsDashboardCard(
        presentation = StatsCardPresentation.Live(session = previewLiveSession, battery = previewBattery),
        onOpenStats = {},
        onOpenLiveSession = {},
        onRetryCapture = {},
        nowElapsedRealtimeMillis = 4_320_000L,
    )
}
