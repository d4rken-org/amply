package eu.darken.amply.charging.core.qualification

import eu.darken.amply.charging.core.ChargePolicy
import eu.darken.amply.charging.core.adapter.ChargingAdapter
import eu.darken.amply.charging.core.enforcement.EnforcementEvidenceState
import eu.darken.amply.charging.core.enforcement.EnforcementStatus
import eu.darken.amply.charging.core.enforcement.EnforcementVerdict
import eu.darken.amply.charging.core.qualification.QualificationProtocol.VARIABLE_CAP_HEADROOM
import eu.darken.amply.charging.core.qualification.QualificationProtocol.VARIABLE_CAP_UNDERSHOOT

/** Why a device cannot be offered a guided run. Each maps to one sentence in the pre-check list. */
enum class IneligibleReason {
    /** No adapter matched, or the matched one is diagnostics-only: nothing to drive. */
    NO_ADAPTER,

    /**
     * The adapter's control is already decided — either it never needed enforcement evidence
     * (its gate is a maintainer-qualified ROM version) or a maintainer qualified this device. A run
     * would prove something already known.
     */
    NOTHING_TO_PROVE,

    /**
     * The adapter's probe refused control for a reason a run cannot change: a secondary user, a
     * missing provider or key. Offering a run here would be an offer that cannot succeed.
     */
    CONTROL_UNAVAILABLE,

    /**
     * The build was already observed charging past its cap. That is terminal, so there is nothing
     * left to measure.
     */
    REFUTED,

    /**
     * `policyLatchesAtPlug` (GrapheneOS): the ROM samples the setting only at plug-session start, so
     * mid-run writes have no hardware effect at all. The sequence would need three unplug→replug
     * cycles, which breaks the keep-it-plugged invariant the whole measurement rests on.
     */
    LATCHES_AT_PLUG,

    /**
     * The adapter offers no [ChargePolicy.FixedLimit] at all — its only protective mode is
     * `Adaptive`, which engages inside an OEM-learned window rather than whenever it is configured
     * (`ChargePolicy.enforcementIsConditional`). Nothing can be challenged on demand.
     */
    NO_TESTABLE_CAP,

    /** No write backend: Shizuku is not connected, or the permission was never granted. */
    ACCESS_NOT_READY,

    /** A temporary full-charge session owns the charge policy right now. */
    SESSION_ACTIVE,

    /** A restore is still owed from an earlier session or boot; it must land before a run starts. */
    RECOVERY_PENDING,

    /**
     * A conditional charge rule currently owns the charging policy. The run must not start: it would
     * read the rule's temporary policy as the user's own setting, restore that persistently at the
     * end, and leave nothing remembering the real baseline the rule still owed back.
     */
    RULE_ACTIVE,

    /**
     * The currently configured policy could not be read, so the run does not know what it would owe
     * the user afterwards. Guessing the adapter's protective default would silently replace an
     * unrecognized native mode with 80%.
     */
    BASELINE_UNREADABLE,

    /** Not plugged in. */
    NOT_CHARGING,

    /** The battery is outside the band this adapter's run shape can work in. */
    BATTERY_LEVEL,

    /**
     * The battery is too close to full to measure anything: up there it stops charging whether or not
     * the cap works, so a hold proves nothing. See [QualificationProtocol.NEAR_FULL_PERCENT].
     */
    BATTERY_TOO_FULL,
}

/** The concrete caps a run will drive, resolved from the adapter and the current battery level. */
data class RunPlan(
    val shape: RunShape,
    val lowCap: Int,
    val releasePolicy: ChargePolicy,
)

sealed interface RunEligibility {
    data class Eligible(val adapter: ChargingAdapter, val plan: RunPlan) : RunEligibility
    data class Ineligible(val reason: IneligibleReason) : RunEligibility
}

/**
 * Whether this device can be offered a guided qualification run, and with what caps.
 *
 * Pure so the pre-check list, the entry-point visibility and the runner all agree by construction
 * rather than by three separate approximations of the same rule.
 */
