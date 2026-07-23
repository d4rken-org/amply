package eu.darken.amply.diagnostics.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.twotone.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import eu.darken.amply.R
import eu.darken.amply.charging.core.access.BackendStatus
import eu.darken.amply.common.compose.AmplyCard
import eu.darken.amply.common.compose.AmplyCardDefaults
import eu.darken.amply.common.compose.AmplyCodeBlock
import eu.darken.amply.common.compose.AmplyPreview
import eu.darken.amply.common.compose.PreviewWrapper
import eu.darken.amply.common.compose.asComposable
import eu.darken.amply.common.ca.toCaString
import eu.darken.amply.diagnostics.core.Disclosure
import eu.darken.amply.diagnostics.core.SettingId
import eu.darken.amply.charging.core.access.SettingNamespace

@Composable
fun ContributionWizardScreen(
    state: ContributionUiState,
    onExit: () -> Unit,
    onRefreshStatus: () -> Unit,
    onOpenShizuku: () -> Unit,
    onAllowShizuku: () -> Unit,
    onFeatureNameChange: (String) -> Unit,
    onRomVersionChange: (String) -> Unit,
    onNotesChange: (String) -> Unit,
    onPendingLabelChange: (String) -> Unit,
    onOpenNativeSettings: () -> Unit,
    onCaptureMode: () -> Unit,
    onSetEffect: (Int, String) -> Unit,
    onUndoLast: () -> Unit,
    onRestart: () -> Unit,
    onRevealRow: (SettingId) -> Unit,
    onToggleInclude: (SettingId) -> Unit,
    onNext: () -> Unit,
    onBack: () -> Unit,
    onOpenIssue: () -> Unit,
    onCopyReport: () -> Unit,
    onEmail: () -> Unit,
) {
    LaunchedEffect(Unit) { onRefreshStatus() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.contribution_title)) },
                navigationIcon = {
                    IconButton(onClick = onExit) {
                        Icon(
                            Icons.AutoMirrored.TwoTone.ArrowBack,
                            contentDescription = stringResource(R.string.contribution_back),
                        )
                    }
                },
            )
        },
        bottomBar = {
            WizardBottomBar(
                showBack = state.step != WizardStep.INTRO,
                showNext = state.step != WizardStep.DELIVER,
                nextEnabled = when (state.step) {
                    WizardStep.INTRO -> state.shizukuReady
                    // Not while a capture is in flight — Review must reflect a settled session.
                    WizardStep.CAPTURE -> state.modes.isNotEmpty() && !state.busy
                    else -> true
                },
                onBack = onBack,
                onNext = onNext,
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = padding.calculateTopPadding() + 8.dp,
                bottom = padding.calculateBottomPadding() + 16.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            when (state.step) {
                WizardStep.INTRO -> introStep(state, onOpenShizuku, onAllowShizuku)
                WizardStep.DETAILS -> detailsStep(
                    state,
                    onFeatureNameChange,
                    onRomVersionChange,
                    onNotesChange,
                )
                WizardStep.CAPTURE -> captureStep(
                    state,
                    onPendingLabelChange,
                    onOpenNativeSettings,
                    onCaptureMode,
                    onSetEffect,
                    onUndoLast,
                    onRestart,
                )
                WizardStep.REVIEW -> reviewStep(state, onRevealRow, onToggleInclude)
                WizardStep.DELIVER -> deliverStep(state, onOpenIssue, onCopyReport, onEmail)
            }
        }
    }
}

@Composable
private fun WizardBottomBar(
    showBack: Boolean,
    showNext: Boolean,
    nextEnabled: Boolean,
    onBack: () -> Unit,
    onNext: () -> Unit,
) {
    Surface(tonalElevation = 3.dp) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (showBack) {
                OutlinedButton(onClick = onBack) {
                    Text(stringResource(R.string.contribution_back))
                }
            }
            Spacer(Modifier.weight(1f))
            if (showNext) {
                Button(onClick = onNext, enabled = nextEnabled) {
                    Text(stringResource(R.string.contribution_next))
                }
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleLarge)
}

