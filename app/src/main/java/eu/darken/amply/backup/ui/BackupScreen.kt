package eu.darken.amply.backup.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.twotone.ArrowBack
import androidx.compose.material.icons.twotone.Close
import androidx.compose.material.icons.twotone.FileOpen
import androidx.compose.material.icons.twotone.NoEncryption
import androidx.compose.material.icons.twotone.Save
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import eu.darken.amply.R
import eu.darken.amply.backup.core.BackupPart
import eu.darken.amply.common.compose.AmplyPreview
import eu.darken.amply.common.compose.PreviewWrapper
import eu.darken.amply.common.settings.SettingsBaseItem
import eu.darken.amply.common.settings.SettingsDivider
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/**
 * Export and import of the portable configuration. The actions are gated in the ViewModel, not
 * here: every tap goes through, and a free user is routed to the upgrade screen from there.
 */
@Composable
fun BackupScreen(
    state: BackupUiState,
    onBack: () -> Unit,
    onExport: () -> Unit,
    onImport: () -> Unit,
    onConfirmImport: () -> Unit,
    onCancelImport: () -> Unit,
    onMessageShown: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_backup_title)) },
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
        // Driven by the state rather than a SnackbarHostState, so the message is part of what the
        // screen renders from its state (and its preview) and is consumed exactly when it goes away.
        snackbarHost = {
            state.message?.let { BackupMessageSnackbar(message = it, onDismiss = onMessageShown) }
        },
    ) { padding ->
        Column(Modifier.padding(padding)) {
            Box(Modifier.fillMaxWidth().height(4.dp)) {
                if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            LazyColumn {
                item {
                    SettingsBaseItem(
                        title = stringResource(R.string.backup_export_title),
                        subtitle = stringResource(R.string.backup_export_subtitle),
                        icon = Icons.TwoTone.Save,
                        enabled = !state.busy,
                        onClick = onExport,
                    )
                }
                item { SettingsDivider() }
                item {
                    SettingsBaseItem(
                        title = stringResource(R.string.backup_import_title),
                        subtitle = stringResource(R.string.backup_import_subtitle),
                        icon = Icons.TwoTone.FileOpen,
                        enabled = !state.busy,
                        onClick = onImport,
                    )
                }
                item { SettingsDivider() }
                item { BackupDisclosure() }
            }
        }
    }

    state.pendingImport?.let { summary ->
        BackupImportDialog(
            summary = summary,
            busy = state.busy,
            onConfirm = onConfirmImport,
            onCancel = onCancelImport,
        )
    }
}