fun qualificationEligibility(
    adapter: ChargingAdapter?,
    support: eu.darken.amply.charging.core.adapter.AdapterSupport?,
    evidence: EnforcementEvidenceState,
    plugged: Boolean,
    percent: Int,
    accessReady: Boolean,
    sessionActive: Boolean,
    pendingRecovery: Boolean,
    ruleOwnsPolicy: Boolean,
    baselineReadable: Boolean,
): RunEligibility {
    if (adapter == null || support == null || adapter.supportedPolicies.isEmpty()) {
        return RunEligibility.Ineligible(IneligibleReason.NO_ADAPTER)
    }
    // A run answers exactly one question: does this build's hardware honour a cap. An adapter whose
    // gate never asked it, or a device a maintainer already qualified, has no use for the answer.
    if (!adapter.enforcementEvidenceRequired || support.enforcement == EnforcementStatus.CONFIRMED) {
        return RunEligibility.Ineligible(IneligibleReason.NOTHING_TO_PROVE)
    }
    if (adapter.policyLatchesAtPlug) return RunEligibility.Ineligible(IneligibleReason.LATCHES_AT_PLUG)
    if (isRefuted(evidence)) return RunEligibility.Ineligible(IneligibleReason.REFUTED)
    // The probe's own verdict, not the evidence tier: the tier being CANDIDATE is the normal reason
    // to run, but a probe refusal (secondary user, no provider) is one a run cannot lift.
    if (support.enforcement == null && !support.controlEnabled) {
        return RunEligibility.Ineligible(IneligibleReason.CONTROL_UNAVAILABLE)
    }
    if (!accessReady) return RunEligibility.Ineligible(IneligibleReason.ACCESS_NOT_READY)
    if (sessionActive) return RunEligibility.Ineligible(IneligibleReason.SESSION_ACTIVE)
    if (pendingRecovery) return RunEligibility.Ineligible(IneligibleReason.RECOVERY_PENDING)
    if (ruleOwnsPolicy) return RunEligibility.Ineligible(IneligibleReason.RULE_ACTIVE)
    if (!baselineReadable) return RunEligibility.Ineligible(IneligibleReason.BASELINE_UNREADABLE)
    if (!plugged) return RunEligibility.Ineligible(IneligibleReason.NOT_CHARGING)

    if (percent >= QualificationProtocol.NEAR_FULL_PERCENT) {
        return RunEligibility.Ineligible(IneligibleReason.BATTERY_TOO_FULL)
    }

    val caps = adapter.supportedPolicies.filterIsInstance<ChargePolicy.FixedLimit>()
        .map { it.percent }
        .filter { it < 100 }
        .distinct()
        .sorted()
    if (caps.isEmpty()) return RunEligibility.Ineligible(IneligibleReason.NO_TESTABLE_CAP)

    val plan = resolvePlan(caps, adapter.supportedPolicies, percent)
        ?: return RunEligibility.Ineligible(IneligibleReason.BATTERY_LEVEL)
    return RunEligibility.Eligible(adapter, plan)
}

private fun isRefuted(evidence: EnforcementEvidenceState): Boolean = when (evidence) {
    is EnforcementEvidenceState.Corrupt -> true
    is EnforcementEvidenceState.Present -> evidence.evidence.verdict == EnforcementVerdict.REFUTED
    else -> false
}

/**
 * Pick the caps for this run.
 *
 * With two or more ticks the run is [RunShape.VARIABLE_CAP]: it caps *below* the current level and
 * releases by raising the cap, so the battery is never left uncapped and the measurement works
 * wherever the battery happens to be. With one tick it is [RunShape.FIXED_CAP]: the release step has
 * to remove the cap entirely, and the run only measures anything near that cap.
 */
internal fun resolvePlan(
    caps: List<Int>,
    policies: List<ChargePolicy>,
    percent: Int,
): RunPlan? {
    if (percent !in 0..100) return null
    val unrestrictedSupported = policies.contains(ChargePolicy.Unrestricted)
    if (caps.size < 2) {
        val only = caps.first()
        // Removing the cap is the only way to release a single-tick adapter.
        if (!unrestrictedSupported) return null
        return RunPlan(RunShape.FIXED_CAP, only, ChargePolicy.Unrestricted)
    }
    val low = caps.lastOrNull { it <= percent - VARIABLE_CAP_UNDERSHOOT } ?: return null
    val release = caps.firstOrNull { it >= percent + VARIABLE_CAP_HEADROOM }
        ?.let { ChargePolicy.FixedLimit(it) }
        // Near the top of the range there is no higher tick left to raise to, so releasing means
        // removing the cap. Harmless: the run re-caps within minutes and restores at the end.
        ?: ChargePolicy.Unrestricted.takeIf { unrestrictedSupported }
        ?: return null
    return RunPlan(RunShape.VARIABLE_CAP, low, release)
}
