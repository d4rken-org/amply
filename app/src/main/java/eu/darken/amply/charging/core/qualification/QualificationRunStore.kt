package eu.darken.amply.charging.core.qualification

import eu.darken.amply.charging.core.ChargePolicy
import eu.darken.amply.common.AppDataStore
import eu.darken.amply.common.datastore.createValue
import eu.darken.amply.common.datastore.value
import eu.darken.amply.common.debug.logging.Logging
import eu.darken.amply.common.debug.logging.log
import eu.darken.amply.common.debug.logging.logTag
import eu.darken.amply.common.serialization.ChargePolicySerializer
import eu.darken.amply.fullcharge.core.WorkProvenance
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/** One completed phase, kept only long enough to build the report, then discarded with the record. */
@Serializable
data class PhaseRecord(
    @SerialName("phase") val phase: RunPhase = RunPhase.PREFLIGHT,
    @SerialName("commanded") val commanded: String = "",
    @SerialName("enteredAtWallMillis") val enteredAtWallMillis: Long = 0L,
    @SerialName("entryPercent") val entryPercent: Int = -1,
    @SerialName("entryCounter") val entryCounter: Int? = null,
    @SerialName("exitAtWallMillis") val exitAtWallMillis: Long = 0L,
    @SerialName("exitPercent") val exitPercent: Int = -1,
    @SerialName("exitCounter") val exitCounter: Int? = null,
    /** The accumulation rate measured across this phase, against which the verdict was decided. */
    @SerialName("ratePerHour") val ratePerHour: Long = 0L,
)

/**
 * The run Amply is in the middle of, held as one record so its baseline, phase and provenance can
 * never be read as a mismatched set — the same reasoning as
 * [eu.darken.amply.fullcharge.core.ChargeSessionRecord].
 *
 * [baseline] is the one genuinely fatal field: it is the policy the run owes the user back, and a
 * build that cannot read it must not guess. An unreadable record collapses to "no run", and the
 * restore still happens through the recovery target registered in
 * [eu.darken.amply.fullcharge.core.FullChargeStore] before the first write — which is precisely why
 * that belt-and-braces registration exists rather than this record being the only copy.
 */
@Serializable
data class QualificationRunRecord(
    @SerialName("baseline")
    @Serializable(with = ChargePolicySerializer::class)
    val baseline: ChargePolicy,
    @SerialName("runId") val runId: String = "",
    /**
     * Authorizes `ChargingRepository.applyForQualification`. Without a live record carrying this
     * token that ungated write path is unreachable, which is what stops it becoming a general
     * bypass of the enforcement gate.
     */
    @SerialName("runToken") val runToken: String = "",
    @SerialName("adapterId") val adapterId: String = "",
    @SerialName("buildIdentity") val buildIdentity: String = "",
    @SerialName("protocolVersion") val protocolVersion: Int = 0,
    @SerialName("shape") val shape: RunShape = RunShape.FIXED_CAP,
    @SerialName("candidate") val candidate: Boolean = false,
    /**
     * False when the baseline could only be inferred rather than read back verified; the restore then
     * writes the adapter's `defaultProtectivePolicy` instead of a policy that was never confirmed.
     */
    @SerialName("baselineVerified") val baselineVerified: Boolean = false,
    @SerialName("phase") val phase: RunPhase = RunPhase.PREFLIGHT,
    @SerialName("runStartedAtWallMillis") val runStartedAtWallMillis: Long = 0L,
    @SerialName("phaseStartedAtWallMillis") val phaseStartedAtWallMillis: Long = 0L,
    @SerialName("lowCap") val lowCap: Int = 0,
    @SerialName("releasePolicy")
    @Serializable(with = ChargePolicySerializer::class)
    val releasePolicy: ChargePolicy = ChargePolicy.Unrestricted,
    @SerialName("commanded") val commanded: String? = null,
    @SerialName("commandedAtWallMillis") val commandedAtWallMillis: Long = 0L,
    /**
     * When the phase's commanded write was acknowledged, which is what the settle window is measured
     * from. Zero while it is unacknowledged — the phase's window may not open, and nothing about the
     * phase may be judged, before the write it is about has landed.
     */
    @SerialName("commandAckedAtWallMillis") val commandAckedAtWallMillis: Long = 0L,
    @SerialName("windowAnchoredAtWallMillis") val windowAnchoredAtWallMillis: Long = 0L,
    @SerialName("windowStartPercent") val windowStartPercent: Int = -1,
    @SerialName("windowStartCounter") val windowStartCounter: Int? = null,
    @SerialName("windowSignalChanges") val windowSignalChanges: Int = 0,
    @SerialName("lastSignalValue") val lastSignalValue: Long? = null,
    /** The within-run control: accumulation per hour measured in the baseline phase. */
    @SerialName("baselineRatePerHour") val baselineRatePerHour: Long = 0L,
    @SerialName("impliedFullCapacity") val impliedFullCapacity: Long = 0L,
    @SerialName("signal") val signal: FlowSignal = FlowSignal.NONE,
    @SerialName("observedHoldPercent") val observedHoldPercent: Int? = null,
    @SerialName("writeFailed") val writeFailed: Boolean = false,
    @SerialName("cancelled") val cancelled: Boolean = false,
    /**
     * Claimed for finalization: a terminal outcome is being written out for this record.
     *
     * The claim is what makes the terminal path transactional. Finalizing means restoring the user's
     * policy and only then recording evidence, which is slow enough for a cancel to arrive in the
     * middle; once claimed, [QualificationRunStore.requestCancel] no longer commits, so the outcome
     * cannot change under a finalization that has already read it.
     */
    @SerialName("finalizing") val finalizing: Boolean = false,
    @SerialName("phaseLog") val phaseLog: List<PhaseRecord> = emptyList(),
    @SerialName("provenance") val provenance: WorkProvenance? = null,
)

