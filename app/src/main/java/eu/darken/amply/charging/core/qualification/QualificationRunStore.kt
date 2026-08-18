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

/** Which of [RunTerminal]'s four shapes a claimed finalization decided on. */
@Serializable
enum class TerminalKind {
    @SerialName("PASSED")
    PASSED,

    @SerialName("REFUTED")
    REFUTED,

    @SerialName("INCONCLUSIVE")
    INCONCLUSIVE,

    @SerialName("ABORTED")
    ABORTED,
}

/**
 * The terminal a finalization already decided on, persisted with the claim that decided it.
 *
 * Finalization is four durable steps — restore, evidence, publish, clear — and any of them can fail
 * halfway. Without this the only thing on disk is "a finalization started", so recovery has to invent
 * a substitute outcome: the user is told nothing was recorded while the evidence that licenses (or
 * permanently withholds) charge control is already written. Recorded here, every recovery path
 * *replays* the outcome the run actually decided instead of contradicting it.
 *
 * A terminal is decided once and never recomputed. A later tick's readings describe a different
 * instant, and the measurement that produced this verdict is over.
 */
@Serializable
data class FinalizationIntent(
    /** Defaults to the one kind that can never claim more than was proven. */
    @SerialName("kind") val kind: TerminalKind = TerminalKind.ABORTED,
    @SerialName("inconclusiveReason") val inconclusiveReason: InconclusiveReason? = null,
    @SerialName("abortReason") val abortReason: AbortReason? = null,
    /** When the terminal was decided, so a replay writes the same evidence rather than a later one. */
    @SerialName("decidedAtWallMillis") val decidedAtWallMillis: Long = 0L,
) {

    /**
     * The terminal this intent stands for, or null when it carries none — a truncated or hand-edited
     * record whose reason went missing. Null deliberately means "no usable intent" rather than a
     * substitute reason: every caller already has the fallback that belongs to its own situation, and
     * inventing one here would put a reason on the record that nothing ever decided. [TerminalKind.PASSED]
     * and [TerminalKind.REFUTED] carry no reason and so can never malform.
     */
    fun toTerminal(): RunTerminal? = when (kind) {
        TerminalKind.PASSED -> RunTerminal.Passed
        TerminalKind.REFUTED -> RunTerminal.Refuted
        TerminalKind.INCONCLUSIVE -> inconclusiveReason?.let { RunTerminal.Inconclusive(it) }
        TerminalKind.ABORTED -> abortReason?.let { RunTerminal.Aborted(it) }
    }

    companion object {
        fun of(terminal: RunTerminal, decidedAtWallMillis: Long): FinalizationIntent = when (terminal) {
            is RunTerminal.Passed -> FinalizationIntent(
                kind = TerminalKind.PASSED,
                decidedAtWallMillis = decidedAtWallMillis,
            )

            is RunTerminal.Refuted -> FinalizationIntent(
                kind = TerminalKind.REFUTED,
                decidedAtWallMillis = decidedAtWallMillis,
            )

            is RunTerminal.Inconclusive -> FinalizationIntent(
                kind = TerminalKind.INCONCLUSIVE,
                inconclusiveReason = terminal.reason,
                decidedAtWallMillis = decidedAtWallMillis,
            )

            is RunTerminal.Aborted -> FinalizationIntent(
                kind = TerminalKind.ABORTED,
                abortReason = terminal.reason,
                decidedAtWallMillis = decidedAtWallMillis,
            )
        }
    }
}

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
    /**
     * The outcome the claim above decided on, so an interrupted finalization is replayed rather than
     * replaced by an invented abort.
     *
     * Deliberately **not** what [finalizing] is derived from. A record written by a build without this
     * field is claimed with no intent, and reading that as unclaimed would hand a half-finalized run
     * back to the engine to measure — the one thing the claim exists to prevent. The invariant is the
     * other way round: a claimed record has `finalizing = true`, and its intent is null only when an
     * older build wrote it.
     */
    @SerialName("finalization") val finalization: FinalizationIntent? = null,
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
     * Claim the record for finalization and write the outcome down with the claim, returning the
     * claimed record, or null when there is nothing to claim — no run, or a finalization another
     * caller already claimed.
     *
     * One transaction, because a re-read at the top of the terminal path only narrows the window: the
     * restore that follows is slow, and a cancel committing during it would otherwise be neither
     * honoured (the terminal was already decided) nor preserved (the record is cleared at the end).
     * After this, [requestCancel] cannot commit, so what this returns is what the run ended as.
     *
     * **An intent already on the record is never overwritten.** It is what that run decided, possibly
     * in a process that has since died; [proposed] is used only when there is none. That is what makes
     * an interrupted finalization replayable rather than a fresh guess made from a later tick's
     * readings.
     *
     * A *fresh* intent is resolved here, inside the same transaction that persists it: a committed
     * cancel downgrades it to [AbortReason.USER_CANCELLED], a failed write to
     * [AbortReason.WRITE_FAILED]. Resolving it anywhere else would let the persisted intent disagree
     * with the downgrade actually applied. A *stored* intent was already downgraded at its own claim
     * time and must not be downgraded twice.
     *
     * [merge] folds the closing measurement into the record, and runs **only for a fresh intent** —
     * the outcome and the readings it was decided from are written down together. A record whose
     * intent was already stored keeps the measurement stored with it.
     *
     * The claim is durable, so on its own it is a one-way latch: a process death between the claim and
     * the [clear] at the end of finalization — a window that spans a policy write, slow on a Shizuku
     * adapter — would leave a record nothing could ever finalize again, and a permanently claimed
     * record is a permanently "running" run. [reclaimRunId] is the way back out: passed non-null it
     * claims the stored record when its [QualificationRunRecord.runId] matches, **even if the record
     * is already claimed**, and refuses anything else — including an unclaimed record belonging to a
     * different run, which would be a newer run stolen rather than an abandoned one recovered. Only
     * the close-out paths may use it, and only for a record this process is not still finalizing.
     */
    suspend fun claimForFinalization(
        proposed: FinalizationIntent,
        reclaimRunId: String? = null,
        merge: (QualificationRunRecord) -> QualificationRunRecord = { it },
    ): QualificationRunRecord? {
        var claimed: QualificationRunRecord? = null
        runValue.update { current ->
            val target = when {
                current == null -> null
                reclaimRunId != null -> current.takeIf { it.runId == reclaimRunId }
                else -> current.takeIf { !it.finalizing }
            }
            claimed = target?.let {
                if (it.finalization != null) {
                    it.copy(finalizing = true)
                } else {
                    val resolved = when {
                        it.cancelled -> FinalizationIntent.of(
                            RunTerminal.Aborted(AbortReason.USER_CANCELLED),
                            proposed.decidedAtWallMillis,
                        )

                        it.writeFailed -> FinalizationIntent.of(
                            RunTerminal.Aborted(AbortReason.WRITE_FAILED),
                            proposed.decidedAtWallMillis,
                        )

                        else -> proposed
                    }
                    merge(it).copy(finalizing = true, finalization = resolved)
                }
            }
            claimed ?: current
        }
        return claimed
    }

    /**
     * Give a finalization claim back after it failed, so the record can be finalized again instead of
     * being stuck claimed forever.
     *
     * It clears [QualificationRunRecord.finalizing] and **keeps the intent**: the release says "this
     * attempt did not finish", not "the outcome was never decided". The outcome *was* decided, from a
     * measurement that is over, and the next attempt has to replay it rather than recompute one from a
     * later tick's readings.
     *
     * Guarded by [runId] inside the transaction, because the two things that legitimately happen while
     * a finalization is failing must not be undone by it: the record may already have been cleared (a
     * throw *after* [clear], the surface refresh being the obvious one) — there is then no record to
     * resurrect — or a later run may already occupy the slot, whose own claim is not this caller's to
     * release.
     */
    suspend fun releaseFinalizationClaim(runId: String) {
        runValue.update { current ->
            if (current?.runId == runId) current.copy(finalizing = false) else current
        }
    }

    /**
     * Mark the run cancelled; the runner turns that into an abort on its next tick.
     *
     * Refused once finalization is claimed *or* an outcome is written down: the outcome is already
     * decided, and flagging a record whose terminal is only waiting to be replayed would lose the
     * cancel silently.
     */
    suspend fun requestCancel() {
        runValue.update { if (it == null || it.finalizing || it.finalization != null) it else it.copy(cancelled = true) }
    }

    /** Record that the last commanded write failed, so the next tick aborts rather than measuring. */
    suspend fun markWriteFailed() {
        runValue.update {
            if (it == null || it.finalizing || it.finalization != null) it else it.copy(writeFailed = true)
        }
    }

    private companion object {
        const val KEY = "qualification.run.v1"
        val TAG = logTag("Charging", "Qualification", "RunStore")
    }
}
