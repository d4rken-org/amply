package eu.darken.amply.main.ui.dashboard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import eu.darken.amply.R
import eu.darken.amply.alarm.core.ChargeAlarmConfig
import eu.darken.amply.common.compose.AmplyCardHeader
import eu.darken.amply.common.compose.AmplyCardToggleIndicator
import eu.darken.amply.common.compose.AmplyPreview
import eu.darken.amply.common.compose.AmplyToggleCard
import eu.darken.amply.common.compose.PreviewWrapper

/**
 * Charge alarm control: a permission-free reminder to unplug at a chosen level. The target slider
 * is always visible (even while disabled) so the user can set it before turning the alarm on — a
 * phone already above the default would otherwise alert before they could adjust it.
 */
@Composable
fun ChargeAlarmCard(
    config: ChargeAlarmConfig,
    notificationsBlocked: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    onTargetChange: (Int) -> Unit,
    onFixNotifications: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Track the slider locally so dragging is smooth; commit only on release to avoid a DataStore
    // write per frame. Re-seed from config when the persisted value changes underneath us.
    var sliderValue by remember(config.targetPercent) {
        mutableFloatStateOf(config.targetPercent.toFloat())
    }

    AmplyToggleCard(
        checked = config.enabled,
        onCheckedChange = onEnabledChange,
        modifier = modifier,
    ) {
        AmplyCardHeader(
            title = stringResource(R.string.dashboard_alarm_title),
            icon = Icons.Default.NotificationsActive,
            trailing = { AmplyCardToggleIndicator(config.enabled) },
        )
        Text(
            stringResource(
                if (config.enabled) R.string.dashboard_alarm_body_on else R.string.dashboard_alarm_body_off,
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            stringResource(R.string.dashboard_alarm_target_label, sliderValue.toInt()),
            style = MaterialTheme.typography.bodyMedium,
        )
        Slider(
            value = sliderValue,
            onValueChange = { sliderValue = it },
            onValueChangeFinished = { onTargetChange(sliderValue.toInt()) },
            valueRange = ChargeAlarmConfig.MIN_TARGET_PERCENT.toFloat()..ChargeAlarmConfig.MAX_TARGET_PERCENT.toFloat(),
            // Range 50..100 in steps of 5 → 9 interior stops.
            steps = ALARM_SLIDER_STEPS,
        )
        if (config.enabled && notificationsBlocked) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Default.WarningAmber,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                )
                Text(
                    stringResource(R.string.dashboard_alarm_notifications_blocked),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onFixNotifications) {
                    Text(stringResource(R.string.dashboard_alarm_notifications_fix_action))
                }
            }
        }
    }
}

private const val ALARM_SLIDER_STEPS = 9

@AmplyPreview
@Composable
private fun ChargeAlarmCardPreview() = PreviewWrapper {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ChargeAlarmCard(
            config = ChargeAlarmConfig(enabled = true, targetPercent = 85),
            notificationsBlocked = false,
            onEnabledChange = {},
            onTargetChange = {},
            onFixNotifications = {},
        )
        ChargeAlarmCard(
            config = ChargeAlarmConfig(enabled = true, targetPercent = 80),
            notificationsBlocked = true,
            onEnabledChange = {},
            onTargetChange = {},
            onFixNotifications = {},
        )
        ChargeAlarmCard(
            config = ChargeAlarmConfig(enabled = false, targetPercent = 80),
            notificationsBlocked = false,
            onEnabledChange = {},
            onTargetChange = {},
            onFixNotifications = {},
        )
    }
}
