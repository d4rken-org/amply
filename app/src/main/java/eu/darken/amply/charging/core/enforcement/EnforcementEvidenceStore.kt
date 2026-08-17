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
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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
 * Reads are **scoped**: a record from another ROM build reads as [EnforcementEvidenceState.Absent],
 * since charge-control HAL capability is build-scoped.
 *
 * A record from an older [EnforcementVerdictEngine.ALGORITHM_VERSION] is **migrated per verdict**,
 * not dropped wholesale — see [decode]. Dropping them all would fail *open*: the version-1 to -2 bump
 * only invalidated the confirmation arm, so discarding a version-1 refutation would re-enable control
 * on a build already observed charging past its cap, with the opt-in preference still set.
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
     * Persist [evidence]. A refutation is **terminal**: an existing one for the same scope is kept as
     * first observed rather than restamped, since nothing that can be observed later changes it (only
     * a refutation is observable at all — see [EnforcementVerdictEngine]). It does overwrite a corrupt
     * record: both fail closed, and the refutation is the more informative of the two.
     *
     * @return true when the store now holds exactly [evidence].
     */
    suspend fun record(evidence: EnforcementEvidence): Boolean {
        val encoded = encode(evidence)
        val updated = stored.update { raw ->
            if (decode(raw).terminalFor(evidence)) raw else encoded
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

    /**
     * The wire version and verdict are read **before** typed deserialization, because a version-1
     * record can no longer be deserialized into today's types at all (its `"CONFIRMED"` constant is
     * gone) and because the two version-1 verdicts must migrate in opposite directions.
     */
    private fun decode(raw: String?): EnforcementEvidenceState {
        if (raw == null) return EnforcementEvidenceState.Absent
        return try {
            val wire = json.parseToJsonElement(raw).jsonObject
            val version = wire[FIELD_ALGORITHM_VERSION]?.jsonPrimitive?.intOrNull
            if (version == SUPERSEDED_ALGORITHM_VERSION) {
                migrateSuperseded(wire[FIELD_VERDICT]?.jsonPrimitive?.contentOrNull, raw)
            } else {
                EnforcementEvidenceState.Present(json.decodeFromString(EnforcementEvidence.serializer(), raw))
            }
        } catch (e: SerializationException) {
            log(TAG, Logging.Priority.ERROR) { "Corrupt enforcement evidence: ${e.message}" }
            EnforcementEvidenceState.Corrupt
        } catch (e: IllegalArgumentException) {
            log(TAG, Logging.Priority.ERROR) { "Unreadable enforcement evidence: ${e.message}" }
            EnforcementEvidenceState.Corrupt
        }
    }

    /**
     * Version 1 → 2, decided by the verdict on the wire. The bump invalidated exactly one arm:
     *
     * - a version-1 `"CONFIRMED"` rested on a hardware signal now known to be session-scoped, so it is
     *   worth nothing and reads as [EnforcementEvidenceState.Absent] — the same "no evidence" a fresh
     *   install has, which is what the constant's removal was meant to produce (untranslated it would
     *   fail to deserialize and read [EnforcementEvidenceState.Corrupt], locking the device out for
     *   good);
     * - a version-1 `"REFUTED"` never depended on that signal and is semantically unchanged, so it is
     *   kept and **restamped to the current version** — a refutation is terminal, and leaving the old
     *   stamp on it would let the very next observation re-record over it;
     * - anything else is a record this build cannot reason about: fail closed with
     *   [EnforcementEvidenceState.Corrupt], which the gate treats as a refutation.
     */
    private fun migrateSuperseded(wireVerdict: String?, raw: String): EnforcementEvidenceState = when (wireVerdict) {
        WIRE_VERDICT_CONFIRMED -> {
            log(TAG, Logging.Priority.INFO) { "Dropping a superseded version-1 confirmation" }
            EnforcementEvidenceState.Absent
        }
        WIRE_VERDICT_REFUTED -> EnforcementEvidenceState.Present(
            json.decodeFromString(EnforcementEvidence.serializer(), raw)
                .copy(algorithmVersion = EnforcementVerdictEngine.ALGORITHM_VERSION),
        )
        else -> {
            log(TAG, Logging.Priority.ERROR) { "Unreadable version-1 verdict: $wireVerdict" }
            EnforcementEvidenceState.Corrupt
        }
    }

    private fun encode(evidence: EnforcementEvidence): String =
        json.encodeToString(EnforcementEvidence.serializer(), evidence)

    private companion object {
        const val KEY = "enforcement.evidence.v1"
        const val FIELD_ALGORITHM_VERSION = "algorithmVersion"
        const val FIELD_VERDICT = "verdict"
        /** The heuristic that also weighed the hardware signal; superseded by version 2. */
        const val SUPERSEDED_ALGORITHM_VERSION = 1
        /** Wire literals, not enum names: `CONFIRMED` no longer exists as a Kotlin constant. */
        const val WIRE_VERDICT_CONFIRMED = "CONFIRMED"
        const val WIRE_VERDICT_REFUTED = "REFUTED"
        val TAG = logTag("Charging", "Enforcement", "Store")
    }
}

/** Whether the stored state is already terminal for [candidate]: a refutation for the very same scope. */
private fun EnforcementEvidenceState.terminalFor(candidate: EnforcementEvidence): Boolean {
    val existing = (this as? EnforcementEvidenceState.Present)?.evidence ?: return false
    return existing.verdict == EnforcementVerdict.REFUTED &&
        existing.adapterId == candidate.adapterId &&
        existing.buildIdentity == candidate.buildIdentity &&
        existing.algorithmVersion == candidate.algorithmVersion
}
