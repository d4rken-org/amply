package eu.darken.amply.main.ui.battery

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ShowChart
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Thermostat
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import eu.darken.amply.R
import eu.darken.amply.battery.core.BatteryReadout
import eu.darken.amply.battery.ui.BatteryEffect
import eu.darken.amply.battery.ui.batteryHealthLabel
import eu.darken.amply.battery.ui.batteryPlugLabel
import eu.darken.amply.battery.ui.batteryStatusLabel
import eu.darken.amply.battery.ui.chargePowerTileFallback
import eu.darken.amply.battery.ui.formatChargeCounter
import eu.darken.amply.common.compose.AmplyCard
import eu.darken.amply.common.compose.AmplyCardDefaults
import eu.darken.amply.common.compose.AmplyPreview
import eu.darken.amply.common.compose.PreviewWrapper
import eu.darken.amply.common.compose.chart.ChartPoint
import eu.darken.amply.stats.core.ChargeBandSplit
import eu.darken.amply.stats.core.ChargeCurvePoint
import eu.darken.amply.stats.core.ChargeTimeBasis
import eu.darken.amply.stats.core.ChargeTimeEstimate
import eu.darken.amply.stats.core.CurveMetricAvailability
import eu.darken.amply.stats.core.StatsPowerCalculator
import eu.darken.amply.stats.ui.ChargeTimeState
import eu.darken.amply.stats.ui.StatsFormat

/**
 * The single battery/charging destination: the current or last charge and the full live readout, led
 * by the recording opt-in until it has been accepted. Replaces the split between a read-only
 * battery-detail screen and a statistics screen that each rendered level, current, and temperature
 * through different code.
 *
 * State-hoisted and previewable — it renders straight from a [BatteryReadout] plus a
 * [ChargeTeaserState], so it needs no ViewModel of its own. Fields the platform doesn't report render
 * as "Not reported" rather than being hidden, so a sparse OEM readout is visible as sparse.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BatteryHubScreen(
    readout: BatteryReadout?,
    captureEnabled: Boolean,
    teaser: ChargeTeaserState,
    // Only badge the opt-in once the entitlement is settled and free — a paying user must not see it
    // flash while billing connects.
    showProBadge: Boolean,
    onBack: () -> Unit,
    onOpenHistory: () -> Unit,
    onEnableCapture: () -> Unit,
    onOpenSession: (Long) -> Unit,
    onOpenMetric: (BatteryMetric) -> Unit,
    // The charge being shown by the teaser, for the tiles' sparklines. Empty until something has been
    // recorded — every tile still renders its live value.
    curve: List<ChargeCurvePoint> = emptyList(),
    // What that charge actually recorded, taken from its raw samples: the curve above is decimated,
    // so it cannot answer "was this metric ever reported" (see CurveMetricAvailability).
    availability: CurveMetricAvailability = CurveMetricAvailability.NONE,
    // Charge-time estimates. Only rendered while recording is on: they come entirely from recorded
    // history, so with recording off there is no card rather than an empty one.
    chargeTime: ChargeTimeState = ChargeTimeState.Loading,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.battery_hub_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.action_back),
                        )
                    }
                },
                actions = {
                    IconButton(onClick = onOpenHistory) {
                        Icon(
                            Icons.AutoMirrored.Filled.ShowChart,
                            contentDescription = stringResource(R.string.battery_hub_history_action),
                        )
                    }
                },
            )
        },
    ) { padding ->
        val data = readout ?: BatteryReadout.UNKNOWN
        LazyColumn(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Gated on the authoritative preference, not on the teaser: the opt-in is a one-time
            // prompt, so it disappears for good the moment recording is on.
            if (!captureEnabled) {
                item { CaptureOptInCard(onEnable = onEnableCapture, showProBadge = showProBadge) }
            }
            // The charge section sits above the raw readout: "how is this charge going" is the question
            // that brings most visits here, and the readout below is reference material. With recording
            // on it is the first thing on the screen; with recording off there is no section at all —
            // the opt-in card immediately above already explains why.
            if (teaser != ChargeTeaserState.CaptureOff) {
                item { SectionHeader(stringResource(teaser.sectionTitle())) }
                item {
                    ChargeTeaser(
                        state = teaser,
                        onOpenSession = onOpenSession,
                        currentPercent = data.levelPercent,
                    )
                }
            }
            if (captureEnabled) {
                item { ChargeTimeCard(state = chargeTime) }
            }
            item { SectionHeader(stringResource(R.string.battery_hub_section_now)) }
            // A Column of Rows, not a LazyVerticalGrid: this screen is itself a LazyColumn, and
            // nesting a lazy grid in a lazy column throws at runtime.
            item {
                StatTileGrid(
                    readout = data,
                    curve = curve,
                    availability = availability,
                    onOpenMetric = onOpenMetric,
                )
            }
            item { ChargingSection(data) }
            item { HealthSection(data) }
            item { ElectricalSection(data) }
        }
    }
}

private fun ChargeTeaserState.sectionTitle(): Int = when (this) {
    is ChargeTeaserState.Live -> R.string.battery_hub_section_charge_live
    // Neutral heading while the plug state is unknown: "Last charge" over an unreadable battery would
    // assert the charge is over.
    ChargeTeaserState.Indeterminate -> R.string.battery_hub_section_charge
    else -> R.string.battery_hub_section_charge_last
}

@Composable
private fun SectionHeader(title: String) = Text(
    title,
    style = MaterialTheme.typography.titleSmall,
    color = MaterialTheme.colorScheme.primary,
    modifier = Modifier.padding(start = 4.dp, top = 4.dp),
)

/**
 * The six live readings as a 2-column tile grid.
 *
 * A tile is tappable when the shown charge recorded **any** sample for that metric — not when the
 * samples vary. A constant reading is still worth opening (min == avg == max is an answer, and the
 * detail chart plots a zero-range series on a real axis); only the sparkline needs variation,
 * which it decides for itself. Status is a label, not a series, so it never navigates.
 *
 * Tappability comes from [availability], never from [curve]: the curve is decimated for drawing, so
 * an intermittently reported metric can be absent from it while the session holds readings for it.
 */
