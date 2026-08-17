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
import eu.darken.amply.rules.core.BtConnectionSnapshot
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
    /**
     * A save has been accepted and is being written. The draft is committed from this moment: Save is
     * inert, and backing out no longer offers to discard something that is already on its way to disk.
     */
    val isSaving: Boolean = false,
) {
    val isNew: Boolean get() = ruleId == null

    /** A charger rule with no type selected would be a wildcard; the engine treats it as matching nothing. */
    val canSave: Boolean
        get() = policy != null && when (conditionKind) {
            ConditionKind.BLUETOOTH -> !address.isNullOrBlank()
            ConditionKind.CHARGER -> plugKinds.isNotEmpty()
        }

    /**
     * What the Save control binds to. Separate from [canSave] — which describes the draft — so a
     * second tap during the write cannot start a second save: the applier's mutex can be held for
     * seconds by a Bluetooth sweep, which is more than enough time to tap twice and create two
     * rules with two different ids.
     */
    val canSaveNow: Boolean get() = canSave && !isSaving

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

    /**
     * Identifies one editing session. Async work captures it when it starts and re-checks it before
     * touching editor state, so a save or delete that completes after the user has already left —
     * or opened a different rule — cannot clear the draft that is on screen now.
     *
     * Only ever read and written on the main thread (every entry point is a UI callback and every
     * continuation resumes on the main dispatcher), so no synchronization is needed.
     */
    private var editorSession = 0L

    // Restartable and last-write-wins: an older, slower answer must never land on top of a newer one
    // and show a stale device list or a stale freshness.
    private var bluetoothRefreshJob: Job? = null
    private var reconcileJob: Job? = null

    /**
     * Bumped synchronously at every refresh entry point. The jobs are cancelled too, but cancellation
     * is not instantaneous — a coroutine already past its last suspension point still runs to
     * completion — so the generation is what actually decides whose answer is allowed to land.
     */
    private var refreshGeneration = 0L

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
        //
        // Every emission is applied, including ones that arrive mid-sweep. Dropping those would lose
        // a connection change for good — the store emits each write once — and there is nothing to
        // protect against by dropping them: the markers only render under FRESH, and freshness is
        // UNKNOWN for the whole time a sweep is in flight, so a mid-sweep set is invisible until the
        // sweep's own update declares one. Emissions arrive in store-write order, so the set only
        // moves forward.
        viewModelScope.launch {
            applier.btSnapshot.collect { snapshot ->
                val addresses = withContext(Dispatchers.Default) { snapshot.addressesForThisBoot() }
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
        val generation = beginRefresh()
        bluetoothRefreshJob = viewModelScope.launch {
            val missing = withContext(Dispatchers.Default) { !applier.hasBluetoothPermission() }
            if (generation != refreshGeneration) return@launch
            bluetoothPermissionMissing.value = missing
            // No editor open: the permission flag above is all the list screen needs.
            if (editorState.value == null) return@launch
            val devices = withContext(Dispatchers.Default) { applier.bondedDevices() }
            if (generation != refreshGeneration) return@launch
            editorState.update { it.copy(bluetoothPermissionMissing = missing, bondedDevices = devices) }
            runReconcile(generation)
        }
    }

    /**
     * Everything a refresh must do *synchronously*, before its first suspension: claim a generation,
     * stop the previous attempt, and drop the freshness claim. Doing any of it after a suspension
     * would leave a window where the screen still asserts a reading that is already being replaced.
     */
    private fun beginRefresh(): Long {
        refreshGeneration += 1
        bluetoothRefreshJob?.cancel()
        reconcileJob?.cancel()
        editorState.update { it.copy(freshness = ConnectionFreshness.UNKNOWN) }
        return refreshGeneration
    }

    /**
     * Refresh which devices are connected, without holding anything up. Never awaited by the editor's
     * own opening: the profile sweep can take seconds, and rows that render immediately with markers
     * appearing a moment later beat a screen that stalls on a Bluetooth round-trip.
     */
    private fun reconcileConnections() {
        val generation = beginRefresh()
        reconcileJob = viewModelScope.launch { runReconcile(generation) }
    }

    /**
     * The set and the freshness are applied together — never freshness first and the addresses from
     * some later emission, which would briefly present the *previous* set as "connected now".
     *
     * The set comes from the store after the sweep, not from the sweep itself: a connection change
     * can land between the sweep's write and this read, and the store's value is then the newer of
     * the two. Reading it here means FRESH is always paired with the newest committed set.
     */
    private suspend fun runReconcile(generation: Long) {
        val swept = withContext(Dispatchers.Default) { applier.reconcileBluetoothForUi() }
        if (generation != refreshGeneration) return
        if (!swept) {
            editorState.update { it.copy(freshness = ConnectionFreshness.UNAVAILABLE) }
            return
        }
        val addresses = withContext(Dispatchers.Default) { applier.btSnapshotNow().addressesForThisBoot() }
        if (generation != refreshGeneration) return
        editorState.update {
            it.copy(connectedAddresses = addresses, freshness = ConnectionFreshness.FRESH)
        }
    }

    /**
     * Connections do not survive a reboot, so a snapshot stamped with a different boot count
     * describes devices that cannot still be connected. Applied identically wherever a stored
     * snapshot reaches the UI, so the two paths can never disagree about what it means.
     */
    private fun BtConnectionSnapshot.addressesForThisBoot(): Set<String> =
        if (bootCount == bootCountProvider.current()) addresses else emptySet()

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
        // A new session: work still in flight from the previous one can no longer touch this draft.
        editorSession += 1
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
     *
     * Once a save has been accepted there is nothing left to discard: the draft is committed and the
     * write is on its way, so this waits for that write's own close rather than offering to throw
     * away something that is already being persisted.
     */
    fun requestCloseEditor() {
        val current = editorState.value
        if (current == null) {
            closeEditorEvents.tryEmit(Unit)
            return
        }
        if (current.isSaving) return
        if (current.draft() == pristineDraft) {
            finishEditing()
        } else {
            editorState.update { it.copy(showDiscardConfirm = true) }
        }
    }

    fun confirmDiscardEditor() = finishEditing()

    fun keepEditing() = editorState.update { it.copy(showDiscardConfirm = false) }

    private fun finishEditing() {
        // Bumped here too: anything still in flight for the session that just ended must not be able
        // to act on the next one.
        editorSession += 1
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
     *
     * Accepting the save flips [RuleEditorState.isSaving] **synchronously**, before the coroutine
     * starts. The write goes through the applier's mutex, which a Bluetooth sweep can hold for
     * seconds — long enough to tap Save again and create a second rule under a second id, or to back
     * out and discard a draft that is already being written.
     */
    fun saveEditor() {
        val draft = editorState.value ?: return
        if (draft.isSaving) return
        val policy = draft.policy ?: return
        if (!draft.canSave) return
        val session = editorSession
        editorState.update { it.copy(isSaving = true, showDiscardConfirm = false) }
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
            try {
                if (draft.isNew) applier.addRule(rule) else applier.updateRule(rule)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log(TAG, Logging.Priority.ERROR) { "Saving rule $id failed: ${e.message}" }
                // Keep the draft and let the user try again — closing here would lose their work
                // and leave nothing written.
                if (editorSession == session) editorState.update { it.copy(isSaving = false) }
                return@launch
            }
            nudgeService()
            // Only this session's own editor may be closed: by now the user may have backed out and
            // opened a different rule, and that draft is not ours to clear.
            if (editorSession == session) finishEditing()
            // Unconditional: the rule exists now, so it still deserves its enable prompt whatever
            // happened to the editor meanwhile.
            if (draft.isNew) requestEnableRule(id)
        }
    }

    /**
     * Delete from the rules LIST. Deliberately blind to the editor: the two entry points used to
     * share one method, so a list delete completing after the user opened some other rule's editor
     * cleared that draft and navigated away from it.
     */
    fun deleteRule(id: String) {
        viewModelScope.launch {
            applier.deleteRule(id)
            nudgeService()
        }
    }

    /** Delete from the editor's overflow: closes the editor, but only the one that asked. */
    fun deleteEditingRule(id: String) {
        val session = editorSession
        viewModelScope.launch {
            applier.deleteRule(id)
            nudgeService()
            if (editorSession == session && editorState.value?.ruleId == id) finishEditing()
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
