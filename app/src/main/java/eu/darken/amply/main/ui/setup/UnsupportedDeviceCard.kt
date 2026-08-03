package eu.darken.amply.main.ui.setup

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.twotone.OpenInNew
import androidx.compose.material.icons.twotone.ContentCopy
import androidx.compose.material.icons.twotone.Email
import androidx.compose.material.icons.twotone.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import eu.darken.amply.R
import eu.darken.amply.common.ca.toCaString
import eu.darken.amply.common.compose.AmplyCard
import eu.darken.amply.common.compose.AmplyCardDefaults
import eu.darken.amply.common.compose.AmplyCardHeader
import eu.darken.amply.common.compose.AmplyCardTone
import eu.darken.amply.common.compose.AmplyCodeBlock
import eu.darken.amply.charging.core.access.BackendStatus
import eu.darken.amply.common.compose.AmplyPreview
import eu.darken.amply.common.compose.PreviewWrapper

/**
 * Shown on the dashboard when a device is unsupported but a useful support
 * contribution (see [eu.darken.amply.charging.core.adapter.AdapterSupport.contributionWanted]).
 * Explains the situation in plain language and offers two low-friction contribution paths: a
 * prefilled public GitHub issue, or a prefilled email (lower barrier, no account needed).
 *
 * [platformLabel] names whatever is actually unmapped, which is not always the manufacturer: on a
 * custom ROM the hardware vendor may well be supported already (a LineageOS Pixel would otherwise be
 * told "not mapped for Google devices", which is false), so the caller passes the ROM there instead.
 */
