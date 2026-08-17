package eu.darken.amply.rules.core

import eu.darken.amply.charging.core.ChargeObservation
import eu.darken.amply.charging.core.ChargePolicy

/**
 * Everything the engine is allowed to know about the world right now. Deliberately a value type with
 * no Android in it: the whole precedence stack is decided here, on the JVM, and unit-tested directly.
 *
 * [chargerTypeSupported] is a capability, not a preference: on adapters whose ROM samples the policy
 * only at plug time (`policyLatchesAtPlug`), a charger-type rule's write always lands *after* the
 * sample and is restored before the next one, so it could never take effect. Such rules never match.
 */
data class ConditionSnapshot(
    val btAddresses: Set<String> = emptySet(),
    val plugged: Boolean = false,
    val plugKind: PlugKind? = null,
    val chargerTypeSupported: Boolean = true,
)

/** What the rules layer should do next. Exactly one action per evaluation pass. */
sealed interface RuleAction {
    data object Noop : RuleAction

    /** Drop rule ownership without writing anything — a session or an external choice took over. */
    data object ClearActiveBookkeeping : RuleAction

    data class Activate(val rule: ChargeRule, val policy: ChargePolicy, val baseline: ChargePolicy) : RuleAction

    /** A different (or edited) rule wins while one is already active; the baseline carries over. */
    data class Switch(val rule: ChargeRule, val policy: ChargePolicy, val baseline: ChargePolicy) : RuleAction

    /** No rule wins any more: write the recorded baseline back. */
    data class Restore(val policy: ChargePolicy) : RuleAction

    /** The configured policy diverged from what the rules layer wrote: adopt it and suspend. */
    data class AdoptExternal(val cohort: Set<String>) : RuleAction

    /** A persisted-but-unconfirmed write is still owed; re-issue it. */
    data class ReconcilePending(val policy: ChargePolicy) : RuleAction
}

/**
 * One evaluation's outcome. The suspension cohort rides along rather than needing its own action, so
 * "these suspended rules stopped matching" and "therefore this rule may now activate" resolve in the
 * SAME pass — a two-pass design would need a spare evaluation to make progress and could sit idle
 * until the next 30s tick.
 */
data class RuleDecision(
    val action: RuleAction,
    val suspendedRuleIds: Set<String>,
)

/**
 * The precedence stack, top wins: **active session > explicit user persistent write > external
 * change > rules > baseline**.
 *
 * Pure by construction (no Android, no I/O, no clock): the applier collects the inputs, this decides,
 * the applier performs. Every safety property worth testing — never overwrite a state Amply cannot
 * restore, never claim a rule applied when the write failed, never re-activate over the user's own
 * choice — is a property of this function.
 */
object RuleEngine {

