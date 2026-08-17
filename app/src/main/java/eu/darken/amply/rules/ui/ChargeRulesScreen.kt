package eu.darken.amply.rules.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.twotone.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import eu.darken.amply.R
import eu.darken.amply.charging.core.ChargePolicy
import eu.darken.amply.common.compose.AmplyCard
import eu.darken.amply.common.compose.AmplyCardDefaults
import eu.darken.amply.common.compose.AmplyCardTone
import eu.darken.amply.common.compose.AmplyPreview
import eu.darken.amply.common.compose.PreviewWrapper
import eu.darken.amply.common.compose.asComposable
import eu.darken.amply.rules.core.ChargeRule
import eu.darken.amply.rules.core.PlugKind
import eu.darken.amply.rules.core.RuleCondition
import eu.darken.amply.rules.core.policy

/**
 * The ordered rule list. Priority is the order itself — the topmost matching rule wins, across both
 * kinds — so reordering is the whole priority editor and needs no separate concept.
 */
@Composable
fun ChargeRulesScreen(
    state: ChargeRulesUiState,
    onBack: () -> Unit,
    onAdd: () -> Unit,
    onEdit: (String) -> Unit,
    onDelete: (String) -> Unit,
    onEnabledChange: (String, Boolean) -> Unit,
    onMove: (String, Boolean) -> Unit,
    onFixBluetoothPermission: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.rules_title)) },
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
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onAdd,
                icon = { Icon(Icons.Default.Add, contentDescription = null) },
                text = { Text(stringResource(R.string.rules_add_action)) },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (state.applyFailed) {
                item(key = "rules.failure") {
                    NoticeCard(
                        text = stringResource(R.string.rules_failure_warning),
                        tone = AmplyCardTone.SurfaceContainer,
                        error = true,
                    )
                }
            }
            if (state.bluetoothPermissionMissing) {
                item(key = "rules.btpermission") {
                    NoticeCard(
                        text = stringResource(R.string.rules_bluetooth_permission_warning),
                        tone = AmplyCardTone.SurfaceContainer,
                        error = true,
                        action = {
                            TextButton(onClick = onFixBluetoothPermission) {
                                Text(stringResource(R.string.rules_editor_bluetooth_permission_action))
                            }
                        },
                    )
                }
            }
            if (state.rows.isEmpty()) {
                item(key = "rules.empty") {
                    AmplyCard(verticalArrangement = Arrangement.spacedBy(AmplyCardDefaults.ItemSpacing)) {
                        Text(
                            stringResource(R.string.rules_empty_title),
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            stringResource(R.string.rules_empty_body),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Button(onClick = onAdd, modifier = Modifier.fillMaxWidth()) {
                            Text(stringResource(R.string.rules_add_action))
                        }
                    }
                }
            } else {
                if (state.rows.size > 1) {
                    item(key = "rules.priority") {
                        InfoNote(
                            icon = Icons.Default.SwapVert,
                            text = stringResource(R.string.rules_priority_note),
                        )
                    }
                }
                for (row in state.rows) {
                    item(key = "rules.row.${row.rule.id}") {
                        ChargeRuleCard(
                            row = row,
                            onEdit = { onEdit(row.rule.id) },
                            onDelete = { onDelete(row.rule.id) },
                            onEnabledChange = { onEnabledChange(row.rule.id, it) },
                            onMove = { up -> onMove(row.rule.id, up) },
                        )
                    }
                }
                item(key = "rules.footnote") {
                    InfoNote(
                        icon = Icons.Outlined.Info,
                        text = stringResource(R.string.rules_notification_footnote),
                    )
                }
            }
        }
    }
}

