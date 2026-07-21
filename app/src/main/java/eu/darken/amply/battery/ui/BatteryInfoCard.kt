package eu.darken.amply.battery.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.BatteryStd
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import eu.darken.amply.R
import eu.darken.amply.battery.core.BatteryReadout
import eu.darken.amply.common.compose.AmplyPreview
import eu.darken.amply.common.compose.PreviewWrapper

/**
 * Compact, permission-free battery overview shown on the dashboard for every device. Surfaces the
 * few most useful live values and links to [BatteryDetailScreen] for the full set.
 */
@Composable
fun BatteryInfoCard(
    readout: BatteryReadout,
    onOpenDetail: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Default.BatteryStd,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
                Text(
                    stringResource(R.string.battery_info_title),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onOpenDetail) {
                    Text(stringResource(R.string.battery_info_details_action))
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
                }
            }
            Text(
                summaryLine(readout),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun summaryLine(readout: BatteryReadout): String {
    val notReported = stringResource(R.string.battery_value_not_reported)
    val level = readout.levelPercent?.let { "$it%" } ?: notReported
    val status = stringResource(batteryStatusLabel(readout.status))
    val temperature = formatTemperature(readout.temperatureTenthsC) ?: notReported
    return stringResource(R.string.battery_info_summary, level, status, temperature)
}

@AmplyPreview
@Composable
private fun BatteryInfoCardPreview() = PreviewWrapper {
    BatteryInfoCard(
        readout = BatteryReadout(
            levelPercent = 82,
            status = android.os.BatteryManager.BATTERY_STATUS_CHARGING,
            temperatureTenthsC = 314,
            currentNowMicroamps = 1_250_000,
        ),
        onOpenDetail = {},
    )
}