@Composable
private fun BackupDisclosure() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.TwoTone.NoEncryption,
            contentDescription = null,
            modifier = Modifier.size(24.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            stringResource(R.string.backup_disclosure),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun BackupImportDialog(
    summary: BackupImportSummary,
    busy: Boolean,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
) {
    AlertDialog(
        // A confirm in flight cannot be taken back, so the dialog stays until it settles.
        onDismissRequest = { if (!busy) onCancel() },
        title = { Text(stringResource(R.string.backup_import_dialog_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                createdLine(summary)?.let { Text(it) }
                Text(settingsLine(summary.settingsParts))
                summary.ruleCount?.let { count ->
                    Text(
                        if (count == 0) {
                            stringResource(R.string.backup_import_dialog_rules_none)
                        } else {
                            pluralStringResource(R.plurals.backup_import_dialog_rules, count, count)
                        },
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = !busy) {
                Text(stringResource(R.string.backup_import_dialog_confirm_action))
            }
        },
        dismissButton = {
            TextButton(onClick = onCancel, enabled = !busy) {
                Text(stringResource(R.string.action_cancel))
            }
        },
    )
}

@Composable
private fun createdLine(summary: BackupImportSummary): String? {
    val date = summary.createdAt.takeIf { it > 0 }?.let {
        DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM)
            .withZone(ZoneId.systemDefault())
            .format(Instant.ofEpochMilli(it))
    }
    val version = summary.appVersion.takeIf { it.isNotBlank() }
    return when {
        date != null && version != null -> stringResource(R.string.backup_import_dialog_created, date, version)
        date != null -> stringResource(R.string.backup_import_dialog_created_date, date)
        version != null -> stringResource(R.string.backup_import_dialog_created_version, version)
        else -> null
    }
}

@Composable
private fun settingsLine(parts: List<BackupPart>): String {
    if (parts.isEmpty()) return stringResource(R.string.backup_import_dialog_settings_none)
    val separator = stringResource(R.string.backup_import_dialog_settings_separator)
    val labels = parts.mapNotNull { part ->
        when (part) {
            BackupPart.THEME -> R.string.backup_part_theme
            BackupPart.CHARGE_ALARM -> R.string.backup_part_charge_alarm
            BackupPart.RECONNECT_GESTURE -> R.string.backup_part_reconnect_gesture
            BackupPart.NOTIFICATION_BUTTONS -> R.string.backup_part_notification_buttons
            BackupPart.HISTORY_RETENTION -> R.string.backup_part_history_retention
            BackupPart.RULES -> null
        }
    }.map { stringResource(it) }
    return stringResource(R.string.backup_import_dialog_settings, labels.joinToString(separator))
}

@Composable
private fun BackupMessageSnackbar(
    message: BackupMessage,
    onDismiss: () -> Unit,
) {
    val currentOnDismiss by rememberUpdatedState(onDismiss)
    LaunchedEffect(message) {
        // An import summary can run to several sentences, so it gets the longer read.
        delay(if (message is BackupMessage.Imported) LONG_MESSAGE_MILLIS else SHORT_MESSAGE_MILLIS)
        currentOnDismiss()
    }
    Snackbar(
        modifier = Modifier
            .padding(12.dp)
            .semantics { liveRegion = LiveRegionMode.Polite },
        dismissAction = {
            IconButton(onClick = onDismiss) {
                Icon(
                    Icons.TwoTone.Close,
                    contentDescription = stringResource(R.string.backup_message_dismiss_action),
                )
            }
        },
    ) {
        Text(backupMessageText(message))
    }
}

@Composable
private fun backupMessageText(message: BackupMessage): String = when (message) {
    BackupMessage.ExportSaved -> stringResource(R.string.backup_message_export_saved)
    BackupMessage.ExportFailed -> stringResource(R.string.backup_message_export_failed)
    BackupMessage.ReadFailed -> stringResource(R.string.backup_message_read_failed)
    BackupMessage.NotABackup -> stringResource(R.string.backup_message_not_a_backup)
    is BackupMessage.NewerVersion -> stringResource(R.string.backup_message_newer_version, message.version)
    BackupMessage.TooLarge -> stringResource(R.string.backup_message_too_large)
    BackupMessage.ProStatusUnknown -> stringResource(R.string.backup_message_pro_unknown)
    is BackupMessage.Imported -> importedText(message)
}

@Composable
private fun importedText(message: BackupMessage.Imported): String {
    val headline = stringResource(
        if (message.partial) R.string.backup_message_imported_partial else R.string.backup_message_imported,
    )
    val rules = message.rulesImported?.let { count ->
        if (count == 0) {
            stringResource(R.string.backup_message_rules_none)
        } else {
            pluralStringResource(R.plurals.backup_message_rules_imported, count, count)
        }
    }
    val skipped = message.skippedEntries.takeIf { it > 0 }?.let {
        pluralStringResource(R.plurals.backup_message_skipped, it, it)
    }
    val unsupported = message.unsupportedRules.takeIf { it > 0 }?.let {
        pluralStringResource(R.plurals.backup_message_unsupported_rules, it, it)
    }
    val downgraded = if (message.gestureDowngraded) {
        stringResource(R.string.backup_message_gesture_downgraded)
    } else {
        null
    }
    val denied = if (message.notificationsDenied) {
        stringResource(R.string.backup_message_notifications_denied)
    } else {
        null
    }
    return listOfNotNull(headline, rules, skipped, unsupported, downgraded, denied).joinToString(" ")
}

private const val SHORT_MESSAGE_MILLIS = 4_000L
private const val LONG_MESSAGE_MILLIS = 10_000L

@AmplyPreview
@Composable
private fun BackupScreenPreview() = PreviewWrapper {
    BackupScreen(
        state = BackupUiState(),
        onBack = {},
        onExport = {},
        onImport = {},
        onConfirmImport = {},
        onCancelImport = {},
        onMessageShown = {},
    )
}

@AmplyPreview
@Composable
private fun BackupScreenConfirmPreview() = PreviewWrapper {
    BackupScreen(
        state = BackupUiState(
            pendingImport = BackupImportSummary(
                createdAt = 1_790_000_000_000L,
                appVersion = "0.4.0-beta1",
                settingsParts = listOf(
                    BackupPart.THEME,
                    BackupPart.CHARGE_ALARM,
                    BackupPart.RECONNECT_GESTURE,
                    BackupPart.NOTIFICATION_BUTTONS,
                    BackupPart.HISTORY_RETENTION,
                ),
                ruleCount = 3,
            ),
        ),
        onBack = {},
        onExport = {},
        onImport = {},
        onConfirmImport = {},
        onCancelImport = {},
        onMessageShown = {},
    )
}

@AmplyPreview
@Composable
private fun BackupScreenResultPreview() = PreviewWrapper {
    BackupScreen(
        state = BackupUiState(
            message = BackupMessage.Imported(
                rulesImported = 3,
                skippedEntries = 1,
                unsupportedRules = 1,
                gestureDowngraded = true,
                notificationsDenied = false,
                partial = false,
            ),
        ),
        onBack = {},
        onExport = {},
        onImport = {},
        onConfirmImport = {},
        onCancelImport = {},
        onMessageShown = {},
    )
}
