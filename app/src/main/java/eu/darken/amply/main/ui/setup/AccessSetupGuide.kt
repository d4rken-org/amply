package eu.darken.amply.main.ui.setup

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Security
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
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
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = if (wssReady) Icons.Default.CheckCircle else Icons.Default.Security,
                    contentDescription = null,
                    tint = if (wssReady) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
                Column {
                    Text(
                        text = if (wssReady) "Control is ready" else "Set up charge control",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = if (wssReady) {
                            "WRITE_SECURE_SETTINGS is granted."
                        } else {
                            "Amply needs one durable Android permission to change the charge policy."
                        },
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }

            if (!wssReady) {
                Spacer(Modifier.height(20.dp))
                Text("Option 1 · Shizuku", style = MaterialTheme.typography.labelLarge)
                Text(
                    "Start Shizuku, allow Amply, then grant the durable permission.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                when {
                    shizukuReady -> Button(onClick = onGrantWss, modifier = Modifier.fillMaxWidth()) {
                        Text("Grant permission with Shizuku")
                    }
                    shizukuRunning -> FilledTonalButton(
                        onClick = onAllowShizuku,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Allow Amply in Shizuku")
                    }
                    else -> FilledTonalButton(
                        onClick = onOpenShizuku,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Open Shizuku")
                    }
                }

                Spacer(Modifier.height(20.dp))
                Text("Option 2 · Computer", style = MaterialTheme.typography.labelLarge)
                Text(
                    "Connect with ADB and run:",
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
                FilledTonalButton(onClick = onCopyAdb, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.ContentCopy, contentDescription = null)
                    Text("Copy ADB command", Modifier.padding(start = 8.dp))
                }
            }
        }
    }
}
