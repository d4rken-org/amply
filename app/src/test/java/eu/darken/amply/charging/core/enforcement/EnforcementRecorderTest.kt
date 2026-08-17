package eu.darken.amply.charging.core.enforcement

import eu.darken.amply.charging.core.ChargePolicy
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * The keep-alive gate behind `EnforcementWatcher.isEnabled()`. It decides whether a foreground
 * service and its persistent notification are held up, so every "no" here matters.
 */
class EnforcementRecorderTest {

    private fun observe(
        evidence: EnforcementEvidenceState = EnforcementEvidenceState.Absent,
        isSystemUser: Boolean = true,
        adapterRequiresEvidence: Boolean = true,
        verificationStarted: Boolean = true,
        persistentPolicy: ChargePolicy? = ChargePolicy.FixedLimit(80),
    ) = shouldObserveEnforcement(
        evidence = evidence,
        isSystemUser = isSystemUser,
        adapterRequiresEvidence = adapterRequiresEvidence,
        verificationStarted = verificationStarted,
        persistentPolicy = persistentPolicy,
    )

    private fun evidence(verdict: EnforcementVerdict) = EnforcementEvidenceState.Present(
        EnforcementEvidence(
            adapterId = "lineageos-chargingcontrol-v1",
            buildIdentity = "build-a",
            algorithmVersion = EnforcementVerdictEngine.ALGORITHM_VERSION,
            verdict = verdict,
            capPercent = 80,
            observedPercent = 80,
            observedAtWallMillis = 1_000L,
        ),
    )

    @Test
    fun `an accepted build with a configured cap observes`() {
        // Nothing stored yet: the refutation watch is exactly what there is left to observe.
        observe() shouldBe true
    }

    @Test
    fun `no cap configured does not hold the service up`() {
        // The state at boot before the user has ever applied a limit through Amply.
        observe(persistentPolicy = null) shouldBe false
        observe(persistentPolicy = ChargePolicy.Unrestricted) shouldBe false
        observe(persistentPolicy = ChargePolicy.Adaptive) shouldBe false
        // A "100% limit" caps nothing, so there is nothing to observe holding.
        observe(persistentPolicy = ChargePolicy.FixedLimit(100)) shouldBe false
    }

    @Test
    fun `a secondary user never observes`() {
        observe(isSystemUser = false) shouldBe false
    }

    @Test
    fun `a refuted or corrupt record ends observation`() {
        // Terminal: the answer for this build is in, and an undecodable record may be that answer.
        observe(evidence = evidence(EnforcementVerdict.REFUTED)) shouldBe false
        observe(evidence = EnforcementEvidenceState.Corrupt) shouldBe false
    }

    @Test
    fun `nothing is observed before the user accepts the build`() {
        observe(verificationStarted = false) shouldBe false
    }

    @Test
    fun `adapters that need no evidence never observe`() {
        observe(adapterRequiresEvidence = false) shouldBe false
    }
}