@Composable
private fun StatTileGrid(
    readout: BatteryReadout,
    curve: List<ChargeCurvePoint>,
    availability: CurveMetricAvailability,
    onOpenMetric: (BatteryMetric) -> Unit,
) {
    val openLabel = stringResource(R.string.battery_metric_open_action)
    val notReported = stringResource(R.string.battery_value_not_reported)
    // Charge power is the one reading with a meaningful "not charging" state, and it is the same
    // rule the electrical rows used before the tiles existed — rendered as a dash here, because a
    // half-width tile has no room for the words.
    val powerFallback = chargePowerTileFallback(BatteryEffect.from(readout))
    val powerFallbackText = stringResource(powerFallback.textRes)
    val powerFallbackSpoken = stringResource(powerFallback.spokenRes)

    @Composable
    fun metricTile(metric: BatteryMetric, modifier: Modifier, icon: ImageVector? = null) {
        val isPower = metric == BatteryMetric.POWER
        val fallback = if (isPower) powerFallbackText else notReported
        val shown = metric.format(metric.select(readout))
        // The dash stands in for a figure rather than being one, so it is dimmed and speaks the
        // words it replaces — which is exactly the case where the fallback's shown and spoken forms
        // differ. Every other value, "Not reported" included, renders as written.
        val isDash = isPower && shown == null && powerFallback.textRes != powerFallback.spokenRes
        BatteryStatTile(
            label = stringResource(metric.titleRes),
            value = shown ?: fallback,
            modifier = modifier,
            valueColor = if (isDash) MaterialTheme.colorScheme.onSurfaceVariant else Color.Unspecified,
            valueDescription = if (isDash) powerFallbackSpoken else null,
            icon = icon,
            sparkline = curve.map {
                ChartPoint(it.elapsedFromStartMillis.toFloat(), metric.select(it)?.toFloat())
            },
            accentColor = metric.accentColor(),
            onClick = if (metric.hasSamples(availability)) {
                { onOpenMetric(metric) }
            } else {
                null
            },
            onClickLabel = openLabel,
        )
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        BatteryStatTileRow { tile ->
            metricTile(BatteryMetric.LEVEL, tile, Icons.Filled.BatteryChargingFull)
            metricTile(BatteryMetric.POWER, tile, Icons.Filled.Bolt)
        }
        BatteryStatTileRow { tile ->
            metricTile(BatteryMetric.VOLTAGE, tile)
            metricTile(BatteryMetric.CURRENT, tile)
        }
        BatteryStatTileRow { tile ->
            metricTile(BatteryMetric.TEMPERATURE, tile, Icons.Filled.Thermostat)
            BatteryStatTile(
                label = stringResource(R.string.battery_detail_status),
                value = stringResource(batteryStatusLabel(readout.status, readout.plugged)),
                modifier = tile,
                // A state label, not a measurement: it is the one tile whose value is a sentence
                // ("Plugged in, not charging"), so it reads a step smaller than the numbers and wraps
                // into the room that buys instead of being clipped.
                valueStyle = MaterialTheme.typography.titleMedium,
                // The third line is for accessibility font scales, not for normal rendering: at 2x
                // the sentence needs 24 characters where two lines of this style hold 22. The box is
                // a minimum height, so at normal scale the value still takes the lines it needs.
                valueMaxLines = 3,
            )
        }
    }
}

