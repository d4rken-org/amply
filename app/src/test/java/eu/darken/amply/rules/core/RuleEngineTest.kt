package eu.darken.amply.rules.core

import eu.darken.amply.charging.core.BackendKind
import eu.darken.amply.charging.core.ChargeObservation
import eu.darken.amply.charging.core.ChargePolicy
import eu.darken.amply.common.ca.toCaString
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test

/**
 * The whole precedence stack lives in [RuleEngine], so this is where the safety properties are
 * pinned: never overwrite a state Amply cannot restore, never lose the user's baseline, never
 * re-apply over a choice the user just made by hand.
 */
class RuleEngineTest {

    private val limit80 = ChargePolicy.FixedLimit(80)
    private val limit90 = ChargePolicy.FixedLimit(90)
    private val adaptive = ChargePolicy.Adaptive
    private val full = ChargePolicy.Unrestricted

    private val carAddress = "AA:BB:CC:DD:EE:FF"

    private fun btRule(
        id: String,
        address: String = carAddress,
        policy: ChargePolicy = limit80,
        enabled: Boolean = true,
    ) = ChargeRule(
        id = id,
        enabled = enabled,
        label = id,
        condition = RuleCondition.BluetoothDevice(address),
        policyId = policy.stableId,
    )

    private fun chargerRule(
        id: String,
        types: Set<PlugKind> = setOf(PlugKind.AC),
        policy: ChargePolicy = full,
        enabled: Boolean = true,
    ) = ChargeRule(
        id = id,
        enabled = enabled,
        label = id,
        condition = RuleCondition.ChargerType(types),
        policyId = policy.stableId,
    )

    private fun connected(vararg addresses: String) = ConditionSnapshot(btAddresses = addresses.toSet())

    private fun pluggedInto(kind: PlugKind, chargerTypeSupported: Boolean = true) = ConditionSnapshot(
        plugged = true,
        plugKind = kind,
        chargerTypeSupported = chargerTypeSupported,
    )

    private fun evaluate(
        rules: List<ChargeRule>,
        snapshot: ConditionSnapshot = ConditionSnapshot(),
        runtime: RuleRuntimeState = RuleRuntimeState(),
        configured: ChargeObservation? = null,
        sessionActive: Boolean = false,
        proSettled: Boolean = true,
        lastPersistent: ChargePolicy? = null,
        defaultProtective: ChargePolicy = limit80,
        supportedPolicyIds: Set<String> = emptySet(),
        lastRequestedPolicy: ChargePolicy? = null,
        lastRequestedAt: Long = 0L,
    ) = RuleEngine.evaluate(
        rules = rules,
        snapshot = snapshot,
        runtime = runtime,
        configured = configured,
        sessionActive = sessionActive,
        proSettled = proSettled,
        lastPersistent = lastPersistent,
        defaultProtective = defaultProtective,
        supportedPolicyIds = supportedPolicyIds,
        lastRequestedPolicy = lastRequestedPolicy,
        lastRequestedAt = lastRequestedAt,
    )

    private fun activeOn(rule: ChargeRule, baseline: ChargePolicy = limit80) = RuleRuntimeState(
        phase = RulePhase.ACTIVE,
        targetPolicyId = rule.policyId,
        activeRuleId = rule.id,
        baselinePolicyId = baseline.stableId,
    )

    @Test
    fun `the first matching enabled rule wins regardless of kind`() {
        val charge = btRule("charge", policy = full)
        val protect = btRule("protect", policy = adaptive)

        val decision = evaluate(listOf(charge, protect), connected(carAddress))

        decision.action.shouldBeInstanceOf<RuleAction.Activate>().rule.id shouldBe "charge"

        // Order alone decides — flipping it flips the winner, across the kind boundary.
        evaluate(listOf(protect, charge), connected(carAddress))
            .action.shouldBeInstanceOf<RuleAction.Activate>().rule.id shouldBe "protect"
    }