@Composable
private fun ChargeRuleCard(
    row: ChargeRuleRow,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onEnabledChange: (Boolean) -> Unit,
    onMove: (Boolean) -> Unit,
) {
    val rule = row.rule
    // Transient dialog visibility only, like the editor's overflow: deletion from the list is just
    // as destructive as from the editor and gets the same confirmation.
    var deleteConfirmOpen by remember { mutableStateOf(false) }
    AmplyCard(
        // The winner is the one rule actually doing something; everything below it is standing by.
        tone = if (row.active) AmplyCardTone.SecondaryContainer else AmplyCardTone.Default,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        // Status reads as an eyebrow above the title — the M3 overline slot — not as a loose line
        // in the card body. Only the active card pays the extra height.
        if (row.active) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Default.Bolt,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
                Text(
                    stringResource(R.string.rules_active_marker),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                rule.kindIcon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
            Column(Modifier.weight(1f)) {
                Text(rule.displayTitle(), style = MaterialTheme.typography.titleMedium)
                // One summary line: condition and effect together. When the title already IS the
                // condition (unnamed rule), repeating it would say nothing — show just the effect.
                val policyLabel = row.policy?.label?.asComposable()
                val summary = when {
                    policyLabel == null -> rule.conditionSummary()
                    rule.label.isBlank() -> policyLabel
                    else -> stringResource(R.string.rules_row_summary, rule.conditionSummary(), policyLabel)
                }
                Text(
                    summary,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = rule.enabled, onCheckedChange = onEnabledChange)
        }
        // Stated on the row rather than hidden: a condition that cannot act here would otherwise
        // look switched on and simply never do anything.
        if (row.unsupportedCondition) {
            WarningLine(stringResource(R.string.rules_condition_unsupported))
        }
        if (row.unsupportedPolicy) {
            WarningLine(stringResource(R.string.rules_policy_unavailable))
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { onMove(true) }, enabled = row.canMoveUp) {
                Icon(
                    Icons.Default.KeyboardArrowUp,
                    contentDescription = stringResource(R.string.rules_action_move_up),
                )
            }
            IconButton(onClick = { onMove(false) }, enabled = row.canMoveDown) {
                Icon(
                    Icons.Default.KeyboardArrowDown,
                    contentDescription = stringResource(R.string.rules_action_move_down),
                )
            }
            Spacer(Modifier.weight(1f))
            IconButton(onClick = onEdit) {
                Icon(
                    Icons.Default.Edit,
                    contentDescription = stringResource(R.string.rules_action_edit),
                )
            }
            IconButton(onClick = { deleteConfirmOpen = true }) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = stringResource(R.string.rules_action_delete),
                )
            }
        }
    }
    if (deleteConfirmOpen) {
        AlertDialog(
            onDismissRequest = { deleteConfirmOpen = false },
            title = { Text(stringResource(R.string.rules_editor_delete_title)) },
            text = { Text(stringResource(R.string.rules_editor_delete_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        deleteConfirmOpen = false
                        onDelete()
                    },
                ) {
                    Text(stringResource(R.string.rules_action_delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteConfirmOpen = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }
}

/**
 * A quiet contextual note between cards. The icon and the card-content alignment are what keep it
 * from reading as a stray string: it sits indented to the same left edge as the card interiors, so
 * it belongs to the list instead of floating beside it.
 */
@Composable
private fun InfoNote(
    icon: ImageVector,
    text: String,
) {
    Row(
        modifier = Modifier.padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            icon,
            contentDescription = null,
            modifier = Modifier.size(16.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun WarningLine(text: String) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Default.WarningAmber,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
            tint = MaterialTheme.colorScheme.error,
        )
        Text(
            text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
    }
}

@Composable
private fun NoticeCard(
    text: String,
    tone: AmplyCardTone,
    error: Boolean,
    action: (@Composable () -> Unit)? = null,
) {
    AmplyCard(tone = tone) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Default.WarningAmber,
                contentDescription = null,
                tint = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.tertiary,
            )
            Text(
                text,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodySmall,
                color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
            )
            action?.invoke()
        }
    }
}

private fun previewRow(
    id: String,
    label: String,
    condition: RuleCondition,
    policy: ChargePolicy,
    enabled: Boolean = true,
    active: Boolean = false,
    unsupportedCondition: Boolean = false,
    canMoveUp: Boolean = true,
    canMoveDown: Boolean = true,
): ChargeRuleRow {
    val rule = ChargeRule(
        id = id,
        enabled = enabled,
        label = label,
        condition = condition,
        policyId = policy.stableId,
    )
    return ChargeRuleRow(
        rule = rule,
        policy = rule.policy,
        active = active,
        unsupportedCondition = unsupportedCondition,
        unsupportedPolicy = false,
        canMoveUp = canMoveUp,
        canMoveDown = canMoveDown,
    )
}

@AmplyPreview
@Composable
private fun ChargeRulesScreenPreview() = PreviewWrapper {
    ChargeRulesScreen(
        state = ChargeRulesUiState(
            rows = listOf(
                previewRow(
                    id = "car",
                    label = "Car",
                    condition = RuleCondition.BluetoothDevice("AA:BB:CC:DD:EE:FF", "Car audio"),
                    policy = ChargePolicy.Unrestricted,
                    active = true,
                    canMoveUp = false,
                ),
                previewRow(
                    id = "desk",
                    label = "Desk dock",
                    condition = RuleCondition.ChargerType(setOf(PlugKind.DOCK, PlugKind.WIRELESS)),
                    policy = ChargePolicy.FixedLimit(80),
                    unsupportedCondition = true,
                ),
                previewRow(
                    id = "night",
                    label = "",
                    condition = RuleCondition.BluetoothDevice("11:22:33:44:55:66", "Bedside speaker"),
                    policy = ChargePolicy.Adaptive,
                    enabled = false,
                    canMoveDown = false,
                ),
            ),
            applyFailed = true,
        ),
        onBack = {},
        onAdd = {},
        onEdit = {},
        onDelete = {},
        onEnabledChange = { _, _ -> },
        onMove = { _, _ -> },
        onFixBluetoothPermission = {},
    )
}

@AmplyPreview
@Composable
private fun ChargeRulesScreenEmptyPreview() = PreviewWrapper {
    ChargeRulesScreen(
        state = ChargeRulesUiState(bluetoothPermissionMissing = true),
        onBack = {},
        onAdd = {},
        onEdit = {},
        onDelete = {},
        onEnabledChange = { _, _ -> },
        onMove = { _, _ -> },
        onFixBluetoothPermission = {},
    )
}