@Composable
fun UnsupportedDeviceCard(
    modifier: Modifier = Modifier,
    platformLabel: String,
    /**
     * Whether the device metadata alone gives a maintainer somewhere to start (see
     * [eu.darken.amply.charging.core.ChargingState.hasSupportLead]). Only then is the metadata-only GitHub
     * path offered — it is the one contribution route that needs no Shizuku, but a report naming no family,
     * ROM marker or key is a public issue nobody can act on. Without a lead the card offers the wizard,
     * which can still discover a key Amply does not know, and email.
     *
     * On LineageOS both are true at once: a lead exists (the lab adapter matches and the settings provider is
     * present), so the metadata path stays offered even though [showGuidedWizard] is false.
     */
    hasSupportLead: Boolean,
    reportPreview: String?,
    showGuidedWizard: Boolean = true,
    /** Null while access is still being probed — renders no action rather than guessing at a state. */
    shizuku: BackendStatus? = null,
    onOpenWizard: () -> Unit,
    onAllowShizuku: () -> Unit = {},
    onOpenShizuku: () -> Unit = {},
    onPrepareReport: () -> Unit,
    onCopyReport: () -> Unit,
    onOpenIssue: () -> Unit,
    onEmail: () -> Unit,
    onHelp: () -> Unit,
) {
    var showDialog by remember { mutableStateOf(false) }

    AmplyCard(
        modifier = modifier,
        tone = AmplyCardTone.SurfaceHigh,
        verticalArrangement = Arrangement.spacedBy(AmplyCardDefaults.ItemSpacing),
    ) {
        AmplyCardHeader(
            title = stringResource(R.string.setup_unsupported_title),
            icon = Icons.TwoTone.Info,
            titleStyle = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
        )
        Text(
            text = stringResource(R.string.setup_unsupported_body, platformLabel),
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            text = stringResource(R.string.setup_unsupported_shares_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        // Primary path: the guided wizard, which produces the far more useful setting-discovery report.
        // Hidden where a settings diff can find nothing (see AdapterSupport.guidedCaptureUseful) — offering it
        // there would walk the user through a capture that always diffs to empty and cannot be delivered.
        if (showGuidedWizard) {
            Button(
                onClick = onOpenWizard,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.AutoMirrored.TwoTone.OpenInNew, contentDescription = null)
                Text(
                    stringResource(R.string.setup_unsupported_wizard_action),
                    Modifier.padding(start = 8.dp),
                )
            }
        }
        // Where the guided wizard is withheld it was also the only place these users could grant Shizuku — and the
        // probe that enriches their report needs it. The action must match the actual state: requesting permission
        // is a no-op while the server is stopped (ShizukuController returns false immediately), so a stopped or
        // absent Shizuku has to send the user to the app instead of showing a button that silently fails.
        if (!showGuidedWizard && shizuku?.ready != true) {
            Text(
                text = stringResource(R.string.setup_unsupported_probe_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            when {
                shizuku == null -> Unit
                !shizuku.installed -> OutlinedButton(
                    onClick = onOpenShizuku,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.contribution_install_shizuku))
                }
                shizuku.available && !shizuku.granted -> OutlinedButton(
                    onClick = onAllowShizuku,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.setup_unsupported_allow_shizuku_action))
                }
                else -> OutlinedButton(
                    onClick = onOpenShizuku,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.contribution_open_shizuku))
                }
            }
        }
        // Secondary: send just the non-privileged device metadata (no Shizuku needed). Offered only where
        // that metadata identifies something — see [hasSupportLead].
        if (hasSupportLead) {
            OutlinedButton(
                onClick = {
                    onPrepareReport()
                    showDialog = true
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.setup_unsupported_request_action))
            }
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

    // Also gated, not just its launcher: a lead that disappears under an open dialog must take the
    // confirmation with it rather than leaving an Open-GitHub button behind.
    if (showDialog && hasSupportLead) {
        AlertDialog(
            onDismissRequest = { showDialog = false },
            title = { Text(stringResource(R.string.setup_unsupported_dialog_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(R.string.setup_unsupported_dialog_body))
                    AmplyCodeBlock(
                        text = reportPreview ?: "…",
                        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                        maxHeight = 220.dp,
                    )
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

@AmplyPreview
@Composable
private fun UnsupportedDeviceCardPreview() = PreviewWrapper {
    UnsupportedDeviceCard(
        modifier = Modifier.padding(16.dp),
        platformLabel = "Samsung",
        hasSupportLead = true,
        reportPreview = PREVIEW_REPORT,
        onOpenWizard = {},
        onPrepareReport = {},
        onCopyReport = {},
        onOpenIssue = {},
        onEmail = {},
        onHelp = {},
    )
}

/** A device whose metadata carries no lead at all: the metadata-only GitHub path is not offered. */
@AmplyPreview
@Composable
private fun UnsupportedDeviceCardNoLeadPreview() = PreviewWrapper {
    UnsupportedDeviceCard(
        modifier = Modifier.padding(16.dp),
        platformLabel = "BLU",
        hasSupportLead = false,
        reportPreview = null,
        onOpenWizard = {},
        onPrepareReport = {},
        onCopyReport = {},
        onOpenIssue = {},
        onEmail = {},
        onHelp = {},
    )
}

private const val PREVIEW_LINEAGE_REPORT =
    "manufacturer=Google\nmodel=Pixel 6\nis_lineageos=true\nlineage_cc_limit_mechanism=NOT_OBSERVED"

/**
 * The custom-ROM shape: a lead exists (lab adapter matched, settings provider present) so the metadata path is
 * offered, but the guided wizard is withheld — a settings diff can discover nothing there — which leaves this
 * card carrying the Shizuku connect step itself. Neither preview above renders that combination.
 */
@AmplyPreview
@Composable
private fun UnsupportedDeviceCardLineagePreview() = PreviewWrapper {
    UnsupportedDeviceCard(
        modifier = Modifier.padding(16.dp),
        platformLabel = "LineageOS",
        hasSupportLead = true,
        reportPreview = PREVIEW_LINEAGE_REPORT,
        showGuidedWizard = false,
        shizuku = BackendStatus(
            available = false,
            granted = false,
            installed = true,
            detail = "Shizuku is not running".toCaString(),
        ),
        onOpenWizard = {},
        onPrepareReport = {},
        onCopyReport = {},
        onOpenIssue = {},
        onEmail = {},
        onHelp = {},
    )
}