@Composable
private fun BodyText(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

// region INTRO

private fun LazyListScope.introStep(
    state: ContributionUiState,
    onOpenShizuku: () -> Unit,
    onAllowShizuku: () -> Unit,
) {
    item { ShizukuCard(state.shizuku, onOpenShizuku, onAllowShizuku) }
    item { SectionTitle(stringResource(R.string.contribution_intro_title)) }
    item { BodyText(stringResource(R.string.contribution_intro_body)) }
    item { BodyText(stringResource(R.string.contribution_intro_candidate_note)) }
}

@Composable
private fun ShizukuCard(
    shizuku: BackendStatus?,
    onOpenShizuku: () -> Unit,
    onAllowShizuku: () -> Unit,
) {
    val ready = shizuku?.ready == true
    AmplyCard(verticalArrangement = Arrangement.spacedBy(AmplyCardDefaults.ItemSpacing)) {
        Text(
            stringResource(
                if (ready) {
                    R.string.contribution_shizuku_ready_title
                } else {
                    R.string.contribution_shizuku_required_title
                },
            ),
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            shizuku?.detail?.asComposable() ?: stringResource(R.string.contribution_shizuku_checking),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        when {
            shizuku == null || ready -> Unit
            !shizuku.installed -> {
                Text(
                    stringResource(R.string.contribution_shizuku_not_installed_body),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Button(onClick = onOpenShizuku) {
                    Text(stringResource(R.string.contribution_install_shizuku))
                }
            }
            shizuku.available && !shizuku.granted -> Button(onClick = onAllowShizuku) {
                Text(stringResource(R.string.contribution_allow_shizuku))
            }
            !shizuku.available -> Button(onClick = onOpenShizuku) {
                Text(stringResource(R.string.contribution_open_shizuku))
            }
        }
    }
}

// endregion

// region DETAILS

private fun LazyListScope.detailsStep(
    state: ContributionUiState,
    onFeatureNameChange: (String) -> Unit,
    onRomVersionChange: (String) -> Unit,
    onNotesChange: (String) -> Unit,
) {
    item { SectionTitle(stringResource(R.string.contribution_details_title)) }
    item {
        OutlinedTextField(
            value = state.featureName,
            onValueChange = onFeatureNameChange,
            modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.contribution_feature_label)) },
            placeholder = { Text(stringResource(R.string.contribution_feature_hint)) },
            singleLine = true,
        )
    }
    item {
        OutlinedTextField(
            value = state.romVersion,
            onValueChange = onRomVersionChange,
            modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.contribution_rom_label)) },
            placeholder = { Text(stringResource(R.string.contribution_rom_hint)) },
            singleLine = true,
        )
    }
    item {
        OutlinedTextField(
            value = state.notes,
            onValueChange = onNotesChange,
            modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.contribution_notes_label)) },
            supportingText = { Text(stringResource(R.string.contribution_notes_pii_warning)) },
        )
    }
}

// endregion

// region CAPTURE

private fun LazyListScope.captureStep(
    state: ContributionUiState,
    onPendingLabelChange: (String) -> Unit,
    onOpenNativeSettings: () -> Unit,
    onCaptureMode: () -> Unit,
    onSetEffect: (Int, String) -> Unit,
    onUndoLast: () -> Unit,
    onRestart: () -> Unit,
) {
    item { SectionTitle(stringResource(R.string.contribution_capture_title)) }
    item { BodyText(stringResource(R.string.contribution_capture_instructions)) }
    item {
        OutlinedButton(onClick = onOpenNativeSettings) {
            Text(stringResource(R.string.contribution_open_native))
        }
    }
    item { BodyText(stringResource(R.string.contribution_native_manual)) }
    item {
        OutlinedTextField(
            value = state.pendingLabel,
            onValueChange = onPendingLabelChange,
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text(stringResource(R.string.contribution_mode_label_hint)) },
            singleLine = true,
            isError = state.labelError != null,
            supportingText = state.labelError?.let { { Text(it.asComposable()) } },
        )
    }
    item {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = onCaptureMode, enabled = !state.busy) {
                Text(stringResource(R.string.contribution_capture_action))
            }
            if (state.busy) {
                Spacer(Modifier.size(12.dp))
                CircularProgressIndicator(modifier = Modifier.size(20.dp))
            }
        }
    }
    state.status?.let { status ->
        item { BodyText(status.asComposable()) }
    }
    item {
        Text(
            stringResource(R.string.contribution_modes_captured, state.modes.size),
            style = MaterialTheme.typography.titleMedium,
        )
    }
    itemsIndexedModes(state.modes, onSetEffect)
    item {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = onUndoLast, enabled = state.modes.isNotEmpty()) {
                Text(stringResource(R.string.contribution_undo_last))
            }
            TextButton(onClick = onRestart) {
                Text(stringResource(R.string.contribution_restart))
            }
        }
    }
}

