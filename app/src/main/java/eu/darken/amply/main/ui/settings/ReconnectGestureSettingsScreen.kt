package eu.darken.amply.main.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.twotone.ArrowBack
import androidx.compose.material.icons.twotone.BatteryChargingFull
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

@Composable
fun ReconnectGestureSettingsScreen(
    gestureEnabled: Boolean,
    anyLevelEnabled: Boolean,
    onBack: () -> Unit,
    onAnyLevelChange: (Boolean) -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_reconnect_title)) },
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
private fun ReconnectGestureSettingsScreenPreview() = PreviewWrapper {
    ReconnectGestureSettingsScreen(
        gestureEnabled = true,
        anyLevelEnabled = true,
        onBack = {},
        onAnyLevelChange = {},
    )
}

@AmplyPreview
@Composable
private fun ReconnectGestureSettingsScreenDisabledPreview() = PreviewWrapper {
    ReconnectGestureSettingsScreen(
        gestureEnabled = false,
        anyLevelEnabled = false,
        onBack = {},
        onAnyLevelChange = {},
    )
}
