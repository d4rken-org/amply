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
import eu.darken.amply.fullcharge.core.BootCountProvider
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
import kotlinx.coroutines.Job
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
 * How much the editor may claim about which devices are connected.
 *
 * Tri-state on purpose: "we have not looked yet" and "we looked and could not tell" must not both
 * render as "not connected". A marker saying a device is connected is a claim about the world right
 * now, so it is shown only under [FRESH].
 */
enum class ConnectionFreshness {
    UNKNOWN,
    FRESH,
    UNAVAILABLE,
}

/** One selectable device row, including one the rule points at that is no longer paired. */
data class EditorDeviceRow(
    val address: String,
    val name: String?,
    val selected: Boolean,
    /** The rule's device is gone from the bonded list; shown anyway so Save is never a surprise. */
    val unpaired: Boolean,
    val connected: Boolean,
)

/**
 * The editor's working copy. Held here rather than in the screen so a rotation mid-edit doesn't lose
 * it and so the pure screen stays a function of state.
 */
data class RuleEditorState(
    val ruleId: String? = null,
    /**
     * The edited rule's switch, carried through untouched: saving an edit must never turn a rule on
     * or off behind the user's back. A new rule starts **off** and is switched on only once the
     * entitlement gate and the notification prompt have both passed.
     */
    val enabled: Boolean = false,
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
    /** Addresses reported connected; only ever presented as such under [ConnectionFreshness.FRESH]. */
    val connectedAddresses: Set<String> = emptySet(),
    val freshness: ConnectionFreshness = ConnectionFreshness.UNKNOWN,
    /** The user asked to leave with unsaved edits; the screen renders the discard confirmation. */
    val showDiscardConfirm: Boolean = false,
) {
    val isNew: Boolean get() = ruleId == null

    /** A charger rule with no type selected would be a wildcard; the engine treats it as matching nothing. */
    val canSave: Boolean
        get() = policy != null && when (conditionKind) {
            ConditionKind.BLUETOOTH -> !address.isNullOrBlank()
            ConditionKind.CHARGER -> plugKinds.isNotEmpty()
        }

    /**
     * The device list as the editor shows it: the bonded devices, plus — when the rule points at a
     * device that is no longer paired — a row for that device too, still selected and marked. The
     * selection is never silently dropped: what Save would keep has to stay visible.
     */
    val deviceRows: List<EditorDeviceRow>
        get() {
            val selectedAddress = address?.takeIf { it.isNotBlank() }
            val normalizedSelection = selectedAddress?.let(::normalizeBtAddress)
            val rows = bondedDevices.map { device ->
                val normalized = normalizeBtAddress(device.address)
                EditorDeviceRow(
                    address = device.address,
                    name = device.name,
                    selected = normalized == normalizedSelection,
                    unpaired = false,
                    connected = isConnected(normalized),
                )
            }
            if (selectedAddress == null || rows.any { it.selected }) return rows
            return rows + EditorDeviceRow(
                address = selectedAddress,
                name = deviceName,
                selected = true,
                unpaired = true,
                connected = isConnected(normalizeBtAddress(selectedAddress)),
            )
        }

    private fun isConnected(normalizedAddress: String) =
        freshness == ConnectionFreshness.FRESH && normalizedAddress in connectedAddresses
}

/**
 * Just the fields a user edits. Everything else on [RuleEditorState] — the bonded list, connection
 * freshness, the adapter's policies — refreshes underneath them from the outside, and comparing the
 * whole state would report a background refresh as an unsaved change and demand a discard
 * confirmation the user never earned.
 */
internal data class RuleDraft(
    val label: String,
    val conditionKind: ConditionKind,
    val address: String?,
    val plugKinds: Set<PlugKind>,
    val policyId: String?,
)

/**
 * Deliberately excludes the device NAME: it travels with the address when the user picks a device,
 * but it can also change on its own when the device is renamed in the OS, which is not an edit.
 */