    @Test
    fun `a bluetooth rule matches while unplugged`() {
        val decision = evaluate(listOf(btRule("a")), connected(carAddress))

        decision.action.shouldBeInstanceOf<RuleAction.Activate>()
    }

    @Test
    fun `bluetooth addresses match case-insensitively`() {
        val rule = btRule("a", address = carAddress.lowercase())

        evaluate(listOf(rule), connected(carAddress)).action.shouldBeInstanceOf<RuleAction.Activate>()
    }

    @Test
    fun `a bluetooth rule stops matching once the device is gone`() {
        val rule = btRule("a")

        evaluate(listOf(rule), connected("11:22:33:44:55:66"), runtime = activeOn(rule)).action shouldBe
            RuleAction.Restore(limit80)
    }

    @Test
    fun `a charger rule needs both power and a listed plug kind`() {
        val rule = chargerRule("a", types = setOf(PlugKind.AC))

        evaluate(listOf(rule), pluggedInto(PlugKind.AC)).action.shouldBeInstanceOf<RuleAction.Activate>()
        evaluate(listOf(rule), pluggedInto(PlugKind.USB)).action shouldBe RuleAction.Noop
        // Unplugged: a charger-type condition is about being on that charger, nothing else.
        evaluate(listOf(rule), ConditionSnapshot(plugged = false, plugKind = null)).action shouldBe RuleAction.Noop
    }

    @Test
    fun `a charger rule with no selected type matches nothing`() {
        val rule = chargerRule("a", types = emptySet())

        evaluate(listOf(rule), pluggedInto(PlugKind.AC)).action shouldBe RuleAction.Noop
    }

    @Test
    fun `charger rules never match on a plug-latched adapter`() {
        // The ROM samples the policy at plug time, so a write triggered BY the plug always lands too
        // late to have any effect. Such a rule must never claim to be doing something.
        val rule = chargerRule("a")

        evaluate(listOf(rule), pluggedInto(PlugKind.AC, chargerTypeSupported = false)).action shouldBe
            RuleAction.Noop
    }

    @Test
    fun `disabled rules and rules with an unreadable policy never win`() {
        val disabled = btRule("disabled", enabled = false)
        val unreadable = ChargeRule(
            id = "future",
            condition = RuleCondition.BluetoothDevice(carAddress),
            policyId = "vendor-mode-7",
        )
        val usable = btRule("usable", policy = adaptive)

        evaluate(listOf(disabled, unreadable, usable), connected(carAddress))
            .action.shouldBeInstanceOf<RuleAction.Activate>().rule.id shouldBe "usable"
    }

    @Test
    fun `activation adopts the freshly read policy as the baseline, over a stale journal`() {
        val decision = evaluate(
            rules = listOf(btRule("a", policy = full)),
            snapshot = connected(carAddress),
            configured = ChargeObservation.Verified(limit90, BackendKind.SHIZUKU),
            // The journal is what Amply last wrote; the user may have changed it natively since.
            lastPersistent = limit80,
        )

        decision.action.shouldBeInstanceOf<RuleAction.Activate>().baseline shouldBe limit90
    }

    @Test
    fun `an unreadable state falls back to the journal, then to the adapter default`() {
        val rules = listOf(btRule("a", policy = full))

        evaluate(rules, connected(carAddress), configured = null, lastPersistent = adaptive)
            .action.shouldBeInstanceOf<RuleAction.Activate>().baseline shouldBe adaptive

        evaluate(rules, connected(carAddress), configured = null, lastPersistent = null, defaultProtective = limit90)
            .action.shouldBeInstanceOf<RuleAction.Activate>().baseline shouldBe limit90
    }

    @Test
    fun `a readable but unrecognized native value refuses activation`() {
        // Amply cannot name this mode, so it cannot put it back — overwriting it would destroy the
        // user's configuration with no way to reproduce it.
        val decision = evaluate(
            rules = listOf(btRule("a", policy = full)),
            snapshot = connected(carAddress),
            configured = ChargeObservation.Unknown("odd".toCaString(), unrecognizedValue = true),
        )

        decision.action shouldBe RuleAction.Noop
    }

