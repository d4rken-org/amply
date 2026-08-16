package eu.darken.amply.rules.ui

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import eu.darken.amply.charging.core.ChargePolicy
import eu.darken.amply.charging.core.ChargingRepository
import eu.darken.amply.common.debug.logging.Logging
import eu.darken.amply.common.debug.logging.log
import eu.darken.amply.common.debug.logging.logTag
import eu.darken.amply.common.flow.SingleEventFlow
import eu.darken.amply.fullcharge.core.ChargeSessionService
import eu.darken.amply.rules.core.BondedDevice
import eu.darken.amply.rules.core.ChargeRule
import eu.darken.amply.rules.core.PlugKind
import eu.darken.amply.rules.core.RuleApplier
import eu.darken.amply.rules.core.RuleCondition
import eu.darken.amply.rules.core.RulePhase
import eu.darken.amply.rules.core.RuleRuntimeState
import eu.darken.amply.rules.core.normalizeBtAddress
import eu.darken.amply.rules.core.policy
import eu.darken.amply.upgrade.core.UpgradeRepo
import eu.darken.amply.upgrade.core.isProForUi
import eu.darken.amply.upgrade.core.isProSettled
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID
import javax.inject.Inject

/** One row of the ordered rule list, with everything the screen needs already derived. */
data class ChargeRuleRow(
    val rule: ChargeRule,
    val policy: ChargePolicy?,
    val active: Boolean,
    /** The condition or the policy cannot do anything on this device; the row says so. */
    val unsupportedCondition: Boolean,
    val unsupportedPolicy: Boolean,
    val canMoveUp: Boolean,
    val canMoveDown: Boolean,
)

data class ChargeRulesUiState(
    val rows: List<ChargeRuleRow> = emptyList(),
    val applyFailed: Boolean = false,
    val bluetoothPermissionMissing: Boolean = false,
    val showProBadge: Boolean = false,
)

enum class ConditionKind {
    BLUETOOTH,
    CHARGER,
}

/**
 * The editor's working copy. Held here rather than in the screen so a rotation mid-edit doesn't lose
 * it and so the pure screen stays a function of state.
 */
data class RuleEditorState(
    val ruleId: String? = null,
    val label: String = "",
    val conditionKind: ConditionKind = ConditionKind.BLUETOOTH,
    val address: String? = null,
    val deviceName: String? = null,
    val plugKinds: Set<PlugKind> = emptySet(),
    val policy: ChargePolicy? = null,
    val supportedPolicies: List<ChargePolicy> = emptyList(),
    val bondedDevices: List<BondedDevice> = emptyList(),
    val chargerTypeSupported: Boolean = true,
    val bluetoothPermissionMissing: Boolean = false,
) {
    val isNew: Boolean get() = ruleId == null

    /** A charger rule with no type selected would be a wildcard; the engine treats it as matching nothing. */
    val canSave: Boolean
        get() = policy != null && when (conditionKind) {
            ConditionKind.BLUETOOTH -> !address.isNullOrBlank()
            ConditionKind.CHARGER -> plugKinds.isNotEmpty()
        }
}

