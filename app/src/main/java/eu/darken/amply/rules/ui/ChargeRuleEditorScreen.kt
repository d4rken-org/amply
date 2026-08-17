package eu.darken.amply.rules.ui

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.automirrored.twotone.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import eu.darken.amply.R
import eu.darken.amply.charging.core.ChargePolicy
import eu.darken.amply.common.compose.AmplyCard
import eu.darken.amply.common.compose.AmplyCardDefaults
import eu.darken.amply.common.compose.AmplyPreview
import eu.darken.amply.common.compose.PreviewWrapper
import eu.darken.amply.common.compose.asComposable
import eu.darken.amply.rules.core.BondedDevice
import eu.darken.amply.rules.core.PlugKind

/**
 * Create or edit one rule. The charger-type option is **absent**, not disabled, where the adapter
 * latches its policy at plug time: offering a condition that can never take effect would be a
 * promise the device cannot keep.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ChargeRuleEditorScreen(
    state: RuleEditorState,
    onBack: () -> Unit,
    onLabelChange: (String) -> Unit,
    onConditionKindChange: (ConditionKind) -> Unit,
    onDeviceSelect: (BondedDevice) -> Unit,
    onPlugKindToggle: (PlugKind) -> Unit,
    onPolicySelect: (ChargePolicy) -> Unit,
    onSave: () -> Unit,
    onDelete: (String) -> Unit,
    onRequestBluetoothPermission: () -> Unit,
) {
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
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.TwoTone.ArrowBack,
                            contentDescription = stringResource(R.string.action_back),
                        )
                    }
                },
                actions = {
                    state.ruleId?.let { id ->
                        IconButton(onClick = { onDelete(id) }) {
                            Icon(
                                Icons.Default.Delete,
                                contentDescription = stringResource(R.string.rules_action_delete),
                            )
                        }
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
                    Text(
                        stringResource(R.string.rules_editor_when_header),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    // One radio group: rows sit contiguous inside it (their own 56dp height is the
                    // separation, per the M3 selection-list pattern) instead of inheriting the
                    // card's item spacing between every option.
                    Column(Modifier.selectableGroup()) {
                        ConditionChoice(
                            text = stringResource(R.string.rules_editor_kind_bluetooth),
                            selected = state.conditionKind == ConditionKind.BLUETOOTH,
                            onClick = { onConditionKindChange(ConditionKind.BLUETOOTH) },
                        )
                        if (state.chargerTypeSupported) {
                            ConditionChoice(
                                text = stringResource(R.string.rules_editor_kind_charger),
                                selected = state.conditionKind == ConditionKind.CHARGER,
                                onClick = { onConditionKindChange(ConditionKind.CHARGER) },
                            )
                        }
                    }
                }
            }
            when (state.conditionKind) {
                ConditionKind.BLUETOOTH -> item(key = "editor.devices") {
                    AmplyCard(verticalArrangement = Arrangement.spacedBy(AmplyCardDefaults.ItemSpacing)) {
                        Text(
                            stringResource(R.string.rules_editor_device_header),
                            style = MaterialTheme.typography.titleMedium,
                        )
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
                            state.bondedDevices.isEmpty() -> Text(
                                stringResource(R.string.rules_editor_device_empty),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            else -> Column(Modifier.selectableGroup()) {
                                state.bondedDevices.forEach { device ->
                                    ConditionChoice(
                                        text = device.name?.takeIf { it.isNotBlank() }
                                            ?: stringResource(R.string.rules_editor_device_unnamed),
                                        supporting = device.address,
                                        selected = state.address == device.address,
                                        onClick = { onDeviceSelect(device) },
                                    )
                                }
                            }
                        }
                    }
                }
                ConditionKind.CHARGER -> item(key = "editor.chargers") {
                    AmplyCard(verticalArrangement = Arrangement.spacedBy(AmplyCardDefaults.ItemSpacing)) {
                        Text(
                            stringResource(R.string.rules_editor_charger_header),
                            style = MaterialTheme.typography.titleMedium,
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
                    }
                }
            }
            item(key = "editor.policy") {
                AmplyCard(verticalArrangement = Arrangement.spacedBy(AmplyCardDefaults.ItemSpacing)) {
                    Text(
                        stringResource(R.string.rules_editor_policy_header),
                        style = MaterialTheme.typography.titleMedium,
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
                    Text(
                        stringResource(R.string.rules_editor_label_header),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    OutlinedTextField(
                        value = state.label,
                        onValueChange = onLabelChange,
                        singleLine = true,
                        placeholder = { Text(stringResource(R.string.rules_editor_label_hint)) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            item(key = "editor.save") {
                Button(
                    onClick = onSave,
                    enabled = state.canSave,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.rules_editor_save))
                }
            }
        }
    }
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
            selected = selected == policy,
            onClick = { onSelect(policy) },
        )
    }
}

/**
 * A radio row per the M3 selection-list pattern. The **row** carries the selection semantics and the
 * radio button itself is inert, so the tap target is the whole line and accessibility announces one
 * control, not two. Because the inert radio brings no interactive minimum of its own, the row must
 * enforce the dimensions itself: 56dp minimum height (M3 single-line selection row; also keeps the
 * two-line paired-device rows above the 48dp touch-target floor). The control-to-label gap is the
 * card idiom's 8dp (AmplyCard headers/rows), not the M3 doc sample's 16dp — the radio glyph carries
 * its own inherent padding, and 16dp on top of it read visibly oversized on device.
 */
@Composable
private fun ConditionChoice(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    supporting: String? = null,
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
        }
    }
}

@AmplyPreview
@Composable
private fun ChargeRuleEditorScreenPreview() = PreviewWrapper {
    ChargeRuleEditorScreen(
        state = RuleEditorState(
            ruleId = "car",
            label = "Car",
            conditionKind = ConditionKind.BLUETOOTH,
            address = "AA:BB:CC:DD:EE:FF",
            deviceName = "Car audio",
            policy = ChargePolicy.Unrestricted,
            supportedPolicies = listOf(
                ChargePolicy.FixedLimit(80),
                ChargePolicy.Adaptive,
                ChargePolicy.Unrestricted,
            ),
            bondedDevices = listOf(
                BondedDevice("AA:BB:CC:DD:EE:FF", "Car audio"),
                BondedDevice("11:22:33:44:55:66", "Bedside speaker"),
            ),
        ),
        onBack = {},
        onLabelChange = {},
        onConditionKindChange = {},
        onDeviceSelect = {},
        onPlugKindToggle = {},
        onPolicySelect = {},
        onSave = {},
        onDelete = {},
        onRequestBluetoothPermission = {},
    )
}

// A charger rule on a device that supports it, mid-edit with nothing picked yet: the save button is
// off until at least one charger type is selected.
@AmplyPreview
@Composable
private fun ChargeRuleEditorScreenChargerPreview() = PreviewWrapper {
    ChargeRuleEditorScreen(
        state = RuleEditorState(
            conditionKind = ConditionKind.CHARGER,
            supportedPolicies = listOf(ChargePolicy.FixedLimit(80), ChargePolicy.Unrestricted),
        ),
        onBack = {},
        onLabelChange = {},
        onConditionKindChange = {},
        onDeviceSelect = {},
        onPlugKindToggle = {},
        onPolicySelect = {},
        onSave = {},
        onDelete = {},
        onRequestBluetoothPermission = {},
    )
}
