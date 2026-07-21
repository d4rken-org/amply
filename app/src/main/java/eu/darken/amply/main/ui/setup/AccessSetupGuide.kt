package eu.darken.amply.main.ui.setup

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import eu.darken.amply.R
import eu.darken.amply.charging.core.ChargeObservation
import eu.darken.amply.charging.core.ChargingState
import eu.darken.amply.charging.core.DeviceInfo
import eu.darken.amply.charging.core.access.AccessSnapshot
import eu.darken.amply.charging.core.access.BackendStatus
import eu.darken.amply.common.ca.toCaString
import eu.darken.amply.common.compose.AmplyPreview
import eu.darken.amply.common.compose.PreviewWrapper
import eu.darken.amply.main.ui.dashboard.DashboardUiState

@Composable
fun AccessSetupGuide(
    state: DashboardUiState,
    adbCommand: String,
    onOpenShizuku: () -> Unit,
    onAllowShizuku: () -> Unit,
    onGrantWss: () -> Unit,
    onCopyAdb: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // A device that fails the adapter gate can never gain control; offering the
    // permission setup would invite a grant that does nothing. Gate on the confirmed
    // observation rather than controlEnabled, whose pre-refresh default is false.
    if (state.charging.observation is ChargeObservation.Unsupported) return

    val access = state.charging.access
    val wssReady = access?.direct?.ready == true
    val shizukuRunning = access?.shizuku?.available == true
    val shizukuReady = access?.shizuku?.ready == true

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (wssReady) {
                MaterialTheme.colorScheme.secondaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceContainerHigh
            },
        ),
    ) {
        Column(Modifier.padding(20.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    // A wrench reads as "setup work"; the shield the status card uses implied protection
                    // was already active. The ready state keeps the check to signal completion.
                    imageVector = if (wssReady) Icons.Default.CheckCircle else Icons.Default.Build,
                    contentDescription = null,
                    tint = if (wssReady) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
                Text(
                    text = if (wssReady) {
                        stringResource(R.string.setup_access_ready_title)
                    } else {
                        stringResource(R.string.setup_access_setup_title)
                    },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(
                text = if (wssReady) {
                    stringResource(R.string.setup_access_ready_body)
                } else {
                    stringResource(R.string.setup_access_setup_body)
                },
                style = MaterialTheme.typography.bodyMedium,
            )

            if (!wssReady) {
                Spacer(Modifier.height(20.dp))
                Text(stringResource(R.string.setup_access_option_shizuku), style = MaterialTheme.typography.labelLarge)
                Text(
                    stringResource(R.string.setup_access_shizuku_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                when {
                    shizukuReady -> Button(
                        onClick = onGrantWss,
                        enabled = !state.charging.grantingWss,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        if (state.charging.grantingWss) {
                            CircularProgressIndicator(
                                // Follow the (disabled) button's content color rather than a fixed onPrimary,
                                // which would under-contrast against the disabled container in light themes.
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                                color = LocalContentColor.current,
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.setup_access_granting_shizuku))
                        } else {
                            Text(stringResource(R.string.setup_access_grant_shizuku))
                        }
                    }
                    shizukuRunning -> FilledTonalButton(
                        onClick = onAllowShizuku,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.setup_access_allow_shizuku))
                    }
                    else -> FilledTonalButton(
                        onClick = onOpenShizuku,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.setup_access_open_shizuku))
                    }
                }

                Spacer(Modifier.height(20.dp))
                Text(stringResource(R.string.setup_access_option_computer), style = MaterialTheme.typography.labelLarge)
                Text(
                    stringResource(R.string.setup_access_adb_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                    SelectionContainer {
                        Text(
                            text = adbCommand,
                            modifier = Modifier.padding(12.dp),
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                }
                Spacer(Modifier.height(4.dp))
                FilledTonalButton(onClick = onCopyAdb, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.ContentCopy, contentDescription = null)
                    Text(stringResource(R.string.setup_access_copy_adb), Modifier.padding(start = 8.dp))
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    // An external `adb pm grant` does not notify this screen, so tell the user how to make
                    // Amply re-check instead of leaving the setup card apparently stuck.
                    stringResource(R.string.setup_access_adb_refresh_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@AmplyPreview
@Composable
private fun AccessSetupGuidePreview() = PreviewWrapper {
    AccessSetupGuide(
        state = DashboardUiState(onboardingComplete = false),
        adbCommand = "adb shell pm grant eu.darken.amply android.permission.WRITE_SECURE_SETTINGS",
        onOpenShizuku = {},
        onAllowShizuku = {},
        onGrantWss = {},
        onCopyAdb = {},
    )
}

@AmplyPreview
@Composable
private fun AccessSetupGuideGrantingPreview() = PreviewWrapper {
    AccessSetupGuide(
        state = DashboardUiState(
            onboardingComplete = false,
            charging = ChargingState(
                device = DeviceInfo("Google", "Pixel 8", 36, "preview"),
                controlEnabled = true,
                access = AccessSnapshot(
                    direct = BackendStatus(available = true, granted = false, detail = "Not granted".toCaString()),
                    shizuku = BackendStatus(available = true, granted = true, detail = "Shizuku connected".toCaString()),
                ),
                grantingWss = true,
            ),
        ),
        adbCommand = "adb shell pm grant eu.darken.amply android.permission.WRITE_SECURE_SETTINGS",
        onOpenShizuku = {},
        onAllowShizuku = {},
        onGrantWss = {},
        onCopyAdb = {},
    )
}