    fun evaluate(
        rules: List<ChargeRule>,
        snapshot: ConditionSnapshot,
        runtime: RuleRuntimeState,
        /** Fresh configured readback, or null when this adapter/device cannot produce one. */
        configured: ChargeObservation?,
        sessionActive: Boolean,
        proSettled: Boolean,
        /** Amply's own journal of the user's last persistent choice; fallback baseline only. */
        lastPersistent: ChargePolicy?,
        defaultProtective: ChargePolicy,
        /**
         * Stable ids the selected adapter can actually apply. **Empty means the device can apply
         * nothing** — the diagnostics-only lab adapters declare exactly that — so it is read
         * strictly, never as "not resolved yet". A rule naming a policy outside this set can only
         * ever produce a refused write.
         */
        supportedPolicyIds: Set<String>,
        /** The last policy ANY Amply component wrote, and when, from the shared write journal. */
        lastRequestedPolicy: ChargePolicy?,
        lastRequestedAt: Long,
    ): RuleDecision {
        val byId = rules.associateBy { it.id }
        // One definition of "this rule is in play right now", used for both the winner selection and
        // the suspension cohort: a rule that cannot act must neither activate NOR keep the cohort
        // closed against the rules that can.
        fun ChargeRule.isEffective() = enabled &&
            policy != null &&
            // A policy this adapter does not offer would be refused by the write path anyway;
            // matching on it would park the layer in a permanently failing pending phase.
            policyId in supportedPolicyIds &&
            matches(snapshot)

        val matching = rules.filter { it.isEffective() }
        // A suspended rule that no longer exists, can no longer act, or no longer matches has served
        // its purpose: the user has moved on from the situation their manual override belonged to.
        val suspended = runtime.suspendedRuleIds.filter { id -> byId[id]?.isEffective() == true }.toSet()
        val blocked = suspended.isNotEmpty()

        // A live session outranks everything: it owns the policy and — via the baseline handed to
        // ChargeSessionManager.begin — the restore too. Bookkeeping left ACTIVE here is the crash
        // window between the session being persisted and the rules layer being cleared.
        if (sessionActive) {
            val action = if (runtime.phase != RulePhase.IDLE) {
                RuleAction.ClearActiveBookkeeping
            } else {
                RuleAction.Noop
            }
            return RuleDecision(action, suspended)
        }

        val winner = matching.firstOrNull()
        val activeRule = runtime.activeRuleId?.let { byId[it] }
        val activeStillWins = activeRule != null && winner?.id == activeRule.id

        // An unconfirmed write is owed before anything else is decided: the persisted intent says a
        // policy may or may not have landed, and every other branch below assumes it knows what is
        // configured.
        if (runtime.isPending) {
            val target = runtime.targetPolicy
            when (runtime.phase) {
                RulePhase.RESTORE_PENDING -> {
                    // A rule winning again while a restore is owed makes the restore obsolete: write
                    // the rule's policy instead, keeping the same baseline still owed underneath.
                    val baseline = runtime.baselinePolicy ?: target
                    if (winner != null && !blocked && proSettled && baseline != null) {
                        return RuleDecision(
                            RuleAction.Switch(winner, winner.policy!!, baseline),
                            suspended,
                        )
                    }
                    if (target != null) return RuleDecision(RuleAction.ReconcilePending(target), suspended)
                }
                RulePhase.APPLY_PENDING -> {
                    // Still the winner: finish the write. Otherwise the pending transition is
                    // obsolete and we fall through, treating the state as ACTIVE — the write may
                    // have landed, so the baseline is still owed and must be resolved normally.
                    if (activeStillWins && target != null) {
                        return RuleDecision(RuleAction.ReconcilePending(target), suspended)
                    }
                }
                else -> Unit
            }
        }

        val ownsPolicy = runtime.phase != RulePhase.IDLE

        if (ownsPolicy) {
            val baseline = runtime.baselinePolicy
            // Divergence is claimed only from a SETTLED activation: while a write is still pending,
            // "configured != target" is the ordinary look of a write that failed or hasn't landed,
            // and treating that as the user's choice would suspend every rule.
            val settledActive = runtime.phase == RulePhase.ACTIVE
            val written = runtime.targetPolicy ?: activeRule?.policy

            // (1) The configured state itself contradicts what the rules layer wrote. A readable but
            // UNRECOGNIZED value counts: the rules layer only ever writes values Amply can name, so
            // an unnameable one is by definition somebody else's — and it must not be overwritten,
            // which is exactly what carrying on as if the rule still owned the policy would do.
            // Without any readback nothing is claimed: "different" would be indistinguishable from
            // "unreadable", and adopting on a guess abandons a perfectly good activation.
            val readbackDiverged = settledActive && when {
                configured is ChargeObservation.Verified -> written != null && configured.policy != written
                configured is ChargeObservation.Unknown -> configured.unrecognizedValue
                else -> false
            }

            // (2) Amply's own write journal moved past the rules layer. Every write path — a session
            // override or its restore, a failed override's rollback, boot-recovery rewrites, an
            // explicit persistent policy — records into that journal (under NonCancellable, after
            // the physical write), so an entry that is NEWER than this activation and names a
            // DIFFERENT policy proves another component overwrote the rule's policy. This is the
            // only divergence signal available on adapters that cannot read their state back at all.
            val journalDiverged = settledActive &&
                lastRequestedAt > runtime.lastWriteAt &&
                lastRequestedPolicy != null &&
                lastRequestedPolicy != runtime.targetPolicy

            if (readbackDiverged || journalDiverged) {
                // Adopt what is actually configured (no restore — the newer choice wins) and suspend
                // every rule that currently matches, not just the winner: otherwise the
                // second-priority rule would immediately re-apply over the same choice.
                val cohort = matching.map { it.id }.toSet()
                return RuleDecision(RuleAction.AdoptExternal(cohort), cohort)
            }
            if (baseline == null) {
                // Nothing recorded to restore to: don't invent one, just drop the bookkeeping.
                return RuleDecision(RuleAction.ClearActiveBookkeeping, suspended)
            }
            if (winner == null) return RuleDecision(RuleAction.Restore(baseline), suspended)
            // A lapsed entitlement stops new activations and switches, but never a deactivation: the
            // user's own baseline must always be restorable. An active rule that still matches keeps
            // running — ending it would change the charging policy over a billing state.
            if (!proSettled) {
                val activeStillMatches = activeRule != null && matching.any { it.id == activeRule.id }
                val action = if (activeStillMatches) RuleAction.Noop else RuleAction.Restore(baseline)
                return RuleDecision(action, suspended)
            }
            val targetPolicy = winner.policy!!
            // Also covers editing the active rule's policy: same rule, different target.
            if (activeStillWins && settledActive && runtime.targetPolicyId == winner.policyId) {
                return RuleDecision(RuleAction.Noop, suspended)
            }
            return RuleDecision(RuleAction.Switch(winner, targetPolicy, baseline), suspended)
        }

        if (winner == null || blocked || !proSettled) return RuleDecision(RuleAction.Noop, suspended)

        val baseline = resolveBaseline(configured, lastPersistent, defaultProtective)
            ?: return RuleDecision(RuleAction.Noop, suspended)
        return RuleDecision(RuleAction.Activate(winner, winner.policy!!, baseline), suspended)
    }

