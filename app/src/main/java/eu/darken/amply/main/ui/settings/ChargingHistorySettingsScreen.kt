package eu.darken.amply.main.ui.settings

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.twotone.ArrowBack
import androidx.compose.material.icons.automirrored.twotone.ShowChart
import androidx.compose.material.icons.twotone.Timer
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import eu.darken.amply.R
import eu.darken.amply.common.compose.AmplyPreview
import eu.darken.amply.common.compose.PreviewWrapper
import eu.darken.amply.common.settings.SettingsDivider
import eu.darken.amply.common.settings.SettingsSliderItem
import eu.darken.amply.common.settings.SettingsSwitchItem
import eu.darken.amply.stats.core.StatsRetention

/**
 * The durable controls for charge recording: the on/off switch and how long recorded charges are
 * kept. The hub's card is a one-time opt-in, so this is where recording is turned off again — and the
 * only place the retention window can be changed.
 *
 * The slider stays enabled while recording is off: retention governs data that is already on the
 * device, which switching capture off does not delete.
 */
@Composable
fun ChargingHistorySettingsScreen(
    captureEnabled: Boolean,
    retentionDays: Int,
    onBack: () -> Unit,
    onCaptureEnabledChange: (Boolean) -> Unit,
    onRetentionChange: (Int) -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_charging_history_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.TwoTone.ArrowBack,
                            contentDescription = stringResource(R.string.action_back),
                        )
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(Modifier.padding(padding)) {
            item {
                SettingsSwitchItem(
                    title = stringResource(R.string.stats_capture_title),
                    subtitle = stringResource(R.string.stats_capture_subtitle),
                    checked = captureEnabled,
                    onCheckedChange = onCaptureEnabledChange,
                    icon = Icons.AutoMirrored.TwoTone.ShowChart,
                )
            }
            item { SettingsDivider() }
            item {
                // The slider moves over preset indices; only committed days leave this screen.
                SettingsSliderItem(
                    title = stringResource(R.string.stats_retention_title),
                    valueLabel = { index ->
                        val days = StatsRetention.PRESETS[index]
                        if (StatsRetention.isForever(days)) {
                            stringResource(R.string.stats_retention_forever)
                        } else {
                            stringResource(R.string.stats_retention_value, days)
                        }
                    },
                    value = StatsRetention.presetIndexOf(retentionDays),
                    range = 0..StatsRetention.PRESETS.lastIndex,
                    onValueChange = { index -> onRetentionChange(StatsRetention.PRESETS[index]) },
                    subtitleFor = { index ->
                        if (StatsRetention.isForever(StatsRetention.PRESETS[index])) {
                            stringResource(R.string.stats_retention_footer_forever)
                        } else {
                            stringResource(R.string.stats_retention_footer)
                        }
                    },
                    icon = Icons.TwoTone.Timer,
                )
            }
        }
    }
}

@AmplyPreview
@Composable
private fun ChargingHistorySettingsScreenPreview() = PreviewWrapper {
    ChargingHistorySettingsScreen(
        captureEnabled = true,
        retentionDays = StatsRetention.MIN_DAYS,
        onBack = {},
        onCaptureEnabledChange = {},
        onRetentionChange = {},
    )
}

@AmplyPreview
@Composable
private fun ChargingHistorySettingsScreenOffPreview() = PreviewWrapper {
    ChargingHistorySettingsScreen(
        captureEnabled = false,
        retentionDays = StatsRetention.DEFAULT_DAYS,
        onBack = {},
        onCaptureEnabledChange = {},
        onRetentionChange = {},
    )
}

@AmplyPreview
@Composable
private fun ChargingHistorySettingsScreenForeverPreview() = PreviewWrapper {
    ChargingHistorySettingsScreen(
        captureEnabled = true,
        retentionDays = StatsRetention.FOREVER,
        onBack = {},
        onCaptureEnabledChange = {},
        onRetentionChange = {},
    )
}
