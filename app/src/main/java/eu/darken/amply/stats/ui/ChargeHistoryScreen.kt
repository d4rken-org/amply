package eu.darken.amply.stats.ui

import android.text.format.DateUtils
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
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
import eu.darken.amply.common.compose.AmplyClickableCard
import eu.darken.amply.common.compose.AmplyPreview
import eu.darken.amply.common.compose.PreviewWrapper
import eu.darken.amply.stats.core.ChargeSessionSummary
import eu.darken.amply.stats.core.ChargingType
import eu.darken.amply.stats.core.StatsSealReason

/**
 * The recorded charge sessions, plus the clear-data action. Reached from the battery hub, which owns
 * the capture switch — this screen is only the list, so a user who came here to look at past charges
 * isn't shown a toggle that could wipe out future ones.
 *
 * State-hoisted and previewable. The three [ChargeHistoryState] cases are rendered distinctly: a Room
 * failure must never be presented as "you have no sessions", which would read as data loss.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChargeHistoryScreen(
    state: ChargeHistoryState,
    onBack: () -> Unit,
    onOpenSession: (Long) -> Unit,
    onClearData: () -> Unit,
) {
    var confirmClear by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.charge_history_title)) },
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
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            when (state) {
                ChargeHistoryState.Loading -> item { Notice(stringResource(R.string.charge_history_loading)) }
                ChargeHistoryState.Unavailable -> item {
                    Notice(stringResource(R.string.charge_history_unavailable))
                }
                is ChargeHistoryState.Ready -> if (state.sessions.isEmpty()) {
                    item { Notice(stringResource(R.string.stats_empty)) }
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
private fun Notice(text: String) = Text(
    text,
    style = MaterialTheme.typography.bodyMedium,
    color = MaterialTheme.colorScheme.onSurfaceVariant,
    modifier = Modifier.padding(4.dp),
)

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
private fun ChargeHistoryScreenPreview() = PreviewWrapper {
    ChargeHistoryScreen(
        state = ChargeHistoryState.Ready(
            listOf(
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
        onOpenSession = {},
        onClearData = {},
    )
}

@AmplyPreview
@Composable
private fun ChargeHistoryScreenEmptyPreview() = PreviewWrapper {
    ChargeHistoryScreen(
        state = ChargeHistoryState.Ready(emptyList()),
        onBack = {},
        onOpenSession = {},
        onClearData = {},
    )
}

@AmplyPreview
@Composable
private fun ChargeHistoryScreenUnavailablePreview() = PreviewWrapper {
    // Deliberately distinct from the empty list above — an outage must not read as data loss.
    ChargeHistoryScreen(
        state = ChargeHistoryState.Unavailable,
        onBack = {},
        onOpenSession = {},
        onClearData = {},
    )
}
