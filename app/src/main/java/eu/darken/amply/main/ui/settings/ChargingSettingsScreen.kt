package eu.darken.amply.main.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.twotone.ArrowBack
import androidx.compose.material.icons.twotone.BatteryChargingFull
import androidx.compose.material.icons.twotone.Bolt
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import eu.darken.amply.R
import eu.darken.amply.common.compose.AmplyPreview
import eu.darken.amply.common.compose.PreviewWrapper
import eu.darken.amply.common.settings.SettingsCategoryHeader
import eu.darken.amply.common.settings.SettingsSwitchItem

/**
 * [canEnableGesture] carries the dashboard card's availability gate (`reconnectSupported && canApply`).
 * Without it this screen would be a way to switch the gesture on for a device where the dashboard
 * correctly forbids it, leaving an "On" preference that either stops immediately or strands a useless
 * foreground monitor. The master row stays interactive while the gesture is already enabled, so an
 * unsupported configuration can always be turned back off.
 */
@Composable
fun ChargingSettingsScreen(
    gestureEnabled: Boolean,
    anyLevelEnabled: Boolean,
    canEnableGesture: Boolean,
    onBack: () -> Unit,
    onGestureEnabledChange: (Boolean) -> Unit,
    onAnyLevelChange: (Boolean) -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_charging_title)) },
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
        Column(Modifier.padding(padding)) {
            SettingsCategoryHeader(stringResource(R.string.settings_reconnect_category))
            val masterInteractive = gestureEnabled || canEnableGesture
            SettingsSwitchItem(
                title = stringResource(R.string.settings_reconnect_enabled_title),
                subtitle = if (masterInteractive) {
                    stringResource(R.string.settings_reconnect_enabled_body)
                } else {
                    stringResource(R.string.settings_reconnect_enabled_unavailable)
                },
                checked = gestureEnabled,
                onCheckedChange = onGestureEnabledChange,
                icon = Icons.TwoTone.Bolt,
                enabled = masterInteractive,
            )
            SettingsSwitchItem(
                title = stringResource(R.string.settings_reconnect_any_level_title),
                subtitle = stringResource(R.string.settings_reconnect_any_level_body),
                checked = anyLevelEnabled,
                onCheckedChange = onAnyLevelChange,
                icon = Icons.TwoTone.BatteryChargingFull,
                enabled = gestureEnabled,
            )
            Text(
                stringResource(R.string.settings_reconnect_any_level_hint),
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@AmplyPreview
@Composable
private fun ChargingSettingsScreenPreview() = PreviewWrapper {
    ChargingSettingsScreen(
        gestureEnabled = true,
        anyLevelEnabled = true,
        canEnableGesture = true,
        onBack = {},
        onGestureEnabledChange = {},
        onAnyLevelChange = {},
    )
}

@AmplyPreview
@Composable
private fun ChargingSettingsScreenDisabledPreview() = PreviewWrapper {
    ChargingSettingsScreen(
        gestureEnabled = false,
        anyLevelEnabled = false,
        canEnableGesture = true,
        onBack = {},
        onGestureEnabledChange = {},
        onAnyLevelChange = {},
    )
}

@AmplyPreview
@Composable
private fun ChargingSettingsScreenUnavailablePreview() = PreviewWrapper {
    ChargingSettingsScreen(
        gestureEnabled = false,
        anyLevelEnabled = false,
        canEnableGesture = false,
        onBack = {},
        onGestureEnabledChange = {},
        onAnyLevelChange = {},
    )
}
