package eu.darken.amply.main.ui.settings

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.twotone.ArrowBack
import androidx.compose.material.icons.automirrored.twotone.Article
import androidx.compose.material.icons.automirrored.twotone.ContactSupport
import androidx.compose.material.icons.twotone.BugReport
import androidx.compose.material.icons.twotone.Code
import androidx.compose.material.icons.twotone.DeleteSweep
import androidx.compose.material.icons.twotone.Share
import androidx.compose.material.icons.twotone.StopCircle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import eu.darken.amply.common.debug.DebugLogState
import eu.darken.amply.common.settings.SettingsBaseItem
import eu.darken.amply.common.settings.SettingsCategoryHeader
import eu.darken.amply.common.settings.SettingsDivider

@Composable
fun SupportScreen(
    state: DebugLogState,
    onBack: () -> Unit,
    onDocumentation: () -> Unit,
    onIssueTracker: () -> Unit,
    onContact: () -> Unit,
    onStartDebugLog: () -> Unit,
    onStopDebugLog: () -> Unit,
    onShareDebugLog: () -> Unit,
    onClearDebugLogs: () -> Unit,
) {
    var showConsent by remember { mutableStateOf(false) }
    var showClearConfirmation by remember { mutableStateOf(false) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Support") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.TwoTone.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(Modifier.padding(padding)) {
            item {
                SettingsBaseItem(
                    title = "Documentation",
                    subtitle = "Setup and usage guide",
                    icon = Icons.AutoMirrored.TwoTone.Article,
                    onClick = onDocumentation,
                )
            }
            item { SettingsDivider() }
            item {
                SettingsBaseItem(
                    title = "Issue tracker",
                    subtitle = "Report a reproducible problem",
                    icon = Icons.TwoTone.Code,
                    onClick = onIssueTracker,
                )
            }
            item { SettingsDivider() }
            item {
                SettingsBaseItem(
                    title = "Contact developer",
                    subtitle = "support@darken.eu",
                    icon = Icons.AutoMirrored.TwoTone.ContactSupport,
                    onClick = onContact,
                )
            }
            item { SettingsCategoryHeader("Debug") }
            item {
                SettingsBaseItem(
                    title = if (state.recording) "Stop debug log" else "Record debug log",
                    subtitle = if (state.recording) {
                        "Recording app events · reproduce the issue now"
                    } else {
                        "Record detailed app events while reproducing a problem"
                    },
                    icon = if (state.recording) Icons.TwoTone.StopCircle else Icons.TwoTone.BugReport,
                    onClick = {
                        if (state.recording) onStopDebugLog() else showConsent = true
                    },
                )
            }
            item { SettingsDivider() }
            item {
                SettingsBaseItem(
                    title = "Share latest debug log",
                    subtitle = if (state.sessions.isEmpty()) {
                        "No completed recordings"
                    } else {
                        "${state.sessions.size} completed recording${if (state.sessions.size == 1) "" else "s"}"
                    },
                    icon = Icons.TwoTone.Share,
                    onClick = onShareDebugLog,
                )
            }
            item { SettingsDivider() }
            item {
                SettingsBaseItem(
                    title = "Clear debug logs",
                    subtitle = "Delete completed recordings from this device",
                    icon = Icons.TwoTone.DeleteSweep,
                    onClick = { if (state.sessions.isNotEmpty()) showClearConfirmation = true },
                )
            }
        }
    }

    if (showConsent) {
        AlertDialog(
            onDismissRequest = { showConsent = false },
            title = { Text("Record debug log?") },
            text = {
                Text(
                    "Amply will record app events, device model, Android version, and charging-control results. " +
                        "Logs stay on this device until you explicitly share or delete them.",
                )
            },
            confirmButton = {
                TextButton(onClick = { showConsent = false; onStartDebugLog() }) { Text("Start") }
            },
            dismissButton = {
                TextButton(onClick = { showConsent = false }) { Text("Cancel") }
            },
        )
    }

    if (showClearConfirmation) {
        AlertDialog(
            onDismissRequest = { showClearConfirmation = false },
            title = { Text("Delete debug logs?") },
            text = { Text("All completed Amply debug recordings will be removed.") },
            confirmButton = {
                TextButton(onClick = { showClearConfirmation = false; onClearDebugLogs() }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { showClearConfirmation = false }) { Text("Cancel") }
            },
        )
    }
}
