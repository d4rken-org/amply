package eu.darken.amply.rules.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Rule
import androidx.compose.material.icons.automirrored.twotone.ArrowBack
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Label
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Power
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import eu.darken.amply.R
import eu.darken.amply.charging.core.ChargePolicy
import eu.darken.amply.common.compose.AmplyCard
import eu.darken.amply.common.compose.AmplyCardDefaults
import eu.darken.amply.common.compose.AmplyCardHeader
import eu.darken.amply.common.compose.AmplyPreview
import eu.darken.amply.common.compose.PreviewWrapper
import eu.darken.amply.common.compose.asComposable
import eu.darken.amply.common.compose.description
import eu.darken.amply.rules.core.BondedDevice
import eu.darken.amply.rules.core.PlugKind

/**
 * Create or edit one rule. The charger-type option is **absent**, not disabled, where the adapter
 * latches its policy at plug time: offering a condition that can never take effect would be a
 * promise the device cannot keep.
 *
 * [detectedPlugKind] and [chargerUnrecognized] describe what is plugged in *right now*. They are
 * passed in rather than read here so the screen stays a pure function of its inputs, and they are
 * shown as a caption only — never as chip selection, which means "part of this rule", not "detected".
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ChargeRuleEditorScreen(
    state: RuleEditorState,
    detectedPlugKind: PlugKind?,
    chargerUnrecognized: Boolean,
    onCloseRequest: () -> Unit,
    onLabelChange: (String) -> Unit,
    onConditionKindChange: (ConditionKind) -> Unit,
    onDeviceSelect: (BondedDevice) -> Unit,
    onPlugKindToggle: (PlugKind) -> Unit,
    onPolicySelect: (ChargePolicy) -> Unit,
    onSave: () -> Unit,
    onDelete: (String) -> Unit,
    onConfirmDiscard: () -> Unit,
    onKeepEditing: () -> Unit,
    onRequestBluetoothPermission: () -> Unit,
) {
    // Transient menu/dialog visibility only. The DISCARD confirmation is deliberately not local: it
    // is raised by the ViewModel's dirty check, which the screen cannot perform.
    var menuOpen by remember { mutableStateOf(false) }
    var deleteConfirmOpen by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        stringResource(
                            if (state.isNew) R.string.rules_editor_title_new else R.string.rules_editor_title_edit,
                        ),
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onCloseRequest) {
                        Icon(
                            Icons.AutoMirrored.TwoTone.ArrowBack,
                            contentDescription = stringResource(R.string.action_back),
                        )
                    }
                },
                actions = {
                    // Delete lives in the overflow: it is the one destructive action here, and a bare
                    // icon beside Save invited exactly the mis-tap it cannot undo.
                    if (state.ruleId != null) {
                        Box {
                            IconButton(onClick = { menuOpen = true }) {
                                Icon(
                                    Icons.Default.MoreVert,
                                    contentDescription = stringResource(R.string.rules_editor_more_actions),
                                )
                            }
                            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.rules_action_delete)) },
                                    onClick = {
                                        menuOpen = false
                                        deleteConfirmOpen = true
                                    },
                                )
                            }
                        }
                    }
                    TextButton(onClick = onSave, enabled = state.canSave) {
                        Text(stringResource(R.string.rules_editor_save))
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
            item(key = "editor.when") {
                AmplyCard(verticalArrangement = Arrangement.spacedBy(AmplyCardDefaults.ItemSpacing)) {
                    AmplyCardHeader(
                        title = stringResource(R.string.rules_editor_when_header),
                        icon = Icons.AutoMirrored.Filled.Rule,
                    )
                    // One radio group: rows sit contiguous inside it (their own 56dp height is the
                    // separation, per the M3 selection-list pattern) instead of inheriting the
                    // card's item spacing between every option.
                    Column(Modifier.selectableGroup()) {
                        ConditionChoice(
                            text = stringResource(R.string.rules_editor_kind_bluetooth),
                            supporting = stringResource(R.string.rules_editor_kind_bluetooth_desc),
                            selected = state.conditionKind == ConditionKind.BLUETOOTH,
                            onClick = { onConditionKindChange(ConditionKind.BLUETOOTH) },
                        )
                        if (state.chargerTypeSupported) {
                            ConditionChoice(
                                text = stringResource(R.string.rules_editor_kind_charger),
                                supporting = stringResource(R.string.rules_editor_kind_charger_desc),
                                selected = state.conditionKind == ConditionKind.CHARGER,
                                onClick = { onConditionKindChange(ConditionKind.CHARGER) },
                            )
                        }
                    }
                }
            }
            when (state.conditionKind) {
                ConditionKind.BLUETOOTH -> item(key = "editor.devices") {
                    DeviceCard(
                        state = state,
                        onDeviceSelect = onDeviceSelect,
                        onRequestBluetoothPermission = onRequestBluetoothPermission,
                    )
                }
                ConditionKind.CHARGER -> item(key = "editor.chargers") {
                    ChargerCard(
                        state = state,
                        detectedPlugKind = detectedPlugKind,
                        chargerUnrecognized = chargerUnrecognized,
                        onPlugKindToggle = onPlugKindToggle,
                    )
                }
            }
            item(key = "editor.policy") {
                AmplyCard(verticalArrangement = Arrangement.spacedBy(AmplyCardDefaults.ItemSpacing)) {
                    AmplyCardHeader(
                        title = stringResource(R.string.rules_editor_policy_header),
                        icon = Icons.Default.BatteryChargingFull,
                    )
                    // Grouped by the derived kind, so "protect" and "charge fully" read as the two
                    // things a condition can be for rather than as one flat list of modes. One
                    // selectableGroup across both: the selection is single across the whole card,
                    // and the headers are plain (non-selectable) children within it.
                    val (charging, protecting) = state.supportedPolicies.partition { it.allowsFullCharge }
                    Column(Modifier.selectableGroup()) {
                        PolicyGroup(
                            header = stringResource(R.string.rules_kind_protection),
                            policies = protecting,
                            selected = state.policy,
                            onSelect = onPolicySelect,
                        )
                        PolicyGroup(
                            header = stringResource(R.string.rules_kind_charge),
                            policies = charging,
                            selected = state.policy,
                            onSelect = onPolicySelect,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                }
            }
            item(key = "editor.label") {
                AmplyCard(verticalArrangement = Arrangement.spacedBy(AmplyCardDefaults.ItemSpacing)) {
                    AmplyCardHeader(
                        title = stringResource(R.string.rules_editor_label_header),
                        icon = Icons.Default.Label,
                    )
                    OutlinedTextField(
                        value = state.label,
                        onValueChange = onLabelChange,
                        singleLine = true,
                        placeholder = { Text(stringResource(R.string.rules_editor_label_hint)) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        stringResource(R.string.rules_editor_label_helper),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }

    if (deleteConfirmOpen && state.ruleId != null) {
        ConfirmDialog(
            title = stringResource(R.string.rules_editor_delete_title),
            body = stringResource(R.string.rules_editor_delete_body),
            confirm = stringResource(R.string.rules_action_delete),
            dismiss = stringResource(R.string.action_cancel),
            onConfirm = {
                deleteConfirmOpen = false
                onDelete(state.ruleId)
            },
            onDismiss = { deleteConfirmOpen = false },
        )
    }
    if (state.showDiscardConfirm) {
        ConfirmDialog(
            title = stringResource(R.string.rules_editor_discard_title),
            body = stringResource(R.string.rules_editor_discard_body),
            confirm = stringResource(R.string.rules_editor_discard_confirm),
            dismiss = stringResource(R.string.rules_editor_discard_keep),
            onConfirm = onConfirmDiscard,
            onDismiss = onKeepEditing,
        )
    }
}

@Composable
private fun DeviceCard(
    state: RuleEditorState,
    onDeviceSelect: (BondedDevice) -> Unit,
    onRequestBluetoothPermission: () -> Unit,
) {
    AmplyCard(verticalArrangement = Arrangement.spacedBy(AmplyCardDefaults.ItemSpacing)) {
        AmplyCardHeader(
            title = stringResource(R.string.rules_editor_device_header),
            icon = Icons.Default.Bluetooth,
        )
        val rows = state.deviceRows
        when {
            // Stacked, not side-by-side: sharing a row would squeeze the explanation
            // against the action at larger font scales.
            state.bluetoothPermissionMissing -> Column(Modifier.fillMaxWidth()) {
                Text(
                    stringResource(R.string.rules_editor_bluetooth_permission),
                    style = MaterialTheme.typography.bodyMedium,
                )
                TextButton(
                    onClick = onRequestBluetoothPermission,
                    modifier = Modifier.align(Alignment.End),
                ) {
                    Text(stringResource(R.string.rules_editor_bluetooth_permission_action))
                }
            }
            rows.isEmpty() -> Text(
                stringResource(R.string.rules_editor_device_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            else -> Column(Modifier.selectableGroup()) {
                rows.forEach { row ->
                    ConditionChoice(
                        text = row.name?.takeIf { it.isNotBlank() }
                            ?: stringResource(R.string.rules_editor_device_unnamed),
                        supporting = if (row.unpaired) {
                            stringResource(R.string.rules_editor_device_unpaired)
                        } else {
                            row.address
                        },
                        // Primary-coloured and inside the row, so it is announced with the row
                        // rather than as a separate control.
                        accent = stringResource(R.string.rules_editor_device_connected)
                            .takeIf { row.connected },
                        selected = row.selected,
                        onClick = { onDeviceSelect(BondedDevice(row.address, row.name)) },
                    )
                }
            }
        }
        // One line for the whole section rather than a per-row hedge: the sweep either produced a
        // reading or it did not, and silence would leave "no marker" meaning two different things.
        if (!state.bluetoothPermissionMissing && state.freshness == ConnectionFreshness.UNAVAILABLE) {
            Text(
                stringResource(R.string.rules_editor_connection_unavailable),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChargerCard(
    state: RuleEditorState,
    detectedPlugKind: PlugKind?,
    chargerUnrecognized: Boolean,
    onPlugKindToggle: (PlugKind) -> Unit,
) {
    AmplyCard(verticalArrangement = Arrangement.spacedBy(AmplyCardDefaults.ItemSpacing)) {
        AmplyCardHeader(
            title = stringResource(R.string.rules_editor_charger_header),
            icon = Icons.Default.Bolt,
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PlugKind.entries.forEach { kind ->
                FilterChip(
                    selected = kind in state.plugKinds,
                    onClick = { onPlugKindToggle(kind) },
                    label = { Text(kind.label()) },
                    leadingIcon = if (kind in state.plugKinds) {
                        {
                            Icon(
                                Icons.Default.Check,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    } else {
                        null
                    },
                )
            }
        }
        if (state.plugKinds.isEmpty()) {
            Text(
                stringResource(R.string.rules_editor_charger_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        // What is plugged in right now, as a caption. Deliberately NOT chip selection: a selected
        // chip means "this rule covers this charger", and pre-selecting the detected one would put
        // a choice in the rule that the user never made.
        val caption = when {
            detectedPlugKind != null -> stringResource(R.string.rules_editor_charger_detected, detectedPlugKind.label())
            chargerUnrecognized -> stringResource(R.string.rules_editor_charger_detected_unknown)
            else -> null
        }
        caption?.let {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Default.Power,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun ConfirmDialog(
    title: String,
    body: String,
    confirm: String,
    dismiss: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(body) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(confirm) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(dismiss) } },
    )
}

@Composable
private fun PolicyGroup(
    header: String,
    policies: List<ChargePolicy>,
    selected: ChargePolicy?,
    onSelect: (ChargePolicy) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (policies.isEmpty()) return
    Text(
        header,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier,
    )
    policies.forEach { policy ->
        ConditionChoice(
            text = policy.label.asComposable(),
            // The same wording the dashboard uses for the same policy, from the shared mapping.
            supporting = policy.description().asComposable(),
            selected = selected == policy,
            onClick = { onSelect(policy) },
        )
    }
}

/**
 * A radio row per the M3 selection-list pattern. The **row** carries the selection semantics and the
 * radio button itself is inert, so the tap target is the whole line and accessibility announces one
 * control, not two — including [supporting] and [accent], which are merged into the row's node.
 * Because the inert radio brings no interactive minimum of its own, the row must enforce the
 * dimensions itself: 56dp minimum height (M3 single-line selection row; also keeps the two-line
 * paired-device rows above the 48dp touch-target floor). The control-to-label gap is the card
 * idiom's 8dp (AmplyCard headers/rows), not the M3 doc sample's 16dp — the radio glyph carries its
 * own inherent padding, and 16dp on top of it read visibly oversized on device.
 *
 * Nothing here caps lines: the supporting text is an explanation, and at large font scales it has to
 * wrap and grow the row rather than truncate the sentence that makes the option understandable.
 */
