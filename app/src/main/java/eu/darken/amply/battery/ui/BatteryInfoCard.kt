package eu.darken.amply.battery.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryStd
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import eu.darken.amply.R
import eu.darken.amply.battery.core.BatteryReadout
import eu.darken.amply.common.compose.AmplyNavigationCard
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
    AmplyNavigationCard(
        onClick = onOpenDetail,
        onClickLabel = stringResource(R.string.battery_info_details_action),
        title = stringResource(R.string.battery_info_title),
        icon = Icons.Default.BatteryStd,
        modifier = modifier,
    ) {
        Text(
            summaryLine(readout),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
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