internal fun RuleEditorState.draft() = RuleDraft(
    label = label,
    conditionKind = conditionKind,
    address = address,
    plugKinds = plugKinds,
    policyId = policy?.stableId,
)

@HiltViewModel
class ChargeRulesViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val applier: RuleApplier,
    private val repository: ChargingRepository,
    private val upgradeRepo: UpgradeRepo,
    private val bootCountProvider: BootCountProvider,
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

    /**
     * The editor is done and the root may navigate away. Every exit routes through here — save,
     * delete, an unchanged back-out, and a confirmed discard — so there is exactly one place that
     * decides an edit is over, and no call site can navigate past a draft that still needs a
     * confirmation.
     */
    val closeEditorEvents = SingleEventFlow<Unit>()

    private val bluetoothPermissionMissing = MutableStateFlow(!applier.hasBluetoothPermission())
    private val editorState = MutableStateFlow<RuleEditorState?>(null)

    /** The draft as it was when the editor opened; what "unsaved changes" is measured against. */
    private var pristineDraft: RuleDraft? = null

    // Both are restartable and last-write-wins: an older, slower answer must never land on top of a
    // newer one and show a stale device list or a stale freshness.
    private var bluetoothRefreshJob: Job? = null
    private var reconcileJob: Job? = null

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
        // The connected set stays live while the editor is open: the manifest ACL receiver keeps the
        // store current, so a device connecting or dropping shows up without another sweep. A
        // snapshot from a previous boot describes connections that cannot still exist.
        viewModelScope.launch {
            applier.btSnapshot.collect { snapshot ->
                val boot = withContext(Dispatchers.Default) { bootCountProvider.current() }
                val addresses = if (snapshot.bootCount == boot) snapshot.addresses else emptySet()
                editorState.update { it.copy(connectedAddresses = addresses) }
            }
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

    /**
     * Re-read the permission AND the bonded list, and re-check which devices are connected.
     *
     * Driven by the root while the editor is open: the system permission prompt, but also Bluetooth
     * being switched on or off and devices being paired or unpaired, all change what this screen
     * should be showing while the user is looking at it.
     *
     * Restartable: the previous run is cancelled so a slower earlier answer — an empty list read
     * while the adapter was still coming up — can never land on top of a newer one.
     */
    fun refreshEditorBluetooth() {
        bluetoothRefreshJob?.cancel()
        bluetoothRefreshJob = viewModelScope.launch {
            val missing = withContext(Dispatchers.Default) { !applier.hasBluetoothPermission() }
            bluetoothPermissionMissing.value = missing
            if (editorState.value == null) return@launch
            val devices = withContext(Dispatchers.Default) { applier.bondedDevices() }
            editorState.update { it.copy(bluetoothPermissionMissing = missing, bondedDevices = devices) }
            reconcileConnections()
        }
    }

    /**
     * Refresh which devices are connected, without holding anything up. Never awaited by the editor's
     * own opening: the profile sweep can take seconds, and rows that render immediately with markers
     * appearing a moment later beat a screen that stalls on a Bluetooth round-trip.
     */
    private fun reconcileConnections() {
        reconcileJob?.cancel()
        reconcileJob = viewModelScope.launch {
            editorState.update { it.copy(freshness = ConnectionFreshness.UNKNOWN) }
            val fresh = withContext(Dispatchers.Default) { applier.reconcileBluetoothForUi() }
            editorState.update {
                it.copy(
                    freshness = if (fresh) ConnectionFreshness.FRESH else ConnectionFreshness.UNAVAILABLE,
                )
            }
        }
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
            openEditor(editorDefaults())
        }
    }

    /** Editing an existing rule is never gated: a lapsed entitlement must not trap a rule switched on. */
    fun editRule(id: String) {
        viewModelScope.launch {
            val rule = applier.rulesNow().firstOrNull { it.id == id } ?: return@launch
            val condition = rule.condition
            openEditor(
                editorDefaults().copy(
                    ruleId = rule.id,
                    enabled = rule.enabled,
                    label = rule.label,
                    conditionKind = when (condition) {
                        is RuleCondition.BluetoothDevice -> ConditionKind.BLUETOOTH
                        is RuleCondition.ChargerType -> ConditionKind.CHARGER
                    },
                    address = (condition as? RuleCondition.BluetoothDevice)?.address,
                    deviceName = (condition as? RuleCondition.BluetoothDevice)?.name,
                    plugKinds = (condition as? RuleCondition.ChargerType)?.types.orEmpty(),
                    policy = rule.policy,
                ),
            )
        }
    }

    private fun openEditor(state: RuleEditorState) {
        editorState.value = state
        // The yardstick for "unsaved changes", captured before the user can touch anything.
        pristineDraft = state.draft()
        openEditorEvents.tryEmit(Unit)
        if (state.conditionKind == ConditionKind.BLUETOOTH) reconcileConnections()
    }

    /**
     * The single exit door. An untouched draft leaves immediately; a modified one raises the discard
     * confirmation instead of throwing the edit away. Saving never comes through here — a save is
     * not a discard, and must never be blocked by the dialog.
     */
    fun requestCloseEditor() {
        val current = editorState.value
        if (current == null) {
            closeEditorEvents.tryEmit(Unit)
            return
        }
        if (current.draft() == pristineDraft) {
            finishEditing()
        } else {
            editorState.update { it.copy(showDiscardConfirm = true) }
        }
    }

    fun confirmDiscardEditor() = finishEditing()

    fun keepEditing() = editorState.update { it.copy(showDiscardConfirm = false) }

    private fun finishEditing() {
        editorState.value = null
        pristineDraft = null
        closeEditorEvents.tryEmit(Unit)
    }

    fun setEditorLabel(label: String) = editorState.update { it.copy(label = label) }

    fun setEditorConditionKind(kind: ConditionKind) {
        editorState.update { it.copy(conditionKind = kind) }
        // Switching TO Bluetooth is when the device list first matters; refresh what it shows.
        if (kind == ConditionKind.BLUETOOTH) reconcileConnections()
    }

    fun setEditorDevice(device: BondedDevice) = editorState.update {
        it.copy(address = device.address, deviceName = device.name)
    }

    fun toggleEditorPlugKind(kind: PlugKind) = editorState.update {
        it.copy(plugKinds = if (kind in it.plugKinds) it.plugKinds - kind else it.plugKinds + kind)
    }

    fun setEditorPolicy(policy: ChargePolicy) = editorState.update { it.copy(policy = policy) }

    /**
     * Persist the working copy.
     *
     * A brand-new rule is saved **switched off** and then routed through the normal enable flow
     * (entitlement gate, then the notification prompt, then the write that turns it on). Saving it
     * on and switching it off again if a gate refuses would mean a rule that briefly owns the
     * charging policy before anything has agreed it may; saving it off costs one extra step and can
     * only ever leave the user with a visible, inert rule they can switch on later.
     *
     * An edit carries the existing switch through untouched — saving must not turn a rule on or off.
     */
    fun saveEditor() {
        val draft = editorState.value ?: return
        val policy = draft.policy ?: return
        if (!draft.canSave) return
        viewModelScope.launch {
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
                enabled = draft.enabled,
                label = draft.label.trim(),
                condition = condition,
                policyId = policy.stableId,
            )
            if (draft.isNew) applier.addRule(rule) else applier.updateRule(rule)
            finishEditing()
            nudgeService()
            // Now ask for the rule to be switched on, through the same gate every other enable uses.
            // A refusal leaves the rule saved and off rather than active-then-revoked.
            if (draft.isNew) requestEnableRule(id)
        }
    }

    fun deleteRule(id: String) {
        viewModelScope.launch {
            applier.deleteRule(id)
            // Also closes the editor when the delete came from it; a no-op from the list screen,
            // where the root is already on the destination this navigates to.
            finishEditing()
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