/**
 * Durable state for the in-flight run.
 *
 * `fallbackToDefault = true` matches [eu.darken.amply.fullcharge.core.FullChargeStore]'s session
 * value: an unreadable run is no run, which is an all-or-nothing decode rather than a record whose
 * fields degrade independently.
 */
@Singleton
class QualificationRunStore @Inject constructor(
    dataStore: AppDataStore,
    json: Json,
) {
    private val runValue = dataStore.createValue<QualificationRunRecord?>(
        key = KEY,
        defaultValue = null,
        json = json,
        fallbackToDefault = true,
    )

    val run: Flow<QualificationRunRecord?> = runValue.flow

    suspend fun currentRun(): QualificationRunRecord? = runValue.value()

    suspend fun put(record: QualificationRunRecord) {
        runValue.update { record }
    }

    /**
     * Advance the stored run inside a single read-modify-write transaction, returning the result.
     *
     * The tick loop must not write back a record it read earlier: [requestCancel] and
     * [markWriteFailed] run on other coroutines, and a plain `put` of the older copy would silently
     * drop the flag they just set — losing the user's cancel. Returns null when no run is stored,
     * which means the run ended while the tick was being processed.
     */
    suspend fun mergeProgress(
        transform: (QualificationRunRecord) -> QualificationRunRecord,
    ): QualificationRunRecord? = runValue.update { current ->
        current?.let(transform)
    }.new

    /**
     * Record that the phase's commanded write landed, at [ackAtWallMillis].
     *
     * The settle window and therefore the phase's whole measurement hang off this timestamp rather
     * than off when the engine emitted the command: the write in between can take seconds, and a
     * window opened before it landed measures the previous configuration.
     */
    suspend fun markApplied(ackAtWallMillis: Long) {
        runValue.update { it?.copy(commandAckedAtWallMillis = ackAtWallMillis) }
    }

    suspend fun clear() {
        log(TAG, Logging.Priority.INFO) { "Clearing qualification run record" }
        runValue.update { null }
    }

    /**
     * Claim the record for finalization, returning it as it was at that instant, or null when there
     * is nothing to claim — no run, or a finalization another caller already claimed.
     *
     * One transaction, because a re-read at the top of the terminal path only narrows the window: the
     * restore that follows is slow, and a cancel committing during it would otherwise be neither
     * honoured (the terminal was already decided) nor preserved (the record is cleared at the end).
     * After this, [requestCancel] cannot commit, so what this returns is what the run ended as.
     */
    suspend fun claimForFinalization(): QualificationRunRecord? {
        var claimed: QualificationRunRecord? = null
        runValue.update { current ->
            claimed = current?.takeIf { !it.finalizing }
            claimed?.copy(finalizing = true) ?: current
        }
        return claimed
    }

    /**
     * Mark the run cancelled; the runner turns that into an abort on its next tick.
     *
     * Refused once finalization is claimed: the outcome is already being written out, and flagging a
     * record that is about to be cleared would only lose the cancel silently.
     */
    suspend fun requestCancel() {
        runValue.update { if (it == null || it.finalizing) it else it.copy(cancelled = true) }
    }

    /** Record that the last commanded write failed, so the next tick aborts rather than measuring. */
    suspend fun markWriteFailed() {
        runValue.update { if (it == null || it.finalizing) it else it.copy(writeFailed = true) }
    }

    private companion object {
        const val KEY = "qualification.run.v1"
        val TAG = logTag("Charging", "Qualification", "RunStore")
    }
}