private fun LazyListScope.itemsIndexedModes(
    modes: List<ModeSummary>,
    onSetEffect: (Int, String) -> Unit,
) {
    itemsIndexed(modes) { index, mode -> ModeCard(index, mode, onSetEffect) }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ModeCard(
    index: Int,
    mode: ModeSummary,
    onSetEffect: (Int, String) -> Unit,
) {
    AmplyCard(verticalArrangement = Arrangement.spacedBy(AmplyCardDefaults.ItemSpacing)) {
        Text(mode.label, style = MaterialTheme.typography.titleMedium)
        mode.changedFromPrevious?.let {
            Text(
                stringResource(R.string.contribution_changed_count, it),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            stringResource(R.string.contribution_effect_prompt),
            style = MaterialTheme.typography.bodySmall,
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            EFFECT_CHOICES.forEach { (token, labelRes) ->
                FilterChip(
                    selected = mode.effect == token,
                    onClick = { onSetEffect(index, token) },
                    label = { Text(stringResource(labelRes)) },
                )
            }
        }
    }
}

private val EFFECT_CHOICES = listOf(
    "started" to R.string.contribution_effect_started,
    "stopped" to R.string.contribution_effect_stopped,
    "no_change" to R.string.contribution_effect_nochange,
    "unsure" to R.string.contribution_effect_unsure,
)

// endregion

// region REVIEW

private fun LazyListScope.reviewStep(
    state: ContributionUiState,
    onRevealRow: (SettingId) -> Unit,
    onToggleInclude: (SettingId) -> Unit,
) {
    item { SectionTitle(stringResource(R.string.contribution_review_title)) }
    item { BodyText(stringResource(R.string.contribution_review_body)) }
    if (state.review.isEmpty()) {
        item { BodyText(stringResource(R.string.contribution_review_empty)) }
    } else {
        items(state.review, key = { it.id.display }) { row ->
            ReviewRowCard(row, onRevealRow, onToggleInclude)
        }
    }
}

@Composable
private fun ReviewRowCard(
    row: ReviewRowUi,
    onRevealRow: (SettingId) -> Unit,
    onToggleInclude: (SettingId) -> Unit,
) {
    AmplyCard(verticalArrangement = Arrangement.spacedBy(AmplyCardDefaults.ItemSpacing)) {
        when {
            row.disclosure == Disclosure.AUTO -> {
                Text(row.id.display, style = MaterialTheme.typography.titleSmall)
                Text(
                    stringResource(R.string.contribution_review_auto),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                ReviewValues(row.values)
            }
            !row.revealed -> {
                // Whole row stays hidden until revealed — key name included, since a setting name can itself
                // carry an identifier. Only after Reveal is the name shown (locally) and offered for inclusion.
                Text(
                    stringResource(R.string.contribution_review_hidden),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedButton(onClick = { onRevealRow(row.id) }) {
                    Text(stringResource(R.string.contribution_review_reveal))
                }
            }
            else -> {
                Text(row.id.display, style = MaterialTheme.typography.titleSmall)
                ReviewValues(row.values)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = row.included,
                        onCheckedChange = { onToggleInclude(row.id) },
                    )
                    Text(stringResource(R.string.contribution_review_include))
                }
            }
        }
    }
}

@Composable
private fun ReviewValues(values: List<String?>?) {
    Text(
        values?.joinToString(" | ") { it ?: "—" } ?: "—",
        style = MaterialTheme.typography.bodyMedium,
    )
}

// endregion

// region DELIVER

private fun LazyListScope.deliverStep(
    state: ContributionUiState,
    onOpenIssue: () -> Unit,
    onCopyReport: () -> Unit,
    onEmail: () -> Unit,
) {
    item { SectionTitle(stringResource(R.string.contribution_deliver_title)) }
    item { BodyText(stringResource(R.string.contribution_deliver_preview)) }
    item {
        AmplyCodeBlock(text = state.reportText ?: "", maxHeight = 260.dp)
    }
    if (state.deliveryTooLargeBytes != null) {
        item { BodyText(stringResource(R.string.contribution_too_large)) }
    }
    item {
        Button(onClick = onOpenIssue, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.contribution_open_issue))
        }
    }
    item {
        OutlinedButton(onClick = onCopyReport, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.contribution_copy_report))
        }
    }
    item {
        TextButton(onClick = onEmail, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.contribution_email))
        }
    }
}

// endregion

@AmplyPreview
@Composable
private fun ContributionWizardScreenPreview() = PreviewWrapper {
    ContributionWizardScreen(
        state = ContributionUiState(
            step = WizardStep.CAPTURE,
            shizuku = BackendStatus(available = true, granted = true, detail = "Shizuku connected".toCaString()),
            featureName = "Protect battery",
            romVersion = "HyperOS 2",
            pendingLabel = "Adaptive",
            modes = listOf(
                ModeSummary(label = "Off", effect = "started", changedFromPrevious = null),
                ModeSummary(label = "Maximum 80%", effect = "stopped", changedFromPrevious = 2),
            ),
            review = listOf(
                ReviewRowUi(
                    id = SettingId(SettingNamespace.SECURE, "charge_optimization_mode"),
                    disclosure = Disclosure.AUTO,
                    revealed = true,
                    included = true,
                    values = listOf("0", "1"),
                ),
            ),
        ),
        onExit = {},
        onRefreshStatus = {},
        onOpenShizuku = {},
        onAllowShizuku = {},
        onFeatureNameChange = {},
        onRomVersionChange = {},
        onNotesChange = {},
        onPendingLabelChange = {},
        onOpenNativeSettings = {},
        onCaptureMode = {},
        onSetEffect = { _, _ -> },
        onUndoLast = {},
        onRestart = {},
        onRevealRow = {},
        onToggleInclude = {},
        onNext = {},
        onBack = {},
        onOpenIssue = {},
        onCopyReport = {},
        onEmail = {},
    )
}
