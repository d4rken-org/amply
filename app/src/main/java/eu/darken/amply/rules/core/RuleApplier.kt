package eu.darken.amply.rules.core

import eu.darken.amply.charging.core.ChargeObservation
import eu.darken.amply.charging.core.ChargePolicy
import eu.darken.amply.charging.core.ChargingPreferences
import eu.darken.amply.common.debug.logging.Logging
import eu.darken.amply.common.debug.logging.log
import eu.darken.amply.common.debug.logging.logTag
import eu.darken.amply.fullcharge.core.BootCountProvider
import eu.darken.amply.upgrade.core.UpgradeRepo
import eu.darken.amply.upgrade.core.isProSettled
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The rules layer's single serialization point.
 *
 * **Every** rule-store mutation (the editor's CRUD, reorder, toggle; the Bluetooth receiver's
 * snapshot update) and every evaluation runs under this one mutex. Without that, a rule deleted from
 * the UI could interleave with an in-flight activation and leave the runtime owning a policy on
 * behalf of a rule that no longer exists — with nothing left that knows to restore the baseline.
 *
 * It deliberately never takes the charge-session service's dispatch lock: the service calls in here,
 * not the other way round, so there is exactly one lock ordering and no cycle.
 *
 * Transitions are **write-ahead**: the intended phase, target and baseline are persisted before the
 * policy write and only finalized after it succeeds. A process death mid-write therefore leaves a
 * pending phase that the next evaluation reconciles, rather than a lost baseline.
 */
