package eu.darken.amply.charging.core.qualification

import androidx.datastore.preferences.core.stringPreferencesKey
import eu.darken.amply.charging.core.enforcement.BuildIdentitySource
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
 * What the store knows about a completed qualification run on *this* build.
 *
 * [Corrupt] means **not qualified**, which is the opposite direction from
 * [eu.darken.amply.charging.core.enforcement.EnforcementEvidenceState.Corrupt] — and for exactly the
 * same reason. There, the unreadable record might be a refutation, so it must not reopen control; here,
 * the only thing it could be is a pass, so it must not *grant* control. In both stores the unreadable
 * state is the restrictive one.
 */
sealed interface QualificationEvidenceState {
    data object Loading : QualificationEvidenceState
    data object Absent : QualificationEvidenceState
    data class Present(val evidence: QualificationEvidence) : QualificationEvidenceState
    data object Corrupt : QualificationEvidenceState
}

/**
 * Single-slot persistence for a qualification pass, over the shared [AppDataStore].
 *
 * Kept deliberately separate from `EnforcementEvidenceStore` rather than folded into it. The two record
 * different things — passive observation versus a driven experiment with a protocol version, a run
 * shape and a measurement signal — and, more importantly, adding a positive constant to that store's
 * verdict enum would open a fail-*open* decode path: every field there carries a default, so a record
 * that lost its `algorithmVersion` decodes as version 0, skips the version-1 migration branch, and with
 * a positive constant present would deserialize into a claim of enforcement on a build that never
 * earned one. Today such a record defaults to `REFUTED` and is scoped out.
 *
 * Reads are scoped to the exact ROM build **and** to [QualificationProtocol.PROTOCOL_VERSION]: a run
 * proves something about the hardware plus the ROM that drives it, and an OTA re-opens the question.
 */
@Singleton
class QualificationEvidenceStore @Inject constructor(
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

    val state: Flow<QualificationEvidenceState> = stored.flow.map { scope(decode(it)) }

    suspend fun currentState(): QualificationEvidenceState = scope(decode(stored.value()))

    /**
     * Persist a pass, replacing any earlier one. Unlike a refutation there is nothing terminal to
     * protect: a later run on the same build measuring the same thing is simply a fresher observation,
     * and a refutation — which *is* terminal — lives in the enforcement store and outranks this record
     * wherever the two are read together.
     */
    suspend fun record(evidence: QualificationEvidence): Boolean {
        val encoded = encode(evidence)
        val updated = stored.update { encoded }
        val accepted = updated.new == encoded
        log(TAG, Logging.Priority.INFO) {
            "record(${evidence.outcome}, adapter=${evidence.adapterId}, cap=${evidence.capPercent}%): $accepted"
        }
        return accepted
    }

    /** Drop the stored pass, e.g. when the user asks to re-run or revoke it. */
    suspend fun clear() {
        stored.update { null }
    }

    private fun scope(state: QualificationEvidenceState): QualificationEvidenceState = when (state) {
        is QualificationEvidenceState.Present -> {
            val evidence = state.evidence
            val applies = evidence.buildIdentity == buildIdentity.current() &&
                evidence.protocolVersion == QualificationProtocol.PROTOCOL_VERSION &&
                evidence.isCredible()
            if (applies) state else QualificationEvidenceState.Absent
        }

        else -> state
    }

    /**
     * A pass has to look like something a run actually produced.
     *
     * The protocol-version guard alone is not fail-closed: `outcome` defaults to the only constant it
     * has, which is the positive one, so a record carrying nothing but a matching `buildIdentity` and
     * `protocolVersion` would decode as a pass with no adapter, no cap, no measurement signal and no
     * policies. Every one of those is written by a real run, so requiring them turns a truncated or
     * hand-written record back into "no evidence" instead of a licence.
     */
    private fun QualificationEvidence.isCredible(): Boolean =
        adapterId.isNotBlank() &&
            capPercent in 1..99 &&
            signal != FlowSignal.NONE &&
            exercisedPolicies.isNotEmpty()

    private fun decode(raw: String?): QualificationEvidenceState {
        if (raw == null) return QualificationEvidenceState.Absent
        return try {
            QualificationEvidenceState.Present(
                json.decodeFromString(QualificationEvidence.serializer(), raw),
            )
        } catch (e: SerializationException) {
            log(TAG, Logging.Priority.ERROR) { "Corrupt qualification evidence: ${e.message}" }
            QualificationEvidenceState.Corrupt
        } catch (e: IllegalArgumentException) {
            log(TAG, Logging.Priority.ERROR) { "Unreadable qualification evidence: ${e.message}" }
            QualificationEvidenceState.Corrupt
        }
    }

    private fun encode(evidence: QualificationEvidence): String =
        json.encodeToString(QualificationEvidence.serializer(), evidence)

    private companion object {
        const val KEY = "qualification.result.v1"
        val TAG = logTag("Charging", "Qualification", "Store")
    }
}