    @Test
    fun `a higher-priority rule takes over while keeping the original baseline`() {
        val top = btRule("top", policy = full)
        val low = btRule("low", policy = adaptive)

        val decision = evaluate(
            rules = listOf(top, low),
            snapshot = connected(carAddress),
            runtime = activeOn(low, baseline = limit90),
            // What is configured now is the ACTIVE RULE's override, never a baseline candidate.
            configured = ChargeObservation.Verified(adaptive, BackendKind.SHIZUKU),
        )

        val switch = decision.action.shouldBeInstanceOf<RuleAction.Switch>()
        switch.rule.id shouldBe "top"
        switch.baseline shouldBe limit90
    }

    @Test
    fun `editing the active rule's policy re-applies it`() {
        val rule = btRule("a", policy = adaptive)
        val runtime = activeOn(rule).copy(targetPolicyId = full.stableId)

        val decision = evaluate(listOf(rule), connected(carAddress), runtime = runtime)

        decision.action.shouldBeInstanceOf<RuleAction.Switch>().policy shouldBe adaptive
    }

    @Test
    fun `a settled activation that still wins does nothing`() {
        val rule = btRule("a")

        evaluate(listOf(rule), connected(carAddress), runtime = activeOn(rule)).action shouldBe RuleAction.Noop
    }

    @Test
    fun `an external change is adopted and suspends every matching rule`() {
        val top = btRule("top", policy = adaptive)
        val other = btRule("other", policy = limit90)
        val unmatched = chargerRule("charger")

        val decision = evaluate(
            rules = listOf(top, other, unmatched),
            snapshot = connected(carAddress),
            runtime = activeOn(top),
            // Neither the rule's policy nor the baseline: somebody else wrote this.
            configured = ChargeObservation.Verified(full, BackendKind.SHIZUKU),
        )

        // The whole matching cohort, not just the winner: otherwise `other` would re-apply over the
        // user's fresh choice on the very next tick.
        decision.action shouldBe RuleAction.AdoptExternal(setOf("top", "other"))
        decision.suspendedRuleIds shouldBe setOf("top", "other")
    }

    @Test
    fun `an unrecognized configured value is external divergence, not a rule's own state`() {
        // The rules layer only ever writes values Amply can name, so an unnameable one is by
        // definition somebody else's — and carrying on as if the rule owned the policy would end in
        // overwriting a mode Amply cannot reproduce.
        val top = btRule("top", policy = adaptive)
        val other = btRule("other", policy = limit90)

        val decision = evaluate(
            rules = listOf(top, other),
            snapshot = connected(carAddress),
            runtime = activeOn(top),
            configured = ChargeObservation.Unknown("vendor mode 7".toCaString(), unrecognizedValue = true),
        )

        decision.action shouldBe RuleAction.AdoptExternal(setOf("top", "other"))
        decision.suspendedRuleIds shouldBe setOf("top", "other")
    }

    @Test
    fun `a merely unreadable state is not divergence`() {
        val rule = btRule("a", policy = adaptive)

        evaluate(
            rules = listOf(rule),
            snapshot = connected(carAddress),
            runtime = activeOn(rule),
            configured = ChargeObservation.Unknown("no backend".toCaString()),
        ).action shouldBe RuleAction.Noop
    }

    @Test
    fun `a newer journal entry naming another policy is external divergence`() {
        // Every Amply write path records into the shared journal after the physical write, so an
        // entry newer than this activation naming a different policy proves something else wrote
        // past the rules layer. This is the only divergence signal on adapters with no readback.
        val top = btRule("top", policy = adaptive)
        val other = btRule("other", policy = limit90)
        val runtime = activeOn(top).copy(lastWriteAt = 1_000L)

        val decision = evaluate(
            rules = listOf(top, other),
            snapshot = connected(carAddress),
            runtime = runtime,
            lastRequestedPolicy = full,
            lastRequestedAt = 2_000L,
        )

        decision.action shouldBe RuleAction.AdoptExternal(setOf("top", "other"))
    }

