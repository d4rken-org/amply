package eu.darken.amply.charging.core.enforcement

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** What was observed about the charging hardware, never about the configured setting. */
@Serializable
enum class EnforcementVerdict {
    /** The battery was observed held at the configured cap: the hardware acts on the setting. */
    @SerialName("CONFIRMED")
    CONFIRMED,

    /** The battery was observed charging past the configured cap: the setting is cosmetic here. */
    @SerialName("REFUTED")
    REFUTED,
}

/**
 * One durable verdict about whether this build's charging hardware enforces a configured cap.
 *
 * A stored wire format (see `code-style.md`): every property carries an explicit [SerialName] and a
 * default so a record written by an older build still decodes. [verdict] defaults to
 * [EnforcementVerdict.REFUTED] on purpose — a record that lost its verdict field must never read as
 * a claim of enforcement.
 *
 * @param buildIdentity see [composeBuildIdentity]; scopes the verdict to the exact ROM build it was
 *   observed on, because charge-control HAL capability is build-scoped, not device-scoped.
 * @param algorithmVersion the [EnforcementVerdictEngine.ALGORITHM_VERSION] that produced the verdict.
 *   A later tightening of the heuristic bumps it, and every record from the weaker one stops counting
 *   (it reads as no evidence, exactly like a build-identity mismatch).
 * @param capPercent the configured cap the observation was made against.
 * @param observedPercent the battery level at the moment the verdict was reached.
 */
@Serializable
data class EnforcementEvidence(
    @SerialName("adapterId") val adapterId: String = "",
    @SerialName("buildIdentity") val buildIdentity: String = "",
    @SerialName("algorithmVersion") val algorithmVersion: Int = 0,
    @SerialName("verdict") val verdict: EnforcementVerdict = EnforcementVerdict.REFUTED,
    @SerialName("capPercent") val capPercent: Int = 0,
    @SerialName("observedPercent") val observedPercent: Int = 0,
    @SerialName("observedAtWallMillis") val observedAtWallMillis: Long = 0L,
)

/** Where a device sits on the "does the hardware actually enforce the cap" question. */
enum class EnforcementStatus {
    /** No evidence yet and the user has not started verification: control stays off. */
    CANDIDATE,

    /** The user started verification on this build: control is available but claims nothing. */
    UNDER_TEST,

    /** Maintainer-qualified, or locally observed holding at the cap. */
    CONFIRMED,

    /** Observed charging past the cap on this build: control is off and stays off. */
    REFUTED,
}