/** Matches the charge curve's series colours, so a tile and the chart it opens read as one metric. */
@Composable
private fun BatteryMetric.accentColor(): Color = when (this) {
    BatteryMetric.LEVEL -> MaterialTheme.colorScheme.primary
    BatteryMetric.POWER -> MaterialTheme.colorScheme.tertiary
    BatteryMetric.TEMPERATURE -> MaterialTheme.colorScheme.error
    BatteryMetric.VOLTAGE, BatteryMetric.CURRENT -> MaterialTheme.colorScheme.secondary
}

@Composable
private fun ChargingSection(readout: BatteryReadout) {
    val notReported = stringResource(R.string.battery_value_not_reported)
    DetailSection(stringResource(R.string.battery_detail_section_charging)) {
        DetailRow(
            stringResource(R.string.battery_detail_power_source),
            batteryPlugLabel(readout.plugged)?.let { stringResource(it) } ?: notReported,
        )
        DetailRow(
            stringResource(R.string.battery_detail_technology),
            readout.technology ?: notReported,
        )
    }
}

@Composable
private fun HealthSection(readout: BatteryReadout) {
    val notReported = stringResource(R.string.battery_value_not_reported)
    DetailSection(stringResource(R.string.battery_detail_section_health)) {
        DetailRow(
            stringResource(R.string.battery_detail_health),
            stringResource(batteryHealthLabel(readout.health)),
        )
        DetailRow(
            stringResource(R.string.battery_detail_cycle_count),
            readout.cycleCount?.toString() ?: notReported,
        )
    }
}

@Composable
private fun ElectricalSection(readout: BatteryReadout) {
    val notReported = stringResource(R.string.battery_value_not_reported)
    // Voltage, current and charge power moved up into the tile grid; what stays here is the pair
    // that has no live series to chart.
    DetailSection(stringResource(R.string.battery_detail_section_electrical)) {
        // Advertised, not measured: what the connected supply claims. Nothing connected means nothing
        // to claim, so the extras are only read through while on a charger.
        DetailRow(
            stringResource(R.string.battery_detail_charger_max),
            readout.takeIf { it.onCharger }
                ?.let { StatsFormat.power(StatsPowerCalculator.advertisedMaxMilliwatts(it)) }
                ?: notReported,
        )
        DetailRow(
            stringResource(R.string.battery_detail_charge_counter),
            formatChargeCounter(readout.chargeCounterMicroampHours) ?: notReported,
        )
    }
}

@Composable
private fun DetailSection(title: String, content: @Composable () -> Unit) {
    AmplyCard(verticalArrangement = Arrangement.spacedBy(AmplyCardDefaults.ItemSpacing)) {
        Text(
            title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
        )
        content()
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
        // Both columns weighted and the value end-aligned, so a long value (a scaled-up font, a
        // verbose technology string) wraps instead of clipping.
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.End,
            modifier = Modifier.weight(1f),
        )
    }
}

private val previewCharging = BatteryReadout(
    levelPercent = 82,
    status = android.os.BatteryManager.BATTERY_STATUS_CHARGING,
    plugged = android.os.BatteryManager.BATTERY_PLUGGED_AC,
    health = android.os.BatteryManager.BATTERY_HEALTH_GOOD,
    technology = "Li-ion",
    temperatureTenthsC = 314,
    voltageMillivolts = 4185,
    currentNowMicroamps = 1_250_000,
    chargeCounterMicroampHours = 3_800_000,
    cycleCount = 142,
    // A 9 V / 2 A charger advertising itself while the battery measures ~5.2 W.
    maxChargingCurrentMicroamps = 2_000_000,
    maxChargingVoltageMicrovolts = 9_000_000,
)

