package eu.darken.amply.main.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import eu.darken.amply.charging.core.ChargePolicy
import eu.darken.amply.common.compose.AmplyPreview
import eu.darken.amply.common.compose.PreviewWrapper
import eu.darken.amply.common.settings.QuickActionPolicyPicker
import eu.darken.amply.common.settings.SettingsCategoryHeader
import eu.darken.amply.common.settings.SettingsSwitchItem

/**
 * [canEnableGesture] carries the dashboard card's availability gate (`reconnectSupported && canApply`).
 * Without it this screen would be a way to switch the gesture on for a device where the dashboard
 * correctly forbids it, leaving an "On" preference that either stops immediately or strands a useless
 * foreground monitor. The master row stays interactive while the gesture is already enabled, so an
 * unsupported configuration can always be turned back off.
 *
 * [availablePolicies] is empty until an adapter is resolved, and the notification-button section is
 * shown only where there is something to choose (more than two policies) on a device that can use the
 * gesture at all — the buttons only ever appear on that notification.
 */
@Composable
fun ChargingSettingsScreen(
    gestureEnabled: Boolean,
    anyLevelEnabled: Boolean,
    anyLevelOnly: Boolean,
    canEnableGesture: Boolean,
    availablePolicies: List<ChargePolicy>,
    selectedPolicyIds: List<String>,
    onBack: () -> Unit,
    onGestureEnabledChange: (Boolean) -> Unit,
    onAnyLevelChange: (Boolean) -> Unit,
    onNotificationPolicyToggle: (ChargePolicy, Boolean) -> Unit,
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
        Column(
            Modifier
                .padding(padding)
                .verticalScroll(rememberScrollState()),
        ) {
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
            // On an any-level-only adapter this is not a choice: the limit-hold alternative needs a
            // hardware hold signal the device never reports, so the gesture already runs any-level.
            // Offering a switch that changes nothing (and whose "off" position describes a mode that
            // cannot arm) would be worse than a shorter screen, so the row and its hint go away and
            // a single line states the behaviour instead.
            if (anyLevelOnly) {
                Text(
                    stringResource(R.string.settings_reconnect_any_level_only_hint),
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
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
            if (availablePolicies.size > 2 && masterInteractive) {
                SettingsCategoryHeader(
                    stringResource(R.string.settings_reconnect_notification_actions_category),
                )
                QuickActionPolicyPicker(
                    availablePolicies = availablePolicies,
                    selectedPolicyIds = selectedPolicyIds,
                    onToggle = onNotificationPolicyToggle,
                    // The buttons live on the gesture notification, so there is nothing to pick
                    // while the gesture is off — the section still shows what it would configure.
                    rowsEnabled = gestureEnabled,
                )
                Text(
                    stringResource(R.string.settings_reconnect_notification_actions_hint),
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

// A two-policy adapter: nothing to pick, so the notification-button section stays hidden.
private val binaryPolicies = listOf(ChargePolicy.FixedLimit(80), ChargePolicy.Unrestricted)

@AmplyPreview
@Composable
private fun ChargingSettingsScreenPreview() = PreviewWrapper {
    ChargingSettingsScreen(
        gestureEnabled = true,
        anyLevelEnabled = true,
        anyLevelOnly = false,
        canEnableGesture = true,
        availablePolicies = binaryPolicies,
        selectedPolicyIds = binaryPolicies.map { it.stableId },
        onBack = {},
        onGestureEnabledChange = {},
        onAnyLevelChange = {},
        onNotificationPolicyToggle = { _, _ -> },
    )
}

@AmplyPreview
@Composable
private fun ChargingSettingsScreenDisabledPreview() = PreviewWrapper {
    ChargingSettingsScreen(
        gestureEnabled = false,
        anyLevelEnabled = false,
        anyLevelOnly = false,
        canEnableGesture = true,
        availablePolicies = binaryPolicies,
        selectedPolicyIds = binaryPolicies.map { it.stableId },
        onBack = {},
        onGestureEnabledChange = {},
        onAnyLevelChange = {},
        onNotificationPolicyToggle = { _, _ -> },
    )
}

@AmplyPreview
@Composable
private fun ChargingSettingsScreenUnavailablePreview() = PreviewWrapper {
    ChargingSettingsScreen(
        gestureEnabled = false,
        anyLevelEnabled = false,
        anyLevelOnly = false,
        canEnableGesture = false,
        availablePolicies = emptyList(),
        selectedPolicyIds = emptyList(),
        onBack = {},
        onGestureEnabledChange = {},
        onAnyLevelChange = {},
        onNotificationPolicyToggle = { _, _ -> },
    )
}

/** A policy-rich device: the notification-button picker is only reachable here. */
@AmplyPreview
@Composable
private fun ChargingSettingsScreenWithNotificationButtonsPreview() = PreviewWrapper {
    ChargingSettingsScreen(
        gestureEnabled = true,
        anyLevelEnabled = false,
        anyLevelOnly = false,
        canEnableGesture = true,
        availablePolicies = listOf(
            ChargePolicy.FixedLimit(80),
            ChargePolicy.FixedLimit(90),
            ChargePolicy.Adaptive,
            ChargePolicy.PauseAtFull,
            ChargePolicy.Unrestricted,
        ),
        selectedPolicyIds = listOf("fixed:80", "adaptive", "unrestricted"),
        onBack = {},
        onGestureEnabledChange = {},
        onAnyLevelChange = {},
        onNotificationPolicyToggle = { _, _ -> },
    )
}
