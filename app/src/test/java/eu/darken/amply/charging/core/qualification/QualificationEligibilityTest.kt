package eu.darken.amply.charging.core.qualification

import android.content.Context
import android.content.Intent
import eu.darken.amply.charging.core.ChargeObservation
import eu.darken.amply.charging.core.ChargePolicy
import eu.darken.amply.charging.core.DeviceInfo
import eu.darken.amply.charging.core.access.AccessBackend
import eu.darken.amply.charging.core.adapter.AdapterSupport
import eu.darken.amply.charging.core.adapter.ChargingAdapter
import eu.darken.amply.charging.core.enforcement.EnforcementEvidence
import eu.darken.amply.charging.core.enforcement.EnforcementEvidenceState
import eu.darken.amply.charging.core.enforcement.EnforcementStatus
import eu.darken.amply.charging.core.enforcement.EnforcementVerdict
import eu.darken.amply.charging.core.enforcement.EnforcementVerdictEngine
import eu.darken.amply.common.ca.toCaString
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test

class QualificationEligibilityTest {

    private class FakeAdapter(
        override val id: String = "fake",
        override val supportedPolicies: List<ChargePolicy> = listOf(
            ChargePolicy.FixedLimit(70),
            ChargePolicy.FixedLimit(80),
            ChargePolicy.FixedLimit(90),
            ChargePolicy.Unrestricted,
        ),
        override val enforcementEvidenceRequired: Boolean = true,
        override val policyLatchesAtPlug: Boolean = false,
    ) : ChargingAdapter {
        override val displayName = "Fake".toCaString()
        override fun probe(device: DeviceInfo) = AdapterSupport(true, true, 0)
        override suspend fun read(backend: AccessBackend): ChargeObservation =
            ChargeObservation.Unknown("".toCaString())

        override suspend fun apply(policy: ChargePolicy, backend: AccessBackend) = true
        override fun nativeSettingsIntent(context: Context) = Intent()
    }

    private fun support(
        controlEnabled: Boolean = true,
        enforcement: EnforcementStatus? = EnforcementStatus.CANDIDATE,
    ) = AdapterSupport(
        matched = true,
        controlEnabled = controlEnabled,
        detail = 0,
        enforcement = enforcement,
    )

    private fun eligibility(
        adapter: ChargingAdapter? = FakeAdapter(),
        support: AdapterSupport? = support(),
        evidence: EnforcementEvidenceState = EnforcementEvidenceState.Absent,
        plugged: Boolean = true,
        percent: Int = 80,
        accessReady: Boolean = true,
        sessionActive: Boolean = false,
        pendingRecovery: Boolean = false,
        ruleOwnsPolicy: Boolean = false,
        baselineReadable: Boolean = true,
    ) = qualificationEligibility(
        adapter = adapter,
        support = support,
        evidence = evidence,
        plugged = plugged,
        percent = percent,
        accessReady = accessReady,
        sessionActive = sessionActive,
        pendingRecovery = pendingRecovery,
        ruleOwnsPolicy = ruleOwnsPolicy,
        baselineReadable = baselineReadable,
    )

    private fun refuted() = EnforcementEvidenceState.Present(
        EnforcementEvidence(
            adapterId = "fake",
            buildIdentity = "build-a",
            algorithmVersion = EnforcementVerdictEngine.ALGORITHM_VERSION,
            verdict = EnforcementVerdict.REFUTED,
        ),
    )

    @Test
    fun `a gated adapter on a plugged device is eligible`() {
        val result = eligibility()

        result.shouldBeInstanceOf<RunEligibility.Eligible>()
        result.plan.shape shouldBe RunShape.VARIABLE_CAP
    }

    /**
     * The flag the run's cap-mismatch protection hangs off. It has to travel with the plan: a run
     * whose commanded values are a guess may never refute, and a second copy of that answer at the
     * start call site would eventually disagree with this one. No path produces a candidate run yet,
     * so the answer here is false — what matters is that it comes from here.
     */
    @Test
    fun `an eligible run carries whether its value mapping is a guess`() {
        val result = eligibility()

        result.shouldBeInstanceOf<RunEligibility.Eligible>()
        result.isCandidate shouldBe false
    }