@HiltViewModel
class ChargeRulesViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val applier: RuleApplier,
    private val repository: ChargingRepository,
    private val upgradeRepo: UpgradeRepo,
) : ViewModel() {

    /** A gated affordance was used without the entitlement; the root navigates to the upgrade screen. */
    val upgradeRequiredEvents = SingleEventFlow<Unit>()

    /**
     * The entitlement check passed and this rule may be switched on. The notification prompt is
     * deliberately downstream: asking for notification access and only then refusing the feature
     * would be the wrong order to put a user through.
     */
    val proceedWithEnableEvents = SingleEventFlow<String>()

    /** The editor is ready (its state is already set); the root navigates to it. */
    val openEditorEvents = SingleEventFlow<Unit>()

    private val bluetoothPermissionMissing = MutableStateFlow(!applier.hasBluetoothPermission())
    private val editorState = MutableStateFlow<RuleEditorState?>(null)

    /**
     * Resolved once, off the main thread: the answer comes from adapter selection, which reads
     * providers, activities and system settings. Permissive until it lands — the rows only *add* a
     * warning when it turns out false, so an optimistic first frame understates nothing.
     */
    private val chargerTypeSupported = MutableStateFlow(true)

    val editor: StateFlow<RuleEditorState?> = editorState.asStateFlow()

    init {
        viewModelScope.launch(Dispatchers.Default) {
            chargerTypeSupported.value = applier.chargerTypeSupported()
        }
    }

    val state: StateFlow<ChargeRulesUiState> = combine(
        applier.rules,
        applier.runtime,
        bluetoothPermissionMissing,
        chargerTypeSupported,
        upgradeRepo.upgradeInfo
            .map { it.isSettled && !it.isPro }
            .catch { e ->
                if (e is CancellationException) throw e
                log(TAG, Logging.Priority.WARN) { "Upgrade info read failed: ${e.message}" }
                emit(false)
            }
            .onStart { emit(false) }
            .distinctUntilChanged(),
    ) { rules, runtime, permissionMissing, chargerSupported, showProBadge ->
        ChargeRulesUiState(
            rows = rules.toRows(runtime, chargerSupported),
            applyFailed = runtime.lastApplyFailed,
            bluetoothPermissionMissing = permissionMissing && rules.any {
                it.enabled && it.condition is RuleCondition.BluetoothDevice
            },
            showProBadge = showProBadge,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), ChargeRulesUiState())

    private fun List<ChargeRule>.toRows(
        runtime: RuleRuntimeState,
        chargerTypeSupported: Boolean,
    ): List<ChargeRuleRow> {
        val supported = repository.state.value.supportedPolicies
        return mapIndexed { index, rule ->
            val policy = rule.policy
            ChargeRuleRow(
                rule = rule,
                policy = policy,
                active = runtime.phase != RulePhase.IDLE && runtime.activeRuleId == rule.id,
                unsupportedCondition = rule.condition is RuleCondition.ChargerType && !chargerTypeSupported,
                // Only claimable once the adapter has actually offered a policy list; an empty one
                // means "not resolved yet", not "this device supports nothing".
                unsupportedPolicy = policy == null || (supported.isNotEmpty() && policy !in supported),
                canMoveUp = index > 0,
                canMoveDown = index < lastIndex,
            )
        }
    }

    /** Re-read after returning from the system permission prompt. */
    fun refreshBluetoothPermission() {
        bluetoothPermissionMissing.value = !applier.hasBluetoothPermission()
    }

    /**
     * The one entry point for adding: gated *before* the editor opens, so nobody fills in a condition
     * only to be told at the end that it needs an upgrade.
     */
    fun requestAddRule() {
        viewModelScope.launch {
            if (!upgradeRepo.isProForUi()) {
                log(TAG) { "Rule creation denied, routing to the upgrade screen" }
                upgradeRequiredEvents.tryEmit(Unit)
                return@launch
            }
            editorState.value = editorDefaults()
            openEditorEvents.tryEmit(Unit)
        }
    }

    /** Editing an existing rule is never gated: a lapsed entitlement must not trap a rule switched on. */
    fun editRule(id: String) {
        viewModelScope.launch {
            val rule = applier.rulesNow().firstOrNull { it.id == id } ?: return@launch
            val condition = rule.condition
            editorState.value = editorDefaults().copy(
                ruleId = rule.id,
                label = rule.label,
                conditionKind = when (condition) {
                    is RuleCondition.BluetoothDevice -> ConditionKind.BLUETOOTH
                    is RuleCondition.ChargerType -> ConditionKind.CHARGER
                },
                address = (condition as? RuleCondition.BluetoothDevice)?.address,
                deviceName = (condition as? RuleCondition.BluetoothDevice)?.name,
                plugKinds = (condition as? RuleCondition.ChargerType)?.types.orEmpty(),
                policy = rule.policy,
            )
            openEditorEvents.tryEmit(Unit)
        }
    }

    fun closeEditor() {
        editorState.value = null
    }

    fun setEditorLabel(label: String) = editorState.update { it.copy(label = label) }

    fun setEditorConditionKind(kind: ConditionKind) = editorState.update { it.copy(conditionKind = kind) }

    fun setEditorDevice(device: BondedDevice) = editorState.update {
        it.copy(address = device.address, deviceName = device.name)
    }

    fun toggleEditorPlugKind(kind: PlugKind) = editorState.update {
        it.copy(plugKinds = if (kind in it.plugKinds) it.plugKinds - kind else it.plugKinds + kind)
    }

    fun setEditorPolicy(policy: ChargePolicy) = editorState.update { it.copy(policy = policy) }

    /**
     * Persist the working copy. A brand-new rule is switched on, which is an activation — so it
     * passes the backend entitlement gate here (not just the navigation gate at [requestAddRule])
     * and then routes through the notification prompt like any other enable.
     */
    fun saveEditor() {
        val draft = editorState.value ?: return
        val policy = draft.policy ?: return
        if (!draft.canSave) return
        viewModelScope.launch {
            if (draft.isNew && !upgradeRepo.isProSettled()) {
                log(TAG) { "Rule creation denied at the write, routing to the upgrade screen" }
                upgradeRequiredEvents.tryEmit(Unit)
                return@launch
            }
            val condition = when (draft.conditionKind) {
                ConditionKind.BLUETOOTH -> RuleCondition.BluetoothDevice(
                    address = normalizeBtAddress(draft.address.orEmpty()),
                    name = draft.deviceName,
                )
                ConditionKind.CHARGER -> RuleCondition.ChargerType(draft.plugKinds)
            }
            val id = draft.ruleId ?: UUID.randomUUID().toString()
            val rule = ChargeRule(
                id = id,
                enabled = true,
                label = draft.label.trim(),
                condition = condition,
                policyId = policy.stableId,
            )
            if (draft.isNew) applier.addRule(rule) else applier.updateRule(rule)
            editorState.value = null
            nudgeService()
            // A new rule is on from the moment it is saved, so it needs the monitor notification.
            if (draft.isNew) proceedWithEnableEvents.tryEmit(id)
        }
    }

    fun deleteRule(id: String) {
        viewModelScope.launch {
            applier.deleteRule(id)
            editorState.value = null
            nudgeService()
        }
    }

    /** Switching a rule ON is gated; switching it off never is. */
    fun requestEnableRule(id: String) {
        viewModelScope.launch {
            if (upgradeRepo.isProForUi()) {
                proceedWithEnableEvents.tryEmit(id)
            } else {
                log(TAG) { "Rule enable denied, routing to the upgrade screen" }
                upgradeRequiredEvents.tryEmit(Unit)
            }
        }
    }

    fun setRuleEnabled(id: String, enabled: Boolean) {
        viewModelScope.launch {
            // Defence in depth, as with the stats capture switch: this is the only path that writes
            // the flag, and it is reachable from more than one affordance.
            if (enabled && !upgradeRepo.isProSettled()) {
                log(TAG) { "Rule enable denied at the write, routing to the upgrade screen" }
                upgradeRequiredEvents.tryEmit(Unit)
                return@launch
            }
            applier.setRuleEnabled(id, enabled)
            nudgeService()
        }
    }

    fun moveRule(id: String, up: Boolean) {
        viewModelScope.launch {
            applier.moveRule(id, up)
            nudgeService()
        }
    }

    /**
     * Off the main thread: listing bonded devices is a Binder round-trip to the Bluetooth stack, and
     * the charger-type answer can still be resolving adapter selection on first use.
     */
    private suspend fun editorDefaults(): RuleEditorState = withContext(Dispatchers.Default) {
        RuleEditorState(
            supportedPolicies = repository.state.value.supportedPolicies,
            bondedDevices = applier.bondedDevices(),
            chargerTypeSupported = applier.chargerTypeSupported(),
            bluetoothPermissionMissing = !applier.hasBluetoothPermission(),
        )
    }

    /**
     * Every mutation re-evaluates: a rule the user just switched on has to take effect now, not on
     * the next 30s tick. Mirrors the alarm's enable path — a foreground start from a foreground
     * activity — and tolerates a refusal, since the next service start reconciles anyway.
     */
    private fun nudgeService() {
        val intent = Intent(context, ChargeSessionService::class.java)
            .setAction(ChargeSessionService.ACTION_EVALUATE_RULES)
        try {
            ContextCompat.startForegroundService(context, intent)
        } catch (e: Exception) {
            log(TAG, Logging.Priority.WARN) { "Rule evaluation nudge failed: ${e.message}" }
        }
    }

    private fun MutableStateFlow<RuleEditorState?>.update(block: (RuleEditorState) -> RuleEditorState) {
        value = value?.let(block)
    }

    private companion object {
        val TAG = logTag("Rules", "ViewModel")
        const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}