private val previewChargeTime = ChargeTimeState.Ready(
    estimate = ChargeTimeEstimate(
        toEightyMillis = null,
        toFullMillis = 1_080_000,
        avgSpeedMilliwatts = 11_500,
        split = ChargeBandSplit(
            toFiftyMillis = 2_700_000,
            fiftyToEightyMillis = 1_800_000,
            eightyToHundredMillis = 3_600_000,
        ),
        basedOnSessions = 6,
    ),
    basis = ChargeTimeBasis.SAME_TYPE,
    charging = true,
    currentPercent = 82,
)

@AmplyPreview
@Composable
private fun BatteryHubScreenLivePreview() = PreviewWrapper {
    // Recording on: no opt-in card, so the live charge is the first thing on the screen.
    BatteryHubScreen(
        readout = previewCharging,
        captureEnabled = true,
        teaser = ChargeTeaserState.Live(previewLiveSession),
        showProBadge = false,
        onBack = {},
        onOpenHistory = {},
        onEnableCapture = {},
        onOpenSession = {},
        onOpenMetric = {},
        curve = previewCurve,
        availability = CurveMetricAvailability.of(previewCurve),
        chargeTime = previewChargeTime,
    )
}

@AmplyPreview
@Composable
private fun BatteryHubScreenLastPreview() = PreviewWrapper {
    BatteryHubScreen(
        readout = previewCharging.copy(
            status = android.os.BatteryManager.BATTERY_STATUS_DISCHARGING,
            plugged = 0,
        ),
        captureEnabled = true,
        teaser = ChargeTeaserState.Last(previewLastSession),
        showProBadge = false,
        onBack = {},
        onOpenHistory = {},
        onEnableCapture = {},
        onOpenSession = {},
        onOpenMetric = {},
        curve = previewCurve,
        availability = CurveMetricAvailability.of(previewCurve),
    )
}

@AmplyPreview
@Composable
private fun BatteryHubScreenCaptureOffPreview() = PreviewWrapper {
    // Never opted in: the opt-in card leads, there is no charge section, and the full readout is still
    // here — it never depended on recording.
    BatteryHubScreen(
        readout = previewCharging,
        captureEnabled = false,
        teaser = ChargeTeaserState.CaptureOff,
        showProBadge = true,
        onBack = {},
        onOpenHistory = {},
        onEnableCapture = {},
        onOpenSession = {},
        onOpenMetric = {},
    )
}

@AmplyPreview
@Composable
private fun BatteryHubScreenCaptureOffProPreview() = PreviewWrapper {
    // Same card for an upgraded user (or before the entitlement has settled): no Pro badge on the
    // opt-in action.
    BatteryHubScreen(
        readout = previewCharging,
        captureEnabled = false,
        teaser = ChargeTeaserState.CaptureOff,
        showProBadge = false,
        onBack = {},
        onOpenHistory = {},
        onEnableCapture = {},
        onOpenSession = {},
        onOpenMetric = {},
    )
}

@AmplyPreview
@Composable
private fun BatteryHubScreenSparsePreview() = PreviewWrapper {
    // Pre-API-34 / sparse OEM: most fields report nothing, and nothing has been recorded yet.
    BatteryHubScreen(
        readout = BatteryReadout(
            levelPercent = 64,
            status = android.os.BatteryManager.BATTERY_STATUS_DISCHARGING,
            plugged = 0,
            health = android.os.BatteryManager.BATTERY_HEALTH_GOOD,
            technology = "Li-ion polymer (high voltage)",
            temperatureTenthsC = 298,
            voltageMillivolts = 3900,
        ),
        captureEnabled = true,
        teaser = ChargeTeaserState.None,
        showProBadge = false,
        onBack = {},
        onOpenHistory = {},
        onEnableCapture = {},
        onOpenSession = {},
        onOpenMetric = {},
    )
}

@Preview(name = "Hub · large font", fontScale = 1.5f, showBackground = true)
@Composable
private fun BatteryHubScreenLargeFontPreview() = PreviewWrapper {
    // The long title plus the History action, and a long technology value — the two places a scaled-up
    // font would clip if the value column weren't weighted.
    BatteryHubScreen(
        readout = previewCharging.copy(technology = "Li-ion polymer (high voltage)"),
        captureEnabled = true,
        teaser = ChargeTeaserState.Last(previewLastSession),
        showProBadge = false,
        onBack = {},
        onOpenHistory = {},
        onEnableCapture = {},
        onOpenSession = {},
        onOpenMetric = {},
        curve = previewCurve,
        availability = CurveMetricAvailability.of(previewCurve),
    )
}
