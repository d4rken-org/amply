package eu.darken.amply.main.ui.battery

import android.os.SystemClock
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import eu.darken.amply.R
import eu.darken.amply.common.compose.AmplyCard
import eu.darken.amply.common.compose.AmplyClickableCard
import eu.darken.amply.common.compose.AmplyPreview
import eu.darken.amply.common.compose.PreviewWrapper
import eu.darken.amply.stats.core.ChargeCurvePoint
import eu.darken.amply.stats.core.ChargeSessionSummary
import eu.darken.amply.stats.core.ChargingType
import eu.darken.amply.stats.core.StatsLiveSession
import eu.darken.amply.stats.core.StatsSealReason
import eu.darken.amply.stats.ui.StatsCurveChart
import eu.darken.amply.stats.ui.StatsFormat
import eu.darken.amply.stats.ui.rememberLiveElapsedMillis
import eu.darken.amply.stats.ui.shouldShowLiveCurve

/**
 * The hub's compact current-or-last charge. Tapping opens that session's full detail; the states with
 * no session to open ([ChargeTeaserState.Loading], [ChargeTeaserState.Unavailable],
 * [ChargeTeaserState.None]) are deliberately *not* clickable — a card that navigates nowhere is worse
 * than a card that plainly says there is nothing yet.
 */
@Composable
fun ChargeTeaser(
    state: ChargeTeaserState,
    onOpenSession: (Long) -> Unit,
    modifier: Modifier = Modifier,
    // The live level, from the same fresh readout the "Now" section below renders. Taken from there
    // rather than from the curve's last recorded point so the two can't show different percentages on
    // one screen — the curve is sampled on the recorder's cadence and lags by design.
    currentPercent: Int? = null,
    // Monotonic and injectable, so the elapsed text is testable without a real clock.
    nowElapsedRealtimeMillis: Long = SystemClock.elapsedRealtime(),
) {
    when (state) {
        ChargeTeaserState.CaptureOff -> Unit
        ChargeTeaserState.Loading -> NoticeCard(stringResource(R.string.battery_hub_teaser_loading), modifier)
        ChargeTeaserState.Unavailable -> NoticeCard(stringResource(R.string.battery_hub_teaser_unavailable), modifier)
        ChargeTeaserState.None -> NoticeCard(stringResource(R.string.battery_hub_teaser_none), modifier)
        ChargeTeaserState.Indeterminate ->
            NoticeCard(stringResource(R.string.battery_hub_teaser_indeterminate), modifier)
        is ChargeTeaserState.Live ->
            LiveTeaser(state.session, currentPercent, nowElapsedRealtimeMillis, onOpenSession, modifier)
        is ChargeTeaserState.Last -> LastTeaser(state.summary, onOpenSession, modifier)
    }
}

@Composable
private fun NoticeCard(text: String, modifier: Modifier) = AmplyCard(modifier = modifier) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun LiveTeaser(
    session: StatsLiveSession,
    currentPercent: Int?,
    nowElapsedRealtimeMillis: Long,
    onOpenSession: (Long) -> Unit,
    modifier: Modifier,
) {
    // Shared with the dashboard's charging card so the two clocks can't drift apart; StatsLiveSession
    // carries a start, never a duration.
    val elapsedMillis = rememberLiveElapsedMillis(session, nowElapsedRealtimeMillis)

    TeaserCard(
        onClick = { onOpenSession(session.id) },
        headline = StatsFormat.percentRange(session.startPercent, currentPercent),
        detail = StatsFormat.duration(elapsedMillis),
        modifier = modifier,
    ) {
        if (shouldShowLiveCurve(session.curve, elapsedMillis)) {
            StatsCurveChart(
                curve = session.curve,
                chartHeight = 84.dp,
                showAxes = false,
                // No range in the legend: this card's headline already states it. Explicitly null
                // rather than omitted — the default would fall back to the plotted window's span,
                // which on a long session narrows to something the headline contradicts.
                percentRangeLabel = null,
            )
        }
        if (session.partial) {
            PartialNote()
        }
    }
}

@Composable
private fun LastTeaser(
    summary: ChargeSessionSummary,
    onOpenSession: (Long) -> Unit,
    modifier: Modifier,
) {
    // A finished session is a summary row without samples — the curve belongs to the detail screen,
    // which loads it on demand. Nothing to draw here, so the teaser stays text-only.
    TeaserCard(
        onClick = { onOpenSession(summary.id) },
        headline = StatsFormat.percentRange(summary.startPercent, summary.endPercent),
        detail = StatsFormat.duration(summary.durationMillis),
        modifier = modifier,
    ) {
        if (summary.partial) {
            PartialNote()
        }
    }
}

@Composable
private fun TeaserCard(
    onClick: () -> Unit,
    headline: String,
    detail: String?,
    modifier: Modifier,
    content: @Composable () -> Unit,
) {
    AmplyClickableCard(
        onClick = onClick,
        onClickLabel = stringResource(R.string.battery_hub_teaser_open_action),
        modifier = modifier,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                headline,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
            )
            if (detail != null) {
                Text(
                    detail,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
            } else {
                Spacer(Modifier.weight(1f))
            }
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        content()
    }
}

/** A partial session's numbers are not a whole charge — say so rather than let them be misread. */
@Composable
private fun PartialNote() = Text(
    stringResource(R.string.stats_session_partial),
    style = MaterialTheme.typography.labelMedium,
    color = MaterialTheme.colorScheme.primary,
)

internal val previewCurve = (0..12).map { i ->
    ChargeCurvePoint(
        elapsedFromStartMillis = i * 300_000L,
        percent = (42 + i * 3).coerceAtMost(100),
        powerMilliwatts = (18_000 - i * 700).coerceAtLeast(2_000),
        temperatureTenthsC = 300 + i,
    )
}

internal val previewLiveSession = StatsLiveSession(
    id = 1,
    startedAtWallMillis = 0L,
    startedElapsedRealtimeMillis = 0L,
    startPercent = 42,
    partial = false,
    curve = previewCurve,
)

internal val previewLastSession = ChargeSessionSummary(
    id = 7,
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
private fun ChargeTeaserPreview() = PreviewWrapper {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ChargeTeaser(
            state = ChargeTeaserState.Live(previewLiveSession),
            onOpenSession = {},
            currentPercent = 78,
            nowElapsedRealtimeMillis = 4_320_000L,
        )
        ChargeTeaser(state = ChargeTeaserState.Last(previewLastSession), onOpenSession = {})
        ChargeTeaser(state = ChargeTeaserState.Last(previewLastSession.copy(partial = true)), onOpenSession = {})
    }
}

@AmplyPreview
@Composable
private fun ChargeTeaserEmptyStatesPreview() = PreviewWrapper {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ChargeTeaser(state = ChargeTeaserState.Loading, onOpenSession = {})
        ChargeTeaser(state = ChargeTeaserState.Unavailable, onOpenSession = {})
        ChargeTeaser(state = ChargeTeaserState.None, onOpenSession = {})
    }
}