@Singleton
class RuleApplier @Inject constructor(
    private val store: ChargeRulesStore,
    private val gateway: RuleChargeGateway,
    private val preferences: ChargingPreferences,
    private val upgradeRepo: UpgradeRepo,
    private val bluetooth: BluetoothConnectionSource,
    private val bootCountProvider: BootCountProvider,
) {
    private val mutex = Mutex()

    val rules: Flow<List<ChargeRule>> = store.rules
    val runtime: Flow<RuleRuntimeState> = store.runtime

    /** The connected-Bluetooth set, live, for surfaces that show it (the editor's device list). */
    val btSnapshot: Flow<BtConnectionSnapshot> = store.btSnapshot

    /** Point read for a one-shot caller (the editor loading a rule); collection is via [rules]. */
    suspend fun rulesNow(): List<ChargeRule> = store.rulesNow()

    /** False on plug-latched adapters — the editor hides charger-type conditions there. */
    fun chargerTypeSupported(): Boolean = gateway.chargerTypeSupported()

    fun bondedDevices(): List<BondedDevice> = bluetooth.bondedDevices()

    fun hasBluetoothPermission(): Boolean = bluetooth.hasPermission()

    /**
     * Whether the monitor service must stay alive for the rules layer. Owed work counts as much as
     * an enabled rule: a pending write still has to be retried, and a suspension cohort still has to
     * be observed stopping matching before rules may resume.
     *
     * Lock-free on purpose — this is polled from the service's keep-alive path and must never queue
     * behind an in-flight evaluation.
     */
    suspend fun isServiceRequired(): Boolean {
        if (store.rulesNow().any { it.enabled }) return true
        val runtime = store.runtimeNow()
        return runtime.phase != RulePhase.IDLE || runtime.suspendedRuleIds.isNotEmpty()
    }

    /**
     * One evaluation pass: reconcile owed work, decide, act.
     *
     * [reconcileBluetooth] asks for the (bounded, best-effort) profile-proxy sweep instead of
     * trusting the receiver-maintained snapshot. Only worth it when the process may have missed
     * broadcasts — i.e. at service start — never on the 30s tick.
     */
    suspend fun evaluate(
        plugged: Boolean,
        plugKind: PlugKind?,
        sessionActive: Boolean,
        reconcileBluetooth: Boolean = false,
    ) = mutex.withLock {
        val ruleList = store.rulesNow()
        val runtimeState = store.runtimeNow()
        if (ruleList.none { it.enabled } && runtimeState.phase == RulePhase.IDLE &&
            runtimeState.suspendedRuleIds.isEmpty()
        ) {
            return@withLock
        }

        val snapshot = ConditionSnapshot(
            btAddresses = resolveBtAddresses(ruleList, reconcileBluetooth),
            plugged = plugged,
            plugKind = plugKind,
            chargerTypeSupported = gateway.chargerTypeSupported(),
        )
        // Only pay for a readback when it can change the answer: an activation needs a baseline, and
        // an active rule needs the divergence check. An idle layer with nothing matching needs neither.
        val relevant = runtimeState.phase != RulePhase.IDLE ||
            ruleList.any { it.enabled && it.policy != null && it.matches(snapshot) }
        val configured = if (relevant) readConfigured() else null
        val lastPersistent = preferences.lastPersistentPolicyNow()
        // The shared write journal: whatever Amply last wrote, from any component. Read as a pair so
        // the policy and its timestamp can never be sampled either side of a concurrent write.
        val lastRequestedPolicy = preferences.lastRequestedNow()
        val lastRequestedAt = preferences.lastRequestedAtNow()

        val inputs = { proSettled: Boolean ->
            RuleEngine.evaluate(
                rules = ruleList,
                snapshot = snapshot,
                runtime = runtimeState,
                configured = configured,
                sessionActive = sessionActive,
                proSettled = proSettled,
                lastPersistent = lastPersistent,
                defaultProtective = gateway.defaultProtectivePolicy(),
                supportedPolicyIds = gateway.supportedPolicyIds(),
                lastRequestedPolicy = lastRequestedPolicy,
                lastRequestedAt = lastRequestedAt,
            )
        }
        // Resolve the entitlement only when the outcome actually depends on it: isProSettled() can
        // wait out a billing round-trip, which has no business running on every battery tick.
        var decision = inputs(true)
        if (decision.action is RuleAction.Activate || decision.action is RuleAction.Switch) {
            if (!upgradeRepo.isProSettled()) {
                log(TAG) { "Rule activation denied: no entitlement" }
                decision = inputs(false)
            }
        }
        perform(decision)
    }

    /**
     * Refresh the connected set for a surface that is displaying it, without evaluating anything.
     *
     * Runs the same sweep the evaluation path uses (permission and boot-count handling included) and
     * persists the result, so the editor's markers and the rules layer read one shared snapshot
     * instead of two answers that can disagree. Under the same mutex, so it cannot interleave with
     * an evaluation's own snapshot write.
     *
     * Returns null when the sweep could not produce an answer — the snapshot is then left exactly as
     * the ACL receiver built it, and the caller must say "unavailable" rather than present a
     * possibly-stale list as current.
     *
     * It returns the resolved snapshot rather than a bare success flag so the caller can adopt the
     * addresses and mark them fresh in ONE step. With a flag it would have to source the set from
     * the store's flow instead, whose next emission is not ordered against this return — leaving a
     * window where the reading is declared fresh while the previous set is still on screen.
     */
    suspend fun reconcileBluetoothForUi(): BtConnectionSnapshot? = mutex.withLock {
        // Deliberately not routed through the rule-shaped short-circuit below: an editor filling in
        // its FIRST Bluetooth condition has no enabled Bluetooth rule yet, and would otherwise be
        // told nothing is connected.
        resolveConnected(reconcile = true).let { if (it.swept) it.snapshot else null }
    }

    /** Receiver hook. Returns whether any enabled rule actually cares about Bluetooth. */
    suspend fun onBluetoothConnectionChanged(address: String, connected: Boolean): Boolean = mutex.withLock {
        val normalized = normalizeBtAddress(address)
        val bootCount = bootCountProvider.current()
        store.updateBtSnapshot { current ->
            // A snapshot from a previous boot describes connections that cannot still exist.
            val base = if (current.bootCount == bootCount) current.addresses else emptySet()
            BtConnectionSnapshot(
                addresses = if (connected) base + normalized else base - normalized,
                bootCount = bootCount,
            )
        }
        store.rulesNow().any { it.enabled && it.condition is RuleCondition.BluetoothDevice }
    }

    suspend fun addRule(rule: ChargeRule) = mutex.withLock {
        store.updateRules { it + rule }
    }

    suspend fun updateRule(rule: ChargeRule) = mutex.withLock {
        store.updateRules { rules -> rules.map { if (it.id == rule.id) rule else it } }
    }

    suspend fun deleteRule(id: String) = mutex.withLock {
        store.updateRules { rules -> rules.filterNot { it.id == id } }
    }

    suspend fun setRuleEnabled(id: String, enabled: Boolean) = mutex.withLock {
        store.updateRules { rules -> rules.map { if (it.id == id) it.copy(enabled = enabled) else it } }
    }

    /** Reorder by one position. Priority is the list order, so this is the whole priority editor. */
    suspend fun moveRule(id: String, up: Boolean) = mutex.withLock {
        store.updateRules { rules ->
            val index = rules.indexOfFirst { it.id == id }
            val target = index + if (up) -1 else 1
            if (index < 0 || target !in rules.indices) {
                rules
            } else {
                rules.toMutableList().apply { add(target, removeAt(index)) }
            }
        }
    }

    /**
     * The user (or the widget/tile) is writing a persistent policy: it outranks the rules layer, so
     * drop rule ownership without restoring and suspend **every** rule that currently matches. Only
     * the winner would be too narrow — the second-priority rule would re-apply over the same choice
     * on the very next tick.
     *
     * Called *before* the write, alongside persisting the pending recovery target — not after it
     * succeeds. Suspending ahead of the write is the safe order because that persisted target owns
     * convergence to the explicit policy on every failure path (a failed write, a killed process, a
     * reboot), so the end state is the explicit policy whether or not this particular write lands.
     * Suspending afterwards would instead leave a window where the policy is already written and
     * every rule is still armed to overwrite it on the next tick.
     */
    suspend fun suspendMatchingCohort(plugged: Boolean, plugKind: PlugKind?) = mutex.withLock {
        val ruleList = store.rulesNow()
        val snapshot = ConditionSnapshot(
            btAddresses = resolveBtAddresses(ruleList, reconcile = false),
            plugged = plugged,
            plugKind = plugKind,
            chargerTypeSupported = gateway.chargerTypeSupported(),
        )
        val cohort = ruleList.filter { it.enabled && it.policy != null && it.matches(snapshot) }
            .map { it.id }
            .toSet()
        log(TAG, Logging.Priority.INFO) { "Explicit persistent write; suspending cohort $cohort" }
        store.updateRuntime { RuleRuntimeState(suspendedRuleIds = cohort) }
    }

    /**
     * The baseline a starting full-charge session must restore to, so the session — which is durable
     * and survives process death — owns the user's true policy rather than the rules layer's
     * transient override. Null when no rule owns the policy.
     */
    suspend fun readActiveBaseline(): ChargePolicy? = mutex.withLock {
        store.runtimeNow().takeIf { it.phase != RulePhase.IDLE }?.baselinePolicy
    }

    /**
     * Hand ownership to a session that is already persisted. Strictly after the session record
     * exists: clearing first would open a window where neither the session nor the rules layer owes
     * the baseline back.
     */
    suspend fun clearActiveAfterSessionPersist() = mutex.withLock {
        store.updateRuntime { RuleRuntimeState(suspendedRuleIds = it.suspendedRuleIds) }
    }

    private suspend fun perform(decision: RuleDecision) {
        val suspended = decision.suspendedRuleIds
        when (val action = decision.action) {
            is RuleAction.Noop -> store.updateRuntime { it.copy(suspendedRuleIds = suspended) }
            is RuleAction.ClearActiveBookkeeping -> {
                log(TAG) { "Dropping rule ownership without a write" }
                store.updateRuntime { RuleRuntimeState(suspendedRuleIds = suspended) }
            }
            is RuleAction.AdoptExternal -> {
                log(TAG, Logging.Priority.INFO) {
                    "Configured policy changed outside the rules layer; adopting it and suspending ${action.cohort}"
                }
                store.updateRuntime { RuleRuntimeState(suspendedRuleIds = action.cohort) }
            }
            is RuleAction.Activate -> {
                log(TAG, Logging.Priority.INFO) {
                    "Rule ${action.rule.id} activating ${action.policy.stableId} over ${action.baseline.stableId}"
                }
                writeAhead(
                    intent = RuleRuntimeState(
                        phase = RulePhase.APPLY_PENDING,
                        targetPolicyId = action.policy.stableId,
                        activeRuleId = action.rule.id,
                        baselinePolicyId = action.baseline.stableId,
                        suspendedRuleIds = suspended,
                    ),
                    policy = action.policy,
                    onSuccess = { it.copy(phase = RulePhase.ACTIVE) },
                )
            }
            is RuleAction.Switch -> {
                log(TAG, Logging.Priority.INFO) {
                    "Rule ${action.rule.id} taking over with ${action.policy.stableId}"
                }
                writeAhead(
                    intent = RuleRuntimeState(
                        phase = RulePhase.APPLY_PENDING,
                        targetPolicyId = action.policy.stableId,
                        activeRuleId = action.rule.id,
                        baselinePolicyId = action.baseline.stableId,
                        suspendedRuleIds = suspended,
                    ),
                    policy = action.policy,
                    onSuccess = { it.copy(phase = RulePhase.ACTIVE) },
                )
            }
            is RuleAction.Restore -> {
                log(TAG, Logging.Priority.INFO) { "No rule matches; restoring ${action.policy.stableId}" }
                writeAhead(
                    intent = RuleRuntimeState(
                        phase = RulePhase.RESTORE_PENDING,
                        targetPolicyId = action.policy.stableId,
                        baselinePolicyId = action.policy.stableId,
                        suspendedRuleIds = suspended,
                    ),
                    policy = action.policy,
                    onSuccess = { RuleRuntimeState(suspendedRuleIds = it.suspendedRuleIds) },
                )
            }
            is RuleAction.ReconcilePending -> {
                log(TAG) { "Re-issuing the pending rule write for ${action.policy.stableId}" }
                val pending = store.runtimeNow()
                writeAhead(
                    intent = pending.copy(suspendedRuleIds = suspended),
                    policy = action.policy,
                    onSuccess = { current ->
                        if (current.phase == RulePhase.RESTORE_PENDING) {
                            RuleRuntimeState(suspendedRuleIds = current.suspendedRuleIds)
                        } else {
                            current.copy(phase = RulePhase.ACTIVE)
                        }
                    },
                )
            }
        }
    }

    /**
     * Persist the intent, then write, then finalize. The order is the crash-safety property: a death
     * between the two leaves a pending phase (retried on the next tick), never a policy the layer has
     * silently forgotten it owes a restore for.
     */
    private suspend fun writeAhead(
        intent: RuleRuntimeState,
        policy: ChargePolicy,
        onSuccess: (RuleRuntimeState) -> RuleRuntimeState,
    ) {
        store.updateRuntime { intent }
        val written = try {
            gateway.applyTemporary(policy)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log(TAG, Logging.Priority.ERROR) { "Rule write for ${policy.stableId} threw: ${e.message}" }
            false
        }
        if (written) {
            // Stamped by COPYING the journal entry the repository just wrote, never from an
            // independent clock read: the engine compares the two, and two separate `now`s could
            // differ by milliseconds and make the rules layer look overwritten by its own write.
            val stamp = preferences.lastRequestedAtNow()
            store.updateRuntime { onSuccess(it).copy(lastApplyFailed = false, lastWriteAt = stamp) }
        } else {
            // Keep the pending phase: the write is still owed, and the 30s monitor tick retries it.
            log(TAG, Logging.Priority.ERROR) { "Rule write for ${policy.stableId} failed" }
            store.updateRuntime { it.copy(lastApplyFailed = true) }
        }
    }

    private suspend fun readConfigured(): ChargeObservation? = try {
        gateway.readConfigured()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        // Unreadable is a legitimate answer here: the engine falls back to the journal for a
        // baseline and refuses to claim external divergence without a readback.
        log(TAG, Logging.Priority.WARN) { "Configured readback failed: ${e.message}" }
        null
    }

    /**
     * The connected-Bluetooth set to evaluate against.
     *
     * A missing BLUETOOTH_CONNECT reads as **empty**, not as "unchanged": without it neither the
     * receiver nor the proxies can observe anything, so keeping a stale set would hold a rule active
     * for a device that may have been gone for hours. Empty deactivates and restores the baseline,
     * which is the safe direction.
     */
    private suspend fun resolveBtAddresses(rules: List<ChargeRule>, reconcile: Boolean): Set<String> {
        // No rule rides on Bluetooth: skip the whole thing rather than pay for a sweep nothing reads.
        if (rules.none { it.enabled && it.condition is RuleCondition.BluetoothDevice }) return emptySet()
        return resolveConnected(reconcile).snapshot.addresses
    }

    /**
     * [swept] answers "is this a fresh reading", which only a surface displaying the set cares
     * about. The evaluation path acts on [snapshot] either way: a sweep that could not answer leaves
     * the receiver-built snapshot in place, which is the best available evidence.
     */
    private data class BtResolution(val snapshot: BtConnectionSnapshot, val swept: Boolean)

    private suspend fun resolveConnected(reconcile: Boolean): BtResolution {
        val bootCount = bootCountProvider.current()
        if (!bluetooth.hasPermission()) {
            log(TAG, Logging.Priority.WARN) { "Bluetooth permission missing; treating nothing as connected" }
            val empty = BtConnectionSnapshot(bootCount = bootCount)
            store.updateBtSnapshot { empty }
            // Not a failed reading: "nothing observable" IS the answer, and it is the same one the
            // evaluation path acts on.
            return BtResolution(empty, swept = true)
        }
        if (reconcile) {
            val live = try {
                bluetooth.connectedAddresses()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log(TAG, Logging.Priority.WARN) { "Bluetooth reconciliation failed: ${e.message}" }
                null
            }
            if (live != null) {
                val swept = BtConnectionSnapshot(addresses = live, bootCount = bootCount)
                store.updateBtSnapshot { swept }
                return BtResolution(swept, swept = true)
            }
        }
        val snapshot = store.btSnapshotNow()
        if (snapshot.bootCount != bootCount) {
            val reset = BtConnectionSnapshot(bootCount = bootCount)
            store.updateBtSnapshot { reset }
            return BtResolution(reset, swept = false)
        }
        return BtResolution(snapshot, swept = false)
    }

    private companion object {
        val TAG = logTag("Rules", "Applier")
    }
}
