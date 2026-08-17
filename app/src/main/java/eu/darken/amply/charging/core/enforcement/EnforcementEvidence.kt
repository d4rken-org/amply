package eu.darken.amply.charging.core.enforcement

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * What was observed about the charging hardware, never about the configured setting.
 *
 * **One value on purpose.** Passive observation can only ever *refute* a cap — see
 * [EnforcementVerdictEngine] for the measurement that removed the confirmation path. It stays an
 * enum rather than collapsing into a boolean or disappearing so the stored wire format and its
 * [SerialName] keep decoding unchanged.
 */
@Serializable
enum class EnforcementVerdict {
    /** The battery was observed charging past the configured cap: the setting is cosmetic here. */
    @SerialName("REFUTED")
    REFUTED,
}

/**
 * One durable verdict about whether this build's charging hardware enforces a configured cap.
 *
 * A stored wire format (see `code-style.md`): every property carries an explicit [SerialName] and a
 * default so a record written by an older build still decodes. [verdict] defaults to
 * [EnforcementVerdict.REFUTED] — a record that lost its verdict field must never read as a claim of
 * enforcement. A record naming a verdict this build no longer knows does not decode at all and reads
 * as [EnforcementEvidenceState.Corrupt], i.e. control off — the safe direction. The one such name that
 * really exists on disk, version 1's `"CONFIRMED"`, is translated before deserialization is attempted
 * (see [EnforcementEvidenceStore]).
 *
 * @param buildIdentity see [composeBuildIdentity]; scopes the verdict to the exact ROM build it was
 *   observed on, because charge-control HAL capability is build-scoped, not device-scoped.
 * @param algorithmVersion the [EnforcementVerdictEngine.ALGORITHM_VERSION] that produced the verdict.
 *   A later tightening of the heuristic bumps it, and a record from the weaker one only survives
 *   where the tightening left its verdict intact — [EnforcementEvidenceStore] migrates those per
 *   verdict, so dropping an arm never hands control back to a device that failed under the old one.
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
    /** No evidence and the user has not accepted the unconfirmed build: control stays off. */
    CANDIDATE,

    /**
     * The user accepted control on an unconfirmed build: it is available, but nothing here shows the
     * cap holding and nothing ever will — only a refutation can still arrive.
     */
    UNVERIFIED,

    /**
     * Maintainer-qualified: a device physically qualified against the protocol in the
     * `device-qualification` skill. This is the ONLY route to a confirmed cap — local observation
     * cannot produce one (see [EnforcementVerdictEngine]).
     */
    CONFIRMED,

    /** Observed charging past the cap on this build: control is off and stays off. */
    REFUTED,
}