    /**
     * What the rules layer owes back once it stops applying.
     *
     * Order matters and is not interchangeable: a fresh readback of what is configured *right now*
     * beats Amply's journal, which can be arbitrarily stale (the user may have changed the policy in
     * the OEM's own settings since). The journal and the adapter default are used **only** when the
     * current state is genuinely unreadable.
     *
     * Returns null for a readable-but-unrecognized native value: a mode Amply cannot name is a mode
     * it cannot put back, and replacing it would silently destroy the user's configuration.
     */
    private fun resolveBaseline(
        configured: ChargeObservation?,
        lastPersistent: ChargePolicy?,
        defaultProtective: ChargePolicy,
    ): ChargePolicy? = when {
        configured is ChargeObservation.Verified -> configured.policy
        configured is ChargeObservation.Unknown && configured.unrecognizedValue -> null
        else -> lastPersistent ?: defaultProtective
    }
}

/**
 * Whether this rule's condition holds right now. A rule whose policy this build cannot decode never
 * matches — the caller filters on that before asking, and the winner selection relies on it.
 */
internal fun ChargeRule.matches(snapshot: ConditionSnapshot): Boolean = when (val test = condition) {
    is RuleCondition.BluetoothDevice ->
        snapshot.btAddresses.contains(normalizeBtAddress(test.address))
    is RuleCondition.ChargerType ->
        snapshot.chargerTypeSupported &&
            snapshot.plugged &&
            snapshot.plugKind != null &&
            test.types.contains(snapshot.plugKind)
}
