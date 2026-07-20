package eu.darken.amply.diagnostics.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.twotone.ArrowBack
import androidx.compose.material.icons.automirrored.twotone.CompareArrows
import androidx.compose.material.icons.automirrored.twotone.OpenInNew
import androidx.compose.material.icons.twotone.CheckCircle
import androidx.compose.material.icons.twotone.Refresh
import androidx.compose.material.icons.twotone.Save
import androidx.compose.material.icons.twotone.Security
import androidx.compose.material.icons.twotone.Share
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import eu.darken.amply.R
import eu.darken.amply.charging.core.access.BackendStatus
import eu.darken.amply.common.ca.toCaString
import eu.darken.amply.common.compose.AmplyPreview
import eu.darken.amply.common.compose.PreviewWrapper
import eu.darken.amply.common.compose.asComposable
import eu.darken.amply.common.settings.SettingsBaseItem
import eu.darken.amply.common.settings.SettingsCategoryHeader
import eu.darken.amply.common.settings.SettingsDivider

@Composable
fun DiagnosticsScreen(
    state: DiagnosticsUiState,
    shizuku: BackendStatus?,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onOpenShizuku: () -> Unit,
    onAllowShizuku: () -> Unit,
    onCapture: () -> Unit,
    onOpenNativeSettings: () -> Unit,
    onCompare: () -> Unit,
    onShare: (String) -> Unit,
) {
    val ready = shizuku?.ready == true
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_diagnostics_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.TwoTone.ArrowBack,
                            contentDescription = stringResource(R.string.action_back),
                        )
                    }
                },
                actions = {
                    IconButton(onClick = onRefresh) {
                        Icon(Icons.TwoTone.Refresh, contentDescription = stringResource(R.string.action_refresh))
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(Modifier.padding(padding)) {
            item { SettingsCategoryHeader(stringResource(R.string.diagnostics_category_access)) }
            item {
                ShizukuAccessCard(
                    status = shizuku,
                    onOpenShizuku = onOpenShizuku,
                    onAllowShizuku = onAllowShizuku,
                )
            }

            item { SettingsCategoryHeader(stringResource(R.string.diagnostics_category_discovery)) }
            item {
                Text(
                    text = stringResource(R.string.diagnostics_discovery_hint),
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            item {
                SettingsBaseItem(
                    title = stringResource(R.string.diagnostics_step_capture_title),
                    subtitle = stringResource(R.string.diagnostics_step_capture_subtitle),
                    icon = if (state.baselineCaptured) Icons.TwoTone.CheckCircle else Icons.TwoTone.Save,
                    enabled = ready && !state.busy,
                    onClick = onCapture,
                )
            }
            item { SettingsDivider() }
            item {
                SettingsBaseItem(
                    title = stringResource(R.string.diagnostics_step_change_title),
                    subtitle = stringResource(R.string.diagnostics_step_change_subtitle),
                    icon = Icons.AutoMirrored.TwoTone.OpenInNew,
                    enabled = ready && state.baselineCaptured && !state.busy,
                    onClick = onOpenNativeSettings,
                )
            }
            item { SettingsDivider() }
            item {
                SettingsBaseItem(
                    title = stringResource(R.string.diagnostics_step_compare_title),
                    subtitle = stringResource(R.string.diagnostics_step_compare_subtitle),
                    icon = Icons.AutoMirrored.TwoTone.CompareArrows,
                    enabled = ready && state.baselineCaptured && !state.busy,
                    onClick = onCompare,
                )
            }
            item {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainer,
                    ),
                ) {
                    Text(
                        text = state.status.asComposable(),
                        modifier = Modifier.padding(16.dp),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }

            state.report?.let { report ->
                item { SettingsCategoryHeader(stringResource(R.string.diagnostics_category_result)) }
                item {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                        ),
                    ) {
                        SelectionContainer {
                            Text(
                                text = report,
                                modifier = Modifier.padding(16.dp),
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                            )
                        }
                    }
                }
                item {
                    Button(
                        onClick = { onShare(report) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                    ) {
                        Icon(Icons.TwoTone.Share, contentDescription = null)
                        Text(stringResource(R.string.diagnostics_share), Modifier.padding(start = 8.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun ShizukuAccessCard(
    status: BackendStatus?,
    onOpenShizuku: () -> Unit,
    onAllowShizuku: () -> Unit,
) {
    val ready = status?.ready == true
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (ready) {
                MaterialTheme.colorScheme.secondaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceContainerHigh
            },
        ),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    if (ready) Icons.TwoTone.CheckCircle else Icons.TwoTone.Security,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
                Column(Modifier.weight(1f)) {
                    Text(
                        if (ready) {
                            stringResource(R.string.diagnostics_shizuku_ready_title)
                        } else {
                            stringResource(R.string.diagnostics_shizuku_required_title)
                        },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        status?.detail?.asComposable() ?: stringResource(R.string.diagnostics_shizuku_checking),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            when {
                status == null || ready -> Unit
                status.available -> Button(
                    onClick = onAllowShizuku,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.diagnostics_allow_shizuku))
                }
                else -> Button(
                    onClick = onOpenShizuku,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.diagnostics_open_shizuku))
                }
            }
        }
    }
}

@AmplyPreview
@Composable
private fun DiagnosticsScreenPreview() = PreviewWrapper {
    DiagnosticsScreen(
        state = DiagnosticsUiState(
            baselineCaptured = true,
            status = R.string.diagnostics_status_compare_complete.toCaString(),
            report = "secure/charge_optimization_mode: 0 → 1\nsecure/adaptive_charging_enabled: 1 → 0",
        ),
        shizuku = BackendStatus(available = true, granted = true, detail = "Shizuku connected".toCaString()),
        onBack = {},
        onRefresh = {},
        onOpenShizuku = {},
        onAllowShizuku = {},
        onCapture = {},
        onOpenNativeSettings = {},
        onCompare = {},
        onShare = {},
    )
}