    @Test
    fun `the rules layer's own write is not divergence`() {
        val rule = btRule("a", policy = adaptive)
        val runtime = activeOn(rule).copy(lastWriteAt = 1_000L)

        // Same policy, newer stamp: this is exactly what the rules layer's own write looks like once
        // the repository's journal entry lands (the stamp is copied from it, so equality is normal).
        evaluate(
            rules = listOf(rule),
            snapshot = connected(carAddress),
            runtime = runtime,
            lastRequestedPolicy = adaptive,
            lastRequestedAt = 2_000L,
        ).action shouldBe RuleAction.Noop

        // A journal entry OLDER than the activation is history, not an overwrite.
        evaluate(
            rules = listOf(rule),
            snapshot = connected(carAddress),
            runtime = runtime,
            lastRequestedPolicy = full,
            lastRequestedAt = 500L,
        ).action shouldBe RuleAction.Noop
    }

    @Test
    fun `a policy this adapter cannot apply never matches`() {
        // Decodable, but not in the adapter's list: matching on it would park the layer in a
        // permanently failing pending phase, since the write path refuses it.
        val unsupported = btRule("unsupported", policy = ChargePolicy.PauseAtFull)
        val usable = btRule("usable", policy = adaptive)
        val supported = setOf(adaptive.stableId, limit80.stableId)

        evaluate(listOf(unsupported), connected(carAddress), supportedPolicyIds = supported)
            .action shouldBe RuleAction.Noop

        evaluate(listOf(unsupported, usable), connected(carAddress), supportedPolicyIds = supported)
            .action.shouldBeInstanceOf<RuleAction.Activate>().rule.id shouldBe "usable"

        // An empty set means adapter selection has not resolved yet, which must not disable rules.
        evaluate(listOf(unsupported), connected(carAddress), supportedPolicyIds = emptySet())
            .action.shouldBeInstanceOf<RuleAction.Activate>()
    }

    @Test
    fun `divergence is never claimed without a readback`() {
        val rule = btRule("a", policy = adaptive)

        evaluate(
            rules = listOf(rule),
            snapshot = connected(carAddress),
            runtime = activeOn(rule),
            configured = ChargeObservation.LastRequested(full),
        ).action shouldBe RuleAction.Noop
    }

    @Test
    fun `a suspended rule blocks activation while it still matches`() {
        val rule = btRule("a")
        val runtime = RuleRuntimeState(suspendedRuleIds = setOf("a"))

        val decision = evaluate(listOf(rule), connected(carAddress), runtime = runtime)

        decision.action shouldBe RuleAction.Noop
        decision.suspendedRuleIds shouldBe setOf("a")
    }

    @Test
    fun `a suspended cohort clears once none of its rules match`() {
        val rule = btRule("a")
        val runtime = RuleRuntimeState(suspendedRuleIds = setOf("a"))

        val decision = evaluate(listOf(rule), ConditionSnapshot(), runtime = runtime)

        decision.suspendedRuleIds.shouldBeEmpty()
    }

    @Test
    fun `a suspended rule that is deleted or disabled stops holding the cohort`() {
        val runtime = RuleRuntimeState(suspendedRuleIds = setOf("a"))

        evaluate(emptyList(), connected(carAddress), runtime = runtime).suspendedRuleIds.shouldBeEmpty()
        evaluate(listOf(btRule("a", enabled = false)), connected(carAddress), runtime = runtime)
            .suspendedRuleIds.shouldBeEmpty()
    }

    @Test
    fun `clearing the cohort and acting on the new winner happen in one pass`() {
        // `car` is suspended and no longer matches; `desk` matches now. A two-pass design would idle
        // here until the next tick.
        val car = btRule("car")
        val desk = btRule("desk", address = "11:22:33:44:55:66", policy = adaptive)
        val runtime = RuleRuntimeState(suspendedRuleIds = setOf("car"))

        val decision = evaluate(listOf(car, desk), connected("11:22:33:44:55:66"), runtime = runtime)

        decision.suspendedRuleIds.shouldBeEmpty()
        decision.action.shouldBeInstanceOf<RuleAction.Activate>().rule.id shouldBe "desk"
    }