    @Test
    fun `no adapter means nothing to drive`() {
        eligibility(adapter = null) shouldBe RunEligibility.Ineligible(IneligibleReason.NO_ADAPTER)
    }

    @Test
    fun `an adapter with no policies is a lab adapter and cannot be driven`() {
        eligibility(adapter = FakeAdapter(supportedPolicies = emptyList())) shouldBe
            RunEligibility.Ineligible(IneligibleReason.NO_ADAPTER)
    }

    @Test
    fun `an adapter whose gate never asked about enforcement has nothing to prove`() {
        eligibility(adapter = FakeAdapter(enforcementEvidenceRequired = false)) shouldBe
            RunEligibility.Ineligible(IneligibleReason.NOTHING_TO_PROVE)
    }

    @Test
    fun `a maintainer-qualified device has nothing to prove`() {
        eligibility(support = support(enforcement = EnforcementStatus.CONFIRMED)) shouldBe
            RunEligibility.Ineligible(IneligibleReason.NOTHING_TO_PROVE)
    }

    /**
     * GrapheneOS. The ROM samples the setting only at plug-session start, so the cut/resume/cut
     * sequence has no hardware effect at all without unplugging between every phase.
     */
    @Test
    fun `a plug-latched adapter is excluded`() {
        eligibility(adapter = FakeAdapter(policyLatchesAtPlug = true)) shouldBe
            RunEligibility.Ineligible(IneligibleReason.LATCHES_AT_PLUG)
    }

    @Test
    fun `a refuted build has nothing left to measure`() {
        eligibility(evidence = refuted()) shouldBe RunEligibility.Ineligible(IneligibleReason.REFUTED)
    }

    @Test
    fun `a corrupt evidence record is treated as a refutation`() {
        eligibility(evidence = EnforcementEvidenceState.Corrupt) shouldBe
            RunEligibility.Ineligible(IneligibleReason.REFUTED)
    }

    /** A secondary user or a missing provider: the probe refused for a reason a run cannot lift. */
    @Test
    fun `a probe refusal is not something a run can fix`() {
        eligibility(support = support(controlEnabled = false, enforcement = null)) shouldBe
            RunEligibility.Ineligible(IneligibleReason.CONTROL_UNAVAILABLE)
    }

    @Test
    fun `an adaptive-only adapter has no testable cap`() {
        eligibility(
            adapter = FakeAdapter(
                supportedPolicies = listOf(ChargePolicy.Adaptive, ChargePolicy.Unrestricted),
            ),
        ) shouldBe RunEligibility.Ineligible(IneligibleReason.NO_TESTABLE_CAP)
    }

    @Test
    fun `a 100 percent cap limits nothing and does not count`() {
        eligibility(
            adapter = FakeAdapter(
                supportedPolicies = listOf(ChargePolicy.FixedLimit(100), ChargePolicy.Unrestricted),
            ),
        ) shouldBe RunEligibility.Ineligible(IneligibleReason.NO_TESTABLE_CAP)
    }

    @Test
    fun `the preconditions each have their own reason`() {
        eligibility(accessReady = false) shouldBe RunEligibility.Ineligible(IneligibleReason.ACCESS_NOT_READY)
        eligibility(sessionActive = true) shouldBe RunEligibility.Ineligible(IneligibleReason.SESSION_ACTIVE)
        eligibility(pendingRecovery = true) shouldBe RunEligibility.Ineligible(IneligibleReason.RECOVERY_PENDING)
        eligibility(plugged = false) shouldBe RunEligibility.Ineligible(IneligibleReason.NOT_CHARGING)
    }

    /**
     * A rule's policy is not the user's setting. Measuring it would capture the rule's temporary
     * value as the baseline, restore it persistently at the end, and leave nothing remembering what
     * the rule still owed back.
     */
    @Test
    fun `a run refuses to start while a charge rule owns the policy`() {
        eligibility(ruleOwnsPolicy = true) shouldBe RunEligibility.Ineligible(IneligibleReason.RULE_ACTIVE)
    }

    /**
     * Not knowing what to put back means not touching it. The alternative — falling back to the
     * adapter's protective default — would replace an unrecognized native mode with 80% permanently.
     */
    @Test
    fun `a run refuses to start when the current policy cannot be read`() {
        eligibility(baselineReadable = false) shouldBe
            RunEligibility.Ineligible(IneligibleReason.BASELINE_UNREADABLE)
    }

