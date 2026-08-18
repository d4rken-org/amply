package eu.darken.amply.charging.core.qualification

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * What a completed run durably claims.
 *
 * **One constant, mirroring [eu.darken.amply.charging.core.enforcement.EnforcementVerdict] and for the
 * opposite reason.** That enum has only `REFUTED` because passive observation can only ever refute; this
 * one has only [PASSED] because a run's only durable *positive* product is a pass. An inconclusive or
 * aborted run stores nothing at all, so runs can be repeated freely and a missing record can never be
 * mistaken for a pass. A refutation produced by a run is recorded in the enforcement store instead —
 * same claim, same terminality, one place.
 */
@Serializable
enum class QualificationOutcomeRecord {
    @SerialName("PASSED")
    PASSED,
}

/**
 * A run that proved this build's charging hardware obeys a cap.
 *
 * A stored wire format (see `code-style.md`): every property carries an explicit [SerialName] and a
 * default so a record written by an older build still decodes.
 *
 * **The fail-closed guard here is [protocolVersion], not an enum default**, and that difference from
 * [eu.darken.amply.charging.core.enforcement.EnforcementEvidence] is deliberate. That record can default
 * its verdict to `REFUTED` because its only verdict is the *restrictive* one, so a record that lost
 * fields degrades safely. There is no safe default for a one-constant *positive* enum: a record that
 * lost everything would otherwise decode as a pass. So a record must carry the current protocol version
 * explicitly — a partially-decoded one carries `0` and is scoped out by
 * [QualificationEvidenceStore].
 *
 * @param exercisedPolicies the policies the run actually wrote and observed, as
 *   [eu.darken.amply.charging.core.ChargePolicy.stableId] values. A pass licenses **only** these, never
 *   the adapter's full `supportedPolicies` — on a candidate device the OEM's other values are still a
 *   guess.
 */
@Serializable
data class QualificationEvidence(
    @SerialName("adapterId") val adapterId: String = "",
    @SerialName("buildIdentity") val buildIdentity: String = "",
    @SerialName("protocolVersion") val protocolVersion: Int = 0,
    @SerialName("outcome") val outcome: QualificationOutcomeRecord = QualificationOutcomeRecord.PASSED,
    @SerialName("shape") val shape: RunShape = RunShape.FIXED_CAP,
    @SerialName("signal") val signal: FlowSignal = FlowSignal.NONE,
    @SerialName("capPercent") val capPercent: Int = 0,
    @SerialName("observedHoldPercent") val observedHoldPercent: Int = 0,
    @SerialName("candidatePromotion") val candidatePromotion: Boolean = false,
    @SerialName("exercisedPolicies") val exercisedPolicies: List<String> = emptyList(),
    @SerialName("completedAtWallMillis") val completedAtWallMillis: Long = 0L,
)