    @Test
    fun `an unconfirmed apply is re-issued while its rule still wins`() {
        val rule = btRule("a", policy = adaptive)
        val runtime = RuleRuntimeState(
            phase = RulePhase.APPLY_PENDING,
            targetPolicyId = adaptive.stableId,
            activeRuleId = "a",
            baselinePolicyId = limit80.stableId,
        )

        evaluate(listOf(rule), connected(carAddress), runtime = runtime).action shouldBe
            RuleAction.ReconcilePending(adaptive)
    }

    @Test
    fun `an unconfirmed apply whose rule stopped matching resolves to the baseline`() {
        // The write may or may not have landed, so the baseline is still owed either way.
        val rule = btRule("a", policy = adaptive)
        val runtime = RuleRuntimeState(
            phase = RulePhase.APPLY_PENDING,
            targetPolicyId = adaptive.stableId,
            activeRuleId = "a",
            baselinePolicyId = limit90.stableId,
        )

        evaluate(listOf(rule), ConditionSnapshot(), runtime = runtime).action shouldBe
            RuleAction.Restore(limit90)
    }

    @Test
    fun `an unconfirmed restore is re-issued`() {
        val runtime = RuleRuntimeState(
            phase = RulePhase.RESTORE_PENDING,
            targetPolicyId = limit90.stableId,
            baselinePolicyId = limit90.stableId,
        )

        evaluate(emptyList(), ConditionSnapshot(), runtime = runtime).action shouldBe
            RuleAction.ReconcilePending(limit90)
    }

    @Test
    fun `an unconfirmed restore yields to a rule that matches again`() {
        val rule = btRule("a", policy = adaptive)
        val runtime = RuleRuntimeState(
            phase = RulePhase.RESTORE_PENDING,
            targetPolicyId = limit90.stableId,
            baselinePolicyId = limit90.stableId,
        )

        val switch = evaluate(listOf(rule), connected(carAddress), runtime = runtime)
            .action.shouldBeInstanceOf<RuleAction.Switch>()
        switch.policy shouldBe adaptive
        // Still owed underneath, unchanged.
        switch.baseline shouldBe limit90
    }

    @Test
    fun `an active session clears rule bookkeeping without writing`() {
        // The session was handed the baseline and owns the restore now; this covers the crash window
        // between the session being persisted and the rules layer being cleared.
        val rule = btRule("a")

        evaluate(
            rules = listOf(rule),
            snapshot = connected(carAddress),
            runtime = activeOn(rule),
            sessionActive = true,
        ).action shouldBe RuleAction.ClearActiveBookkeeping

        evaluate(listOf(rule), connected(carAddress), sessionActive = true).action shouldBe RuleAction.Noop
    }

    @Test
    fun `a lapsed entitlement blocks new activations and switches`() {
        val top = btRule("top", policy = full)
        val low = btRule("low", policy = adaptive)

        evaluate(listOf(top), connected(carAddress), proSettled = false).action shouldBe RuleAction.Noop
        evaluate(
            rules = listOf(top, low),
            snapshot = connected(carAddress),
            runtime = activeOn(low),
            proSettled = false,
        ).action shouldBe RuleAction.Noop
    }

    @Test
    fun `a lapsed entitlement still restores the baseline`() {
        val rule = btRule("a", policy = adaptive)

        evaluate(
            rules = listOf(rule),
            snapshot = ConditionSnapshot(),
            runtime = activeOn(rule, baseline = limit90),
            proSettled = false,
        ).action shouldBe RuleAction.Restore(limit90)
    }

    @Test
    fun `an activation with no recorded baseline is dropped rather than guessed at`() {
        val rule = btRule("a")
        val runtime = RuleRuntimeState(phase = RulePhase.ACTIVE, activeRuleId = "a")

        evaluate(listOf(rule), ConditionSnapshot(), runtime = runtime).action shouldBe
            RuleAction.ClearActiveBookkeeping
    }
}
