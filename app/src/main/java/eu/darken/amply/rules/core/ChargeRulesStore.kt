package eu.darken.amply.rules.core

import androidx.datastore.preferences.core.stringPreferencesKey
import eu.darken.amply.common.AppDataStore
import eu.darken.amply.common.datastore.createValue
import eu.darken.amply.common.datastore.value
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Field-by-field decode of the rules runtime, following the same reasoning as
 * `ChargingPreferences.decodePolicyState`: this record carries **owed restore work** — the user's own
 * charging policy that a rule temporarily replaced — and a whole-record fallback would let one
 * unreadable field (a future phase name, a malformed suspension list) silently drop it, leaving the
 * battery on a rule's policy with nothing left that knows to put it back.
 *
 * The one deliberately conservative reading: an unreadable [RuleRuntimeState.phase] alongside an
 * activation still decodes as [RulePhase.ACTIVE], not IDLE. Claiming "no rule owns the policy" while
 * a baseline is recorded is the only failure direction that loses work.
 */
internal fun decodeRuleRuntimeState(raw: String?): RuleRuntimeState {
    if (raw == null) return RuleRuntimeState()
    val obj = runCatching { Json.parseToJsonElement(raw) as? JsonObject }.getOrNull() ?: return RuleRuntimeState()
    val activeRuleId = obj.stringOrNull("activeRuleId")
    val baselinePolicyId = obj.stringOrNull("baselinePolicyId")
    val phase = obj.stringOrNull("phase")
        ?.let { name -> RulePhase.entries.firstOrNull { it.name == name } }
        ?: if (activeRuleId != null || baselinePolicyId != null) RulePhase.ACTIVE else RulePhase.IDLE
    return RuleRuntimeState(
        phase = phase,
        targetPolicyId = obj.stringOrNull("targetPolicyId"),
        activeRuleId = activeRuleId,
        baselinePolicyId = baselinePolicyId,
        suspendedRuleIds = obj.stringSet("suspendedRuleIds"),
        lastApplyFailed = obj.booleanOrDefault("lastApplyFailed"),
        lastWriteAt = obj.longOrDefault("lastWriteAt"),
    )
}

private fun JsonObject.primitiveOrNull(name: String): JsonPrimitive? =
    (this[name] as? JsonPrimitive)?.takeUnless { it is JsonNull }

private fun JsonObject.stringOrNull(name: String): String? = primitiveOrNull(name)?.takeIf { it.isString }?.content

private fun JsonObject.booleanOrDefault(name: String, default: Boolean = false): Boolean =
    primitiveOrNull(name)?.takeUnless { it.isString }?.content?.toBooleanStrictOrNull() ?: default

private fun JsonObject.longOrDefault(name: String, default: Long = 0L): Long =
    primitiveOrNull(name)?.takeUnless { it.isString }?.content?.toLongOrNull() ?: default

private fun JsonObject.stringSet(name: String): Set<String> = (this[name] as? JsonArray)
    ?.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
    ?.toSet()
    .orEmpty()

/**
 * Persistence for the conditional charge rules, their runtime bookkeeping, and the Bluetooth
 * connected-set the manifest receiver maintains. Three separate keys on purpose: the receiver writes
 * the Bluetooth set on a completely different cadence than the user edits rules, and the runtime
 * changes on every activation — folding them together would wake every collector on each.
 *
 * The mutators are `internal` and are called **only** from `RuleApplier`, which serializes every
 * mutation and every evaluation under one mutex. Editing the rule set outside that lock could
 * interleave with an in-flight activation and strand the runtime pointing at a rule that no longer
 * exists.
 */
@Singleton
class ChargeRulesStore @Inject constructor(
    dataStore: AppDataStore,
    json: Json,
) {
    // A rule set is read as a unit, and an unparseable one means "no rules" — which only ever removes
    // overrides, never adds one — so the whole-record fallback is the safe reading here.
    private val ruleSetValue = dataStore.createValue(
        key = "rules.set.v1",
        defaultValue = ChargeRuleSet(),
        json = json,
        fallbackToDefault = true,
    )

    private val runtimeValue = dataStore.createValue(
        key = stringPreferencesKey("rules.runtime.v1"),
        reader = { raw -> decodeRuleRuntimeState(raw as? String) },
        writer = { state -> json.encodeToString(RuleRuntimeState.serializer(), state) },
    )

    private val btValue = dataStore.createValue(
        key = "rules.bt.v1",
        defaultValue = BtConnectionSnapshot(),
        json = json,
        fallbackToDefault = true,
    )

    val rules: Flow<List<ChargeRule>> = ruleSetValue.flow.map { it.rules.deduped() }.distinctUntilChanged()

    val runtime: Flow<RuleRuntimeState> = runtimeValue.flow

    /**
     * Read-only view of the connected set, for surfaces that want to show it live (the editor's
     * "connected now" markers stay current as ACL broadcasts land). Mutating it stays applier-only —
     * every write still has to be serialized against evaluation.
     */
    val btSnapshot: Flow<BtConnectionSnapshot> = btValue.flow

    suspend fun rulesNow(): List<ChargeRule> = ruleSetValue.value().rules.deduped()

    suspend fun runtimeNow(): RuleRuntimeState = runtimeValue.value()

    suspend fun btSnapshotNow(): BtConnectionSnapshot = btValue.value()

    internal suspend fun updateRules(block: (List<ChargeRule>) -> List<ChargeRule>) {
        ruleSetValue.update { current -> ChargeRuleSet(rules = block(current.rules.deduped()).deduped()) }
    }

    internal suspend fun updateRuntime(block: (RuleRuntimeState) -> RuleRuntimeState) {
        runtimeValue.update(block)
    }

    /**
     * The revision is stamped **here**, not by the callers, so no write site can forget it and break
     * the ordering that readers depend on. It is a read-modify-write inside the store's own
     * transaction, so two concurrent writers cannot mint the same number.
     *
     * A write that changes nothing keeps its revision, which matters more than it looks: the
     * evaluation path resolves this snapshot on every ~30s tick, and an unconditional bump would
     * turn each of those into a disk write that re-emits the shared store to every collector in the
     * app. It also keeps the ordering honest — the same set arriving twice is one write, not two.
     */
    internal suspend fun updateBtSnapshot(block: (BtConnectionSnapshot) -> BtConnectionSnapshot) {
        btValue.update { current ->
            val next = block(current)
            if (next.addresses == current.addresses && next.bootCount == current.bootCount) {
                current
            } else {
                next.copy(revision = current.revision + 1)
            }
        }
    }
}

/**
 * Ids address rules (the runtime points at one, reorder/delete resolve by it), so a duplicate would
 * make "which rule" ambiguous. Keep the first occurrence — it is the higher-priority one.
 */
private fun List<ChargeRule>.deduped(): List<ChargeRule> = distinctBy { it.id }
