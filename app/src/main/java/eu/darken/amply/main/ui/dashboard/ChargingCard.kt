package eu.darken.amply.main.ui.dashboard

import android.os.BatteryManager
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
import eu.darken.amply.battery.ui.batteryStatusLabel
import eu.darken.amply.battery.ui.formatTemperature
import eu.darken.amply.common.compose.AmplyNavigationCard
import eu.darken.amply.common.compose.AmplyPreview
import eu.darken.amply.common.compose.PreviewWrapper
import eu.darken.amply.stats.core.ChargeCurvePoint
import eu.darken.amply.stats.core.ChargeSessionSummary
import eu.darken.amply.stats.core.ChargingType
import eu.darken.amply.stats.core.StatsLiveSession
import eu.darken.amply.stats.core.StatsSealReason
import eu.darken.amply.stats.ui.StatsCurveChart
import eu.darken.amply.stats.ui.StatsFormat
import eu.darken.amply.stats.ui.shouldShowLiveCurve
import kotlinx.coroutines.delay

const val CHARGING_CARD_TEST_TAG = "dashboard.charging.card"

/**
 * The dashboard's measurement card, and the app's single battery/charging telemetry surface.
 *
 * The live reading is rendered in **every** state, including when charge recording is switched off —
 * it comes from the permission-free battery broadcast, not from the opt-in stats pipeline, so gating
 * it behind capture would hide always-available data behind a feature flag. That is why [readout] is
 * its own parameter rather than a field on [ChargingCardPresentation]: only the *session* body below
 * adapts to capture state.
 *
 * The card holds a fixed slot on the dashboard and always opens the battery hub — one card, one
 * destination. Retry is the sole in-card action (a repair, not navigation) and must not bubble.
 */
@Composable
fun ChargingCard(
    presentation: ChargingCardPresentation,
    readout: BatteryReadout?,
    onOpenHub: () -> Unit,
    onRetryCapture: () -> Unit,
    modifier: Modifier = Modifier,
    // Monotonic (never negative, immune to clock changes) and shares the curve's clock; re-evaluated
    // on each recomposition and backstopped by a minute tick while live (see LiveBody).
    nowElapsedRealtimeMillis: Long = SystemClock.elapsedRealtime(),
) {
    // An absent readout renders every field as "Not reported" rather than hiding the line — the card
    // is the telemetry surface, so it must say it has nothing rather than silently shrink.
    val battery = readout ?: BatteryReadout.UNKNOWN
    // The title follows the raw readout, not the presentation: "on the charger" has exactly one truth
    // rule in this app (BatteryReadout.onCharger) and the card must not invent a second one. But being
    // on a charger is not the same as charging — a device held at its limit is connected and idle —
    // so the headline only says "Charging" when the platform positively reports it.
    val onCharger = battery.onCharger
    val charging = onCharger && battery.status == BatteryManager.BATTERY_STATUS_CHARGING
    AmplyNavigationCard(
        onClick = onOpenHub,
        onClickLabel = stringResource(R.string.dashboard_charging_open_action),
        title = stringResource(
            when {
                charging -> R.string.dashboard_charging_title_charging
                onCharger -> R.string.dashboard_charging_title_connected
                else -> R.string.dashboard_charging_title_idle
            },
        ),
        icon = if (onCharger) Icons.Filled.BatteryChargingFull else Icons.AutoMirrored.Filled.ShowChart,
        modifier = modifier.testTag(CHARGING_CARD_TEST_TAG),
    ) {
        Text(
            batteryNowLine(battery),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
        )
        when (presentation) {
            ChargingCardPresentation.Promo -> BodyText(stringResource(R.string.dashboard_charging_history_hint))
            ChargingCardPresentation.Loading -> BodyText(stringResource(R.string.dashboard_stats_loading))
            ChargingCardPresentation.Unavailable -> BodyText(stringResource(R.string.dashboard_stats_unavailable))
            ChargingCardPresentation.Indeterminate ->
                BodyText(stringResource(R.string.dashboard_charging_indeterminate))
            is ChargingCardPresentation.Live -> LiveBody(presentation, battery, nowElapsedRealtimeMillis)
            is ChargingCardPresentation.ConnectedWithoutSession -> ConnectedBody(presentation, onRetryCapture)
            is ChargingCardPresentation.Idle -> IdleBody(presentation)
        }
    }
}

/**
 * "82% · Charging · 12.3 W · 31.4 °C" — the live reading.
 *
 * Power is included only when [chargePowerMilliwatts] allows it (on the charger *and* actively
 * charging), because the underlying calculator returns an unsigned magnitude: shown while
 * discharging, the same figure would read as charge power. Every other value renders its own
 * "Not reported" rather than vanishing, so a sparse OEM readout is visible as sparse.
 */
