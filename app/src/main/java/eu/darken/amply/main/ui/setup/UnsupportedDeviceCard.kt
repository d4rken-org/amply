package eu.darken.amply.main.ui.setup

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.twotone.OpenInNew
import androidx.compose.material.icons.twotone.ContentCopy
import androidx.compose.material.icons.twotone.Email
import androidx.compose.material.icons.twotone.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import eu.darken.amply.R
import eu.darken.amply.common.theming.AmplyTheme
import eu.darken.amply.common.theming.ThemeMode
import eu.darken.amply.common.theming.ThemeState

/**
 * Shown on the dashboard and during onboarding when a device is unsupported but a useful support
 * contribution (see [eu.darken.amply.charging.core.adapter.AdapterSupport.contributionWanted]).
 * Explains the situation in plain language and offers two low-friction contribution paths: a
 * prefilled public GitHub issue, or a prefilled email (lower barrier, no account needed).
 */
@Composable
fun UnsupportedDeviceCard(
    modifier: Modifier = Modifier,
    manufacturer: String,
    reportPreview: String?,
    onPrepareReport: () -> Unit,
    onCopyReport: () -> Unit,
    onOpenIssue: () -> Unit,
    onEmail: () -> Unit,
    onHelp: () -> Unit,
) {
    var showDialog by remember { mutableStateOf(false) }

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.TwoTone.Info,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = stringResource(R.string.setup_unsupported_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Text(
                text = stringResource(R.string.setup_unsupported_body, manufacturer),
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = stringResource(R.string.setup_unsupported_shares_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(
                onClick = {
                    onPrepareReport()
                    showDialog = true
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.AutoMirrored.TwoTone.OpenInNew, contentDescription = null)
                Text(
                    stringResource(R.string.setup_unsupported_request_action),
                    Modifier.padding(start = 8.dp),
                )
            }

            HorizontalDivider(Modifier.padding(vertical = 4.dp))

            Text(
                text = stringResource(R.string.setup_unsupported_email_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(
                onClick = onEmail,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.TwoTone.Email, contentDescription = null)
                Text(
                    stringResource(R.string.setup_unsupported_email_action),
                    Modifier.padding(start = 8.dp),
                )
            }
            TextButton(onClick = onHelp, modifier = Modifier.align(Alignment.End)) {
                Text(stringResource(R.string.setup_unsupported_help_action))
            }
        }
    }

    if (showDialog) {
        AlertDialog(
            onDismissRequest = { showDialog = false },
            title = { Text(stringResource(R.string.setup_unsupported_dialog_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(R.string.setup_unsupported_dialog_body))
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                        ),
                    ) {
                        SelectionContainer {
                            Text(
                                text = reportPreview ?: "…",
                                modifier = Modifier
                                    .heightIn(max = 220.dp)
                                    .verticalScroll(rememberScrollState())
                                    .padding(12.dp),
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                            )
                        }
                    }
                    TextButton(
                        onClick = onCopyReport,
                        enabled = reportPreview != null,
                        modifier = Modifier.align(Alignment.End),
                    ) {
                        Icon(Icons.TwoTone.ContentCopy, contentDescription = null)
                        Text(
                            stringResource(R.string.setup_unsupported_dialog_copy),
                            Modifier.padding(start = 8.dp),
                        )
                    }
                }
            },
            confirmButton = {
                // Only enable once the exact snapshot shown above is ready, so what opens on GitHub
                // matches the preview the user consented to.
                Button(
                    onClick = {
                        showDialog = false
                        onOpenIssue()
                    },
                    enabled = reportPreview != null,
                ) {
                    Text(stringResource(R.string.setup_unsupported_dialog_open))
                }
            },
            dismissButton = {
                TextButton(onClick = { showDialog = false }) {
                    Text(stringResource(R.string.setup_unsupported_dialog_cancel))
                }
            },
        )
    }
}

private const val PREVIEW_REPORT = "manufacturer=Samsung\nmodel=SM-S911B\nandroid_sdk=34\nadapter=samsung-lab"

@Preview(name = "Unsupported card · light")
@Composable
private fun UnsupportedDeviceCardPreview() {
    AmplyTheme(state = ThemeState(mode = ThemeMode.LIGHT)) {
        UnsupportedDeviceCard(
            modifier = Modifier.padding(16.dp),
            manufacturer = "Samsung",
            reportPreview = PREVIEW_REPORT,
            onPrepareReport = {},
            onCopyReport = {},
            onOpenIssue = {},
            onEmail = {},
            onHelp = {},
        )
    }
}

@Preview(name = "Unsupported card · dark")
@Composable
private fun UnsupportedDeviceCardDarkPreview() {
    AmplyTheme(state = ThemeState(mode = ThemeMode.DARK)) {
        UnsupportedDeviceCard(
            modifier = Modifier.padding(16.dp),
            manufacturer = "Xiaomi",
            reportPreview = PREVIEW_REPORT,
            onPrepareReport = {},
            onCopyReport = {},
            onOpenIssue = {},
            onEmail = {},
            onHelp = {},
        )
    }
}