    /**
     * The refusal carries the level a run would need, so the pre-check can name the number instead of
     * saying "too low". 73 for these caps: the lowest tick is 70 and a variable-cap run has to start
     * [QualificationProtocol.VARIABLE_CAP_UNDERSHOOT] above it.
     */
    @Test
    fun `a battery below the lowest cap cannot host a variable-cap run yet`() {
        eligibility(percent = 60) shouldBe
            RunEligibility.Ineligible(IneligibleReason.BATTERY_LEVEL, requiredPercent = 73)
    }

    /**
     * Deliberately absent up here: the way out of a too-full battery is to discharge, and a "needed"
     * level would read as a target to charge towards.
     */
    @Test
    fun `a too-full battery carries no required level`() {
        val result = eligibility(percent = 100)

        result.shouldBeInstanceOf<RunEligibility.Ineligible>()
        result.requiredPercent shouldBe null
    }

    @Test
    fun `the required level is the lowest one that yields a plan`() {
        minimumStartPercent(
            caps = listOf(70, 75, 80, 85, 90, 95),
            policies = listOf(ChargePolicy.Unrestricted),
        ) shouldBe 73
    }

    /** A single-tick adapter plans at any level, so there is no level requirement to show. */
    @Test
    fun `a single-tick adapter has no meaningful level requirement`() {
        minimumStartPercent(
            caps = listOf(80),
            policies = listOf(ChargePolicy.Unrestricted),
        ) shouldBe 0
    }

    @Test
    fun `an adapter that can neither cap below nor release has no start level at all`() {
        minimumStartPercent(
            caps = listOf(80),
            policies = listOf(ChargePolicy.FixedLimit(80)),
        ) shouldBe null
    }

    /**
     * Refused before a plan is even resolved: near full, a stopped battery is not evidence of a cap,
     * so a run up here could stage a cut → resume → cut out of an ordinary end-of-charge.
     */
    @Test
    fun `a nearly full battery cannot host a run at all`() {
        eligibility(percent = QualificationProtocol.NEAR_FULL_PERCENT) shouldBe
            RunEligibility.Ineligible(IneligibleReason.BATTERY_TOO_FULL)
        eligibility(percent = 100) shouldBe RunEligibility.Ineligible(IneligibleReason.BATTERY_TOO_FULL)
    }

    @Test
    fun `just below the near-full threshold is still eligible`() {
        eligibility(percent = QualificationProtocol.NEAR_FULL_PERCENT - 1)
            .shouldBeInstanceOf<RunEligibility.Eligible>()
    }

    @Test
    fun `a variable-cap plan caps below the current level and releases above it`() {
        val plan = resolvePlan(
            caps = listOf(70, 75, 80, 85, 90, 95),
            policies = listOf(ChargePolicy.Unrestricted),
            percent = 80,
        )!!

        plan.shape shouldBe RunShape.VARIABLE_CAP
        plan.lowCap shouldBe 75
        plan.releasePolicy shouldBe ChargePolicy.FixedLimit(85)
    }

    @Test
    fun `near the top of the range the release step removes the cap instead`() {
        val plan = resolvePlan(
            caps = listOf(70, 75, 80, 85, 90, 95),
            policies = listOf(ChargePolicy.Unrestricted),
            percent = 95,
        )!!

        plan.lowCap shouldBe 90
        plan.releasePolicy shouldBe ChargePolicy.Unrestricted
    }

    @Test
    fun `a single-tick adapter releases by removing the cap`() {
        val plan = resolvePlan(
            caps = listOf(80),
            policies = listOf(ChargePolicy.Unrestricted),
            percent = 50,
        )!!

        plan.shape shouldBe RunShape.FIXED_CAP
        plan.lowCap shouldBe 80
        plan.releasePolicy shouldBe ChargePolicy.Unrestricted
    }

    @Test
    fun `an adapter that cannot remove its only cap cannot be released`() {
        resolvePlan(caps = listOf(80), policies = emptyList(), percent = 50) shouldBe null
    }

    @Test
    fun `an unknown battery level has no plan`() {
        resolvePlan(caps = listOf(70, 80), policies = listOf(ChargePolicy.Unrestricted), percent = -1) shouldBe null
    }
}