@Composable
private fun ConditionChoice(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    supporting: String? = null,
    accent: String? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Column(Modifier.weight(1f)) {
            Text(text, style = MaterialTheme.typography.bodyLarge)
            supporting?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            accent?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

private val previewPolicies = listOf(
    ChargePolicy.FixedLimit(80),
    ChargePolicy.Adaptive,
    ChargePolicy.Unrestricted,
)

private val previewDevices = listOf(
    BondedDevice("AA:BB:CC:DD:EE:FF", "Car audio"),
    BondedDevice("11:22:33:44:55:66", "Bedside speaker"),
)

@Composable
private fun EditorPreview(
    state: RuleEditorState,
    detectedPlugKind: PlugKind? = null,
    chargerUnrecognized: Boolean = false,
) = ChargeRuleEditorScreen(
    state = state,
    detectedPlugKind = detectedPlugKind,
    chargerUnrecognized = chargerUnrecognized,
    onCloseRequest = {},
    onLabelChange = {},
    onConditionKindChange = {},
    onDeviceSelect = {},
    onPlugKindToggle = {},
    onPolicySelect = {},
    onSave = {},
    onDelete = {},
    onConfirmDiscard = {},
    onKeepEditing = {},
    onRequestBluetoothPermission = {},
)

// An existing rule: overflow + Save coexist in the bar, and the selected device is marked connected.
@AmplyPreview
@Composable
private fun ChargeRuleEditorScreenPreview() = PreviewWrapper {
    EditorPreview(
        state = RuleEditorState(
            ruleId = "car",
            label = "Car",
            conditionKind = ConditionKind.BLUETOOTH,
            address = "AA:BB:CC:DD:EE:FF",
            deviceName = "Car audio",
            policy = ChargePolicy.Unrestricted,
            supportedPolicies = previewPolicies,
            bondedDevices = previewDevices,
            connectedAddresses = setOf("AA:BB:CC:DD:EE:FF"),
            freshness = ConnectionFreshness.FRESH,
        ),
    )
}

// The title, the overflow and the Save action have to survive a narrow screen at a large font scale
// together — this is where a text action in the bar goes wrong if it goes wrong.
@Preview(showBackground = true, name = "Compact + large font", widthDp = 320, fontScale = 1.5f)
@Composable
private fun ChargeRuleEditorScreenCompactPreview() = PreviewWrapper {
    EditorPreview(
        state = RuleEditorState(
            ruleId = "car",
            label = "Car",
            conditionKind = ConditionKind.BLUETOOTH,
            address = "AA:BB:CC:DD:EE:FF",
            deviceName = "Car audio",
            policy = ChargePolicy.FixedLimit(80),
            supportedPolicies = previewPolicies,
            bondedDevices = previewDevices,
        ),
    )
}

// The device the rule points at is gone from the bonded list: it stays visible, selected and marked,
// so Save can never quietly keep something the screen never showed.
@AmplyPreview
@Composable
private fun ChargeRuleEditorScreenUnpairedPreview() = PreviewWrapper {
    EditorPreview(
        state = RuleEditorState(
            ruleId = "car",
            conditionKind = ConditionKind.BLUETOOTH,
            address = "99:88:77:66:55:44",
            deviceName = "Old car",
            policy = ChargePolicy.Adaptive,
            supportedPolicies = previewPolicies,
            bondedDevices = previewDevices,
            freshness = ConnectionFreshness.UNAVAILABLE,
        ),
    )
}

@AmplyPreview
@Composable
private fun ChargeRuleEditorScreenPermissionPreview() = PreviewWrapper {
    EditorPreview(
        state = RuleEditorState(
            conditionKind = ConditionKind.BLUETOOTH,
            supportedPolicies = previewPolicies,
            bluetoothPermissionMissing = true,
        ),
    )
}

@AmplyPreview
@Composable
private fun ChargeRuleEditorScreenNoDevicesPreview() = PreviewWrapper {
    EditorPreview(
        state = RuleEditorState(
            conditionKind = ConditionKind.BLUETOOTH,
            supportedPolicies = previewPolicies,
        ),
    )
}

// A charger rule mid-edit with nothing picked yet: Save is off until a charger type is selected, and
// the detected charger is a caption — the USB chip stays unselected.
@AmplyPreview
@Composable
private fun ChargeRuleEditorScreenChargerPreview() = PreviewWrapper {
    EditorPreview(
        state = RuleEditorState(
            conditionKind = ConditionKind.CHARGER,
            supportedPolicies = listOf(ChargePolicy.FixedLimit(80), ChargePolicy.Unrestricted),
        ),
        detectedPlugKind = PlugKind.USB,
    )
}

@AmplyPreview
@Composable
private fun ChargeRuleEditorScreenChargerUnrecognizedPreview() = PreviewWrapper {
    EditorPreview(
        state = RuleEditorState(
            conditionKind = ConditionKind.CHARGER,
            plugKinds = setOf(PlugKind.AC),
            policy = ChargePolicy.FixedLimit(80),
            supportedPolicies = previewPolicies,
        ),
        chargerUnrecognized = true,
    )
}

// Unplugged: no caption at all, rather than a "nothing detected" line that says nothing.
@AmplyPreview
@Composable
private fun ChargeRuleEditorScreenChargerUnpluggedPreview() = PreviewWrapper {
    EditorPreview(
        state = RuleEditorState(
            conditionKind = ConditionKind.CHARGER,
            plugKinds = setOf(PlugKind.AC, PlugKind.DOCK),
            policy = ChargePolicy.Adaptive,
            supportedPolicies = previewPolicies,
        ),
    )
}

@AmplyPreview
@Composable
private fun ChargeRuleEditorScreenDiscardPreview() = PreviewWrapper {
    EditorPreview(
        state = RuleEditorState(
            ruleId = "car",
            label = "Car",
            conditionKind = ConditionKind.BLUETOOTH,
            address = "AA:BB:CC:DD:EE:FF",
            deviceName = "Car audio",
            policy = ChargePolicy.Unrestricted,
            supportedPolicies = previewPolicies,
            bondedDevices = previewDevices,
            showDiscardConfirm = true,
        ),
    )
}
