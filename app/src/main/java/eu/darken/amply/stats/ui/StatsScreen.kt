package eu.darken.amply.stats.ui

import android.text.format.DateUtils
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import eu.darken.amply.R
import eu.darken.amply.common.compose.AmplyCardHeader
import eu.darken.amply.common.compose.AmplyCardToggleIndicator
import eu.darken.amply.common.compose.AmplyClickableCard
import eu.darken.amply.common.compose.AmplyPreview
import eu.darken.amply.common.compose.AmplyToggleCard
import eu.darken.amply.common.compose.PreviewWrapper
import eu.darken.amply.stats.core.ChargeSessionSummary
import eu.darken.amply.stats.core.ChargingType
import eu.darken.amply.stats.core.StatsSealReason

/**
 * Battery-statistics home: the opt-in capture switch (with the always-on persistent-notification
 * caveat spelled out), a clear-data action, and the list of recorded charge sessions. State-hoisted
 * and previewable. Enabling is routed by the caller through the notification-permission flow.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatsScreen(
    state: StatsUiState,
    onBack: () -> Unit,
    onCaptureEnabledChange: (Boolean) -> Unit,
    onOpenSession: (Long) -> Unit,
    onClearData: () -> Unit,
) {
    var confirmClear by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.stats_title)) },
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
        LazyColumn(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                CaptureCard(
                    enabled = state.captureEnabled,
                    lastCaptureWallMillis = state.lastCaptureWallMillis,
                    onCaptureEnabledChange = onCaptureEnabledChange,
                )
            }
            item {
                Text(
                    stringResource(R.string.stats_sessions_header),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 4.dp, top = 4.dp),
                )
            }
            if (state.sessions.isEmpty()) {
                item {
                    Text(
                        stringResource(R.string.stats_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(4.dp),
                    )
                }
            } else {
                items(state.sessions, key = { it.id }) { session ->
                    SessionRow(session = session, onClick = { onOpenSession(session.id) })
                }
                item {
                    TextButton(
                        onClick = { confirmClear = true },
                        modifier = Modifier.padding(top = 4.dp),
                    ) {
                        Icon(Icons.Default.DeleteOutline, contentDescription = null)
                        Text(
                            stringResource(R.string.stats_clear_action),
                            modifier = Modifier.padding(start = 8.dp),
                        )
                    }
                }
            }
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text(stringResource(R.string.stats_clear_confirm_title)) },
            text = { Text(stringResource(R.string.stats_clear_confirm_message)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmClear = false
                    onClearData()
                }) {
                    Text(stringResource(R.string.stats_clear_confirm_positive))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) {
                    Text(stringResource(R.string.stats_action_cancel))
                }
            },
        )
    }
}

@Composable
private fun CaptureCard(
    enabled: Boolean,
    lastCaptureWallMillis: Long?,
    onCaptureEnabledChange: (Boolean) -> Unit,
) {
    AmplyToggleCard(checked = enabled, onCheckedChange = onCaptureEnabledChange) {
        AmplyCardHeader(
            title = stringResource(R.string.stats_capture_title),
            trailing = { AmplyCardToggleIndicator(enabled) },
        )
        Text(
            stringResource(R.string.stats_capture_subtitle),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (enabled) {
            val lastText = lastCaptureWallMillis?.let {
                DateUtils.getRelativeTimeSpanString(it).toString()
            } ?: stringResource(R.string.stats_capture_never)
            Text(
                stringResource(R.string.stats_capture_last_recorded, lastText),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SessionRow(session: ChargeSessionSummary, onClick: () -> Unit) {
    AmplyClickableCard(
        onClick = onClick,
        onClickLabel = stringResource(R.string.stats_session_open_action),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    DateUtils.getRelativeTimeSpanString(session.startedAtWallMillis).toString(),
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    buildString {
                        append(StatsFormat.percentRange(session.startPercent, session.endPercent))
                        StatsFormat.duration(session.durationMillis)?.let { append("  ·  $it") }
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                val tags = buildList {
                    if (session.limitHit) add(stringResource(R.string.stats_session_limit_likely))
                    if (session.partial) add(stringResource(R.string.stats_session_partial))
                }
                if (tags.isNotEmpty()) {
                    Text(
                        tags.joinToString("  ·  "),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@AmplyPreview
@Composable
private fun StatsScreenPreview() = PreviewWrapper {
    StatsScreen(
        state = StatsUiState(
            captureEnabled = true,
            lastCaptureWallMillis = System.currentTimeMillis() - 120_000,
            sessions = listOf(
                ChargeSessionSummary(
                    id = 1,
                    startedAtWallMillis = System.currentTimeMillis() - 7_200_000,
                    endedAtWallMillis = System.currentTimeMillis() - 3_600_000,
                    durationMillis = 3_600_000,
                    startPercent = 42,
                    endPercent = 100,
                    chargingType = ChargingType.AC,
                    avgPowerMilliwatts = 12_000,
                    peakPowerMilliwatts = 27_000,
                    minTemperatureTenthsC = 280,
                    avgTemperatureTenthsC = 305,
                    maxTemperatureTenthsC = 330,
                    limitHit = false,
                    partial = false,
                    fullReachedAtWallMillis = System.currentTimeMillis() - 3_650_000,
                    sealReason = StatsSealReason.UNPLUGGED,
                ),
                ChargeSessionSummary(
                    id = 2,
                    startedAtWallMillis = System.currentTimeMillis() - 90_000_000,
                    endedAtWallMillis = System.currentTimeMillis() - 88_000_000,
                    durationMillis = 2_000_000,
                    startPercent = 62,
                    endPercent = 80,
                    chargingType = ChargingType.WIRELESS,
                    avgPowerMilliwatts = 6_500,
                    peakPowerMilliwatts = 9_000,
                    minTemperatureTenthsC = 300,
                    avgTemperatureTenthsC = 320,
                    maxTemperatureTenthsC = 350,
                    limitHit = true,
                    partial = true,
                    fullReachedAtWallMillis = null,
                    sealReason = StatsSealReason.UNPLUGGED,
                ),
            ),
        ),
        onBack = {},
        onCaptureEnabledChange = {},
        onOpenSession = {},
        onClearData = {},
    )
}

@AmplyPreview
@Composable
private fun StatsScreenEmptyPreview() = PreviewWrapper {
    StatsScreen(
        state = StatsUiState(captureEnabled = false),
        onBack = {},
        onCaptureEnabledChange = {},
        onOpenSession = {},
        onClearData = {},
    )
}
