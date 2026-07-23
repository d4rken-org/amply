package eu.darken.amply.battery.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
import eu.darken.amply.battery.core.BatteryReadout
import eu.darken.amply.common.compose.AmplyCard
import eu.darken.amply.common.compose.AmplyCardDefaults
import eu.darken.amply.common.compose.AmplyPreview
import eu.darken.amply.common.compose.PreviewWrapper

/**
 * Full read-only battery detail. State-hoisted and permission-free: it renders straight from a
 * [BatteryReadout], so it previews from a fixture and needs no ViewModel. Fields the platform
 * doesn't report render as "Not reported" rather than being hidden.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BatteryDetailScreen(
    readout: BatteryReadout?,
    onBack: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.battery_detail_title)) },
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
        val data = readout ?: BatteryReadout.UNKNOWN
        LazyColumn(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { ChargingSection(data) }
            item { HealthSection(data) }
            item { ElectricalSection(data) }
            item { ThermalSection(data) }
        }
    }
}

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
            stringResource(batteryStatusLabel(readout.status)),
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
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
        )
    }
}

@AmplyPreview
@Composable
private fun BatteryDetailScreenPreview() = PreviewWrapper {
    BatteryDetailScreen(
        readout = BatteryReadout(
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
        ),
        onBack = {},
    )
}

@AmplyPreview
@Composable
private fun BatteryDetailScreenUnknownPreview() = PreviewWrapper {
    // Pre-API-34 / sparse OEM: most fields report nothing.
    BatteryDetailScreen(
        readout = BatteryReadout(
            levelPercent = 64,
            status = android.os.BatteryManager.BATTERY_STATUS_DISCHARGING,
            plugged = 0,
            health = android.os.BatteryManager.BATTERY_HEALTH_GOOD,
            technology = "Li-ion",
            temperatureTenthsC = 298,
            voltageMillivolts = 3900,
        ),
        onBack = {},
    )
}
