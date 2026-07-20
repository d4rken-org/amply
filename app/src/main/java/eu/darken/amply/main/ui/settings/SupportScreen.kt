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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import eu.darken.amply.R
import eu.darken.amply.common.compose.AmplyPreview
import eu.darken.amply.common.compose.PreviewWrapper
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
                title = { Text(stringResource(R.string.settings_support_title)) },
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
        LazyColumn(Modifier.padding(padding)) {
            item {
                SettingsBaseItem(
                    title = stringResource(R.string.settings_support_documentation_title),
                    subtitle = stringResource(R.string.settings_support_documentation_subtitle),
                    icon = Icons.AutoMirrored.TwoTone.Article,
                    onClick = onDocumentation,
                )
            }
            item { SettingsDivider() }
            item {
                SettingsBaseItem(
                    title = stringResource(R.string.settings_support_issue_title),
                    subtitle = stringResource(R.string.settings_support_issue_subtitle),
                    icon = Icons.TwoTone.Code,
                    onClick = onIssueTracker,
                )
            }
            item { SettingsDivider() }
            item {
                SettingsBaseItem(
                    title = stringResource(R.string.settings_support_contact_title),
                    subtitle = stringResource(R.string.settings_support_contact_subtitle),
                    icon = Icons.AutoMirrored.TwoTone.ContactSupport,
                    onClick = onContact,
                )
            }
            item { SettingsCategoryHeader(stringResource(R.string.settings_support_debug_category)) }
            item {
                SettingsBaseItem(
                    title = stringResource(
                        if (state.recording) {
                            R.string.settings_support_record_stop_title
                        } else {
                            R.string.settings_support_record_start_title
                        },
                    ),
                    subtitle = if (state.recording) {
                        stringResource(R.string.settings_support_record_recording_subtitle)
                    } else {
                        stringResource(R.string.settings_support_record_idle_subtitle)
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
                    title = stringResource(R.string.settings_support_share_title),
                    subtitle = if (state.sessions.isEmpty()) {
                        stringResource(R.string.settings_support_share_none)
                    } else {
                        pluralStringResource(
                            R.plurals.settings_support_recordings,
                            state.sessions.size,
                            state.sessions.size,
                        )
                    },
                    icon = Icons.TwoTone.Share,
                    onClick = onShareDebugLog,
                )
            }
            item { SettingsDivider() }
            item {
                SettingsBaseItem(
                    title = stringResource(R.string.settings_support_clear_title),
                    subtitle = stringResource(R.string.settings_support_clear_subtitle),
                    icon = Icons.TwoTone.DeleteSweep,
                    onClick = { if (state.sessions.isNotEmpty()) showClearConfirmation = true },
                )
            }
        }
    }

    if (showConsent) {
        AlertDialog(
            onDismissRequest = { showConsent = false },
            title = { Text(stringResource(R.string.settings_support_consent_title)) },
            text = {
                Text(stringResource(R.string.settings_support_consent_body))
            },
            confirmButton = {
                TextButton(onClick = { showConsent = false; onStartDebugLog() }) {
                    Text(stringResource(R.string.settings_support_consent_start))
                }
            },
            dismissButton = {
                TextButton(onClick = { showConsent = false }) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }

    if (showClearConfirmation) {
        AlertDialog(
            onDismissRequest = { showClearConfirmation = false },
            title = { Text(stringResource(R.string.settings_support_clear_confirm_title)) },
            text = { Text(stringResource(R.string.settings_support_clear_confirm_body)) },
            confirmButton = {
                TextButton(onClick = { showClearConfirmation = false; onClearDebugLogs() }) {
                    Text(stringResource(R.string.settings_support_clear_confirm_action))
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearConfirmation = false }) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }
}

@AmplyPreview
@Composable
private fun SupportScreenPreview() = PreviewWrapper {
    SupportScreen(
        state = DebugLogState(recording = true, currentPath = "debug/logs/session-1.log"),
        onBack = {},
        onDocumentation = {},
        onIssueTracker = {},
        onContact = {},
        onStartDebugLog = {},
        onStopDebugLog = {},
        onShareDebugLog = {},
        onClearDebugLogs = {},
    )
}
