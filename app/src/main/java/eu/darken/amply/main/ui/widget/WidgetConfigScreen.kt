package eu.darken.amply.main.ui.widget

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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

/**
 * Picks the persistent-policy buttons of one widget instance.
 *
 * Every state — including the ones with nothing to pick — offers a confirming action, because the
 * AppWidget host discards a widget whose configuration ends in RESULT_CANCELED on API 26–30. The
 * upgrade link is never the only way out of the locked state.
 */
@Composable
fun WidgetConfigScreen(
    state: WidgetConfigState,
    onToggle: (ChargePolicy, Boolean) -> Unit,
    onConfirm: () -> Unit,
    onDone: () -> Unit,
    onRetry: () -> Unit,
    onUpgrade: () -> Unit,
) {
    Scaffold(
        topBar = { TopAppBar(title = { Text(stringResource(R.string.widget_config_title)) }) },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .verticalScroll(rememberScrollState()),
        ) {
            when (state) {
                is WidgetConfigState.Loading -> Message(stringResource(R.string.widget_config_loading))
                is WidgetConfigState.Locked -> Message(stringResource(R.string.widget_config_locked))
                is WidgetConfigState.Error -> Message(stringResource(R.string.widget_config_error))
                is WidgetConfigState.Unavailable -> Message(stringResource(R.string.widget_config_unavailable))
                is WidgetConfigState.NotConfigurable -> Message(
                    stringResource(R.string.widget_config_not_configurable),
                )
                is WidgetConfigState.Ready -> {
                    QuickActionPolicyPicker(
                        availablePolicies = state.availablePolicies,
                        selectedPolicyIds = state.selectedPolicyIds,
                        onToggle = onToggle,
                        rowsEnabled = !state.saving,
                    )
                    Message(stringResource(R.string.widget_config_picker_hint))
                    if (state.saveFailed) {
                        Text(
                            stringResource(R.string.widget_config_save_failed),
                            modifier = Modifier.padding(horizontal = 16.dp),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.End,
            ) {
                if (state is WidgetConfigState.Locked) {
                    TextButton(onClick = onUpgrade) {
                        Text(stringResource(R.string.widget_config_upgrade_action))
                    }
                }
                if (state is WidgetConfigState.Error) {
                    TextButton(onClick = onRetry) {
                        Text(stringResource(R.string.widget_config_retry_action))
                    }
                }
                if (state is WidgetConfigState.Ready) {
                    Button(onClick = onConfirm, enabled = !state.saving) {
                        Text(stringResource(R.string.widget_config_confirm_action))
                    }
                } else {
                    Button(onClick = onDone) {
                        Text(stringResource(R.string.widget_config_done_action))
                    }
                }
            }
        }
    }
}

@Composable
private fun Message(text: String) {
    Text(
        text,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@AmplyPreview
@Composable
private fun WidgetConfigScreenPreview() = PreviewWrapper {
    WidgetConfigScreen(
        state = WidgetConfigState.Ready(
            availablePolicies = listOf(
                ChargePolicy.FixedLimit(80),
                ChargePolicy.FixedLimit(90),
                ChargePolicy.Adaptive,
                ChargePolicy.Unrestricted,
            ),
            selectedPolicyIds = listOf("fixed:80", "unrestricted"),
        ),
        onToggle = { _, _ -> },
        onConfirm = {},
        onDone = {},
        onRetry = {},
        onUpgrade = {},
    )
}

@AmplyPreview
@Composable
private fun WidgetConfigScreenLockedPreview() = PreviewWrapper {
    WidgetConfigScreen(
        state = WidgetConfigState.Locked,
        onToggle = { _, _ -> },
        onConfirm = {},
        onDone = {},
        onRetry = {},
        onUpgrade = {},
    )
}
