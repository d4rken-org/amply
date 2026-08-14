package eu.darken.amply.main.ui.battery

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ShowChart
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import eu.darken.amply.R
import eu.darken.amply.battery.core.BatteryReadout
import eu.darken.amply.battery.ui.BatteryEffect
import eu.darken.amply.battery.ui.batteryHealthLabel
import eu.darken.amply.battery.ui.batteryPlugLabel
import eu.darken.amply.battery.ui.batteryStatusLabel
import eu.darken.amply.battery.ui.chargePowerFallbackRes
import eu.darken.amply.battery.ui.formatChargeCounter
import eu.darken.amply.battery.ui.formatCurrent
import eu.darken.amply.battery.ui.formatTemperature
import eu.darken.amply.battery.ui.formatVoltage
import eu.darken.amply.common.compose.AmplyCard
import eu.darken.amply.common.compose.AmplyCardDefaults
import eu.darken.amply.common.compose.AmplyPreview
import eu.darken.amply.common.compose.PreviewWrapper
import eu.darken.amply.stats.core.StatsPowerCalculator
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
            item { SectionHeader(stringResource(R.string.battery_hub_section_now)) }
            item { ChargingSection(data) }
            item { HealthSection(data) }
            item { ElectricalSection(data) }
            item { ThermalSection(data) }
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

@Composable
private fun ChargingSection(readout: BatteryReadout) {
    val notReported = stringResource(R.string.battery_value_not_reported)
    DetailSection(stringResource(R.string.battery_detail_section_charging)) {
        DetailRow(
            stringResource(R.string.battery_detail_level),
            readout.levelPercent?.let { "$it%" } ?: notReported,
        )
        DetailRow(
            stringResource(R.string.battery_detail_status),
            stringResource(batteryStatusLabel(readout.status, readout.plugged)),
        )
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
    DetailSection(stringResource(R.string.battery_detail_section_electrical)) {
        DetailRow(
            stringResource(R.string.battery_detail_voltage),
            formatVoltage(readout.voltageMillivolts) ?: notReported,
        )
        DetailRow(
            stringResource(R.string.battery_detail_current),
            formatCurrent(readout.currentNowMicroamps) ?: notReported,
        )
        // Voltage × current above is a magnitude in either direction; this row is the gated charge
        // power, so a discharge draw can never be read here as charge power.
        DetailRow(
            stringResource(R.string.battery_detail_power),
            StatsFormat.power(StatsPowerCalculator.chargeMilliwatts(readout))
                ?: stringResource(chargePowerFallbackRes(BatteryEffect.from(readout))),
        )
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
private fun ThermalSection(readout: BatteryReadout) {
    val notReported = stringResource(R.string.battery_value_not_reported)
    DetailSection(stringResource(R.string.battery_detail_section_thermal)) {
        DetailRow(
            stringResource(R.string.battery_detail_temperature),
            formatTemperature(readout.temperatureTenthsC) ?: notReported,
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
    )
}