@Composable
private fun batteryNowLine(readout: BatteryReadout): String {
    val notReported = stringResource(R.string.battery_value_not_reported)
    val level = readout.levelPercent?.let { "$it%" } ?: notReported
    val status = stringResource(batteryStatusLabel(readout.status, readout.plugged))
    val temperature = formatTemperature(readout.temperatureTenthsC) ?: notReported
    val power = chargePowerMilliwatts(readout)?.let { StatsFormat.power(it) }
    return if (power != null) {
        stringResource(R.string.battery_info_summary_power, level, status, power, temperature)
    } else {
        stringResource(R.string.battery_info_summary, level, status, temperature)
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
    live: ChargingCardPresentation.Live,
    battery: BatteryReadout,
    nowElapsedRealtimeMillis: Long,
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
            StatsFormat.percentRange(live.session.startPercent, battery.levelPercent),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (elapsed != null) {
            Text(
                elapsed,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    if (shouldShowLiveCurve(live.session.curve.size, elapsedMillis)) {
        StatsCurveChart(curve = live.session.curve, chartHeight = 84.dp, showAxes = false)
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
    connected: ChargingCardPresentation.ConnectedWithoutSession,
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
private fun IdleBody(idle: ChargingCardPresentation.Idle) {
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

private val previewCharging = BatteryReadout(
    levelPercent = 78,
    status = BatteryManager.BATTERY_STATUS_CHARGING,
    plugged = BatteryManager.BATTERY_PLUGGED_AC,
    temperatureTenthsC = 312,
    voltageMillivolts = 4_100,
    currentNowMicroamps = 2_050_000,
)

private val previewHolding = previewCharging.copy(
    levelPercent = 80,
    status = BatteryManager.BATTERY_STATUS_NOT_CHARGING,
)

private val previewUnplugged = BatteryReadout(
    levelPercent = 64,
    status = BatteryManager.BATTERY_STATUS_DISCHARGING,
    plugged = 0,
    temperatureTenthsC = 298,
    voltageMillivolts = 3_900,
    currentNowMicroamps = -450_000,
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
private fun ChargingCardLivePreview() = PreviewWrapper {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ChargingCard(
            presentation = ChargingCardPresentation.Live(session = previewLiveSession),
            readout = previewCharging,
            onOpenHub = {},
            onRetryCapture = {},
            nowElapsedRealtimeMillis = 4_320_000L,
        )
        // Held at a limit: connected, not charging, and deliberately no power figure.
        ChargingCard(
            presentation = ChargingCardPresentation.Live(session = previewLiveSession),
            readout = previewHolding,
            onOpenHub = {},
            onRetryCapture = {},
            nowElapsedRealtimeMillis = 4_320_000L,
        )
    }
}

@AmplyPreview
@Composable
private fun ChargingCardConnectedPreview() = PreviewWrapper {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ChargingCard(
            presentation = ChargingCardPresentation.ConnectedWithoutSession(startFailed = false),
            readout = previewCharging,
            onOpenHub = {},
            onRetryCapture = {},
        )
        ChargingCard(
            presentation = ChargingCardPresentation.ConnectedWithoutSession(startFailed = true),
            readout = previewCharging,
            onOpenHub = {},
            onRetryCapture = {},
        )
    }
}

@AmplyPreview
@Composable
private fun ChargingCardIdlePreview() = PreviewWrapper {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ChargingCard(
            presentation = ChargingCardPresentation.Idle(lastSession = previewLastSession, sessionCount = 12),
            readout = previewUnplugged,
            onOpenHub = {},
            onRetryCapture = {},
        )
        ChargingCard(
            presentation = ChargingCardPresentation.Idle(lastSession = null, sessionCount = 0),
            readout = previewUnplugged,
            onOpenHub = {},
            onRetryCapture = {},
        )
    }
}

@AmplyPreview
@Composable
private fun ChargingCardWithoutCapturePreview() = PreviewWrapper {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        // Capture off: the reading is still the point of the card.
        ChargingCard(
            presentation = ChargingCardPresentation.Promo,
            readout = previewUnplugged,
            onOpenHub = {},
            onRetryCapture = {},
        )
        ChargingCard(
            presentation = ChargingCardPresentation.Loading,
            readout = previewCharging,
            onOpenHub = {},
            onRetryCapture = {},
        )
        ChargingCard(
            presentation = ChargingCardPresentation.Unavailable,
            readout = previewCharging,
            onOpenHub = {},
            onRetryCapture = {},
        )
        // Nothing reported at all — every field says so.
        ChargingCard(
            presentation = ChargingCardPresentation.Idle(lastSession = null, sessionCount = 0),
            readout = null,
            onOpenHub = {},
            onRetryCapture = {},
        )
    }
}
