package eu.darken.amply.charging.core.enforcement

import androidx.datastore.preferences.core.stringPreferencesKey
import eu.darken.amply.common.AppDataStore
import eu.darken.amply.common.datastore.createValue
import eu.darken.amply.common.datastore.value
import eu.darken.amply.common.debug.logging.Logging
import eu.darken.amply.common.debug.logging.log
import eu.darken.amply.common.debug.logging.logTag
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/**
 * What the store knows about this build's enforcement evidence. Explicit rather than a nullable
 * record, because the three "no evidence" shapes must not collapse into one:
 *
 * - [Loading] — a caller that has not read the store yet. Resolves as a candidate (control off).
 * - [Absent] — genuinely nothing stored for this build and algorithm version.
 * - [Corrupt] — a record exists but cannot be decoded. Fail-closed, NOT treated as [Absent]: the
 *   unreadable record could be a REFUTED one, and treating it as absent would reopen control on a
 *   device already proven not to enforce.
 */
sealed interface EnforcementEvidenceState {
    data object Loading : EnforcementEvidenceState
    data object Absent : EnforcementEvidenceState
    data class Present(val evidence: EnforcementEvidence) : EnforcementEvidenceState
    data object Corrupt : EnforcementEvidenceState
}

/**
 * Single-slot persistence for the enforcement verdict, over the shared [AppDataStore].
 *
 * The stored value is kept as the **raw JSON string** and decoded here rather than through a typed
 * `fallbackToDefault` reader. Two reasons, both fail-closed: a corrupt record must surface as
 * [EnforcementEvidenceState.Corrupt] instead of silently degrading to "no evidence" (which would
 * hand control back to a device observed charging past its cap), and a corrupt payload must survive
 * a write that declines to replace it, which a state-typed writer could not express — it would
 * encode the undecodable state as "clear the key".
 *
 * Reads are **scoped**: a record from another ROM build or from an older
 * [EnforcementVerdictEngine.ALGORITHM_VERSION] reads as [EnforcementEvidenceState.Absent], since
 * charge-control HAL capability is build-scoped and an older heuristic's confirmation is not this
 * one's.
 */
@Singleton
class EnforcementEvidenceStore @Inject constructor(
    dataStore: AppDataStore,
    private val buildIdentity: BuildIdentitySource,
    private val json: Json,
) {
    private val stored = dataStore.createValue(
        key = stringPreferencesKey(KEY),
        // `as?`, not `as`: a key name reused for a non-String type must not throw a
        // ClassCastException, which no decode guard below would cover.
        reader = { raw -> raw as? String },
        writer = { value -> value },
    )

    /** The evidence that applies to *this* build, scoped and fail-closed. */
    val state: Flow<EnforcementEvidenceState> = stored.flow.map { scope(decode(it)) }

    suspend fun currentState(): EnforcementEvidenceState = scope(decode(stored.value()))

    /**
     * Persist [evidence], honouring the one asymmetry that matters: a REFUTED record for the same
     * scope is terminal, so a later CONFIRMED must not overwrite it — a device that charged past its
     * cap is not redeemed by a later plateau. A REFUTED verdict overwrites anything, including a
     * corrupt record. A CONFIRMED verdict declines to overwrite a corrupt record: what it would
     * replace is unknown, and unknown fails closed.
     *
     * @return true when the store now holds exactly [evidence].
     */
    suspend fun record(evidence: EnforcementEvidence): Boolean {
        val encoded = encode(evidence)
        val updated = stored.update { raw ->
            val current = decode(raw)
            when {
                evidence.verdict == EnforcementVerdict.REFUTED -> encoded
                current is EnforcementEvidenceState.Corrupt -> raw
                current.terminalFor(evidence) -> raw
                else -> encoded
            }
        }
        val accepted = updated.new == encoded
        log(TAG, Logging.Priority.INFO) {
            "record(${evidence.verdict}, cap=${evidence.capPercent}%, at=${evidence.observedPercent}%): $accepted"
        }
        return accepted
    }

    private fun scope(state: EnforcementEvidenceState): EnforcementEvidenceState = when (state) {
        is EnforcementEvidenceState.Present -> {
            val evidence = state.evidence
            val applies = evidence.buildIdentity == buildIdentity.current() &&
                evidence.algorithmVersion == EnforcementVerdictEngine.ALGORITHM_VERSION
            if (applies) state else EnforcementEvidenceState.Absent
        }
        else -> state
    }

    private fun decode(raw: String?): EnforcementEvidenceState {
        if (raw == null) return EnforcementEvidenceState.Absent
        return try {
            EnforcementEvidenceState.Present(json.decodeFromString(EnforcementEvidence.serializer(), raw))
        } catch (e: SerializationException) {
            log(TAG, Logging.Priority.ERROR) { "Corrupt enforcement evidence: ${e.message}" }
            EnforcementEvidenceState.Corrupt
        } catch (e: IllegalArgumentException) {
            log(TAG, Logging.Priority.ERROR) { "Unreadable enforcement evidence: ${e.message}" }
            EnforcementEvidenceState.Corrupt
        }
    }

    private fun encode(evidence: EnforcementEvidence): String =
        json.encodeToString(EnforcementEvidence.serializer(), evidence)

    private companion object {
        const val KEY = "enforcement.evidence.v1"
        val TAG = logTag("Charging", "Enforcement", "Store")
    }
}

/** Whether the stored state blocks [candidate]: a refutation for the very same scope. */
private fun EnforcementEvidenceState.terminalFor(candidate: EnforcementEvidence): Boolean {
    val existing = (this as? EnforcementEvidenceState.Present)?.evidence ?: return false
    return existing.verdict == EnforcementVerdict.REFUTED &&
        existing.adapterId == candidate.adapterId &&
        existing.buildIdentity == candidate.buildIdentity &&
        existing.algorithmVersion == candidate.algorithmVersion
}
