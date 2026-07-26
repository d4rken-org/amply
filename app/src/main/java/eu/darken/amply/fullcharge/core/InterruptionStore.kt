package eu.darken.amply.fullcharge.core

import eu.darken.amply.common.AppDataStore
import eu.darken.amply.common.datastore.createValue
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/** Why Amply's process most likely went away. Cosmetic only — never asserts a force-stop. */
@Serializable
enum class InterruptionReason {
    /** The exit reason matched a user-requested stop (ApplicationExitInfo USER_REQUESTED / USER_STOPPED). */
    @SerialName("USER_STOPPED")
    USER_STOPPED,

    /** Anything else, no matching exit record, or the API is unavailable. */
    @SerialName("OTHER")
    OTHER,
}

@Serializable
enum class InterruptionOutcome {
    /** The catch-up restore succeeded — the protective limit is back. */
    @SerialName("RESTORED_LATE")
    RESTORED_LATE,

    /** The restore failed and retry work remains persisted. */
    @SerialName("STILL_PENDING")
    STILL_PENDING,

    /** Recovery gave up without hardware confirmation and cleared its retry state. */
    @SerialName("UNCONFIRMED")
    UNCONFIRMED,
}

@Serializable
data class InterruptionEvent(
    @SerialName("occurredAtMillis") val occurredAtMillis: Long,
    @SerialName("reason") val reason: InterruptionReason,
    @SerialName("outcome") val outcome: InterruptionOutcome,
    /** Stable correlation id of the owed work (see [ChargeSessionRecord.workId]); survives adoption. */
    @SerialName("workId") val workId: String,
)

/**
 * Single-slot persistence for the "Amply was interrupted while owing a restore" dashboard signal.
 * At most one event is held at a time — a newer event overwrites the old. A malformed record (an
 * unknown stored enum, say) decodes to no event, which matches the all-or-nothing decode this had
 * when it was spread across four keys. Shares the single [AppDataStore] like the other facades.
 */
@Singleton
open class InterruptionStore @Inject constructor(
    dataStore: AppDataStore,
    json: Json,
) {
    private val eventValue = dataStore.createValue<InterruptionEvent?>(
        key = "interruption.v2",
        defaultValue = null,
        json = json,
        fallbackToDefault = true,
    )

    val event = eventValue.flow

    open suspend fun record(event: InterruptionEvent) {
        eventValue.update { event }
    }

    /**
     * Upgrade a still-pending / unconfirmed event to [InterruptionOutcome.RESTORED_LATE] once a later
     * restore for the *same* work token succeeds. No-op when there is no event, the token differs, or
     * it is already RESTORED_LATE.
     */
    open suspend fun markRestored(workId: String) {
        eventValue.update { current ->
            when {
                current == null -> null
                current.workId != workId -> current
                current.outcome == InterruptionOutcome.RESTORED_LATE -> current
                else -> current.copy(outcome = InterruptionOutcome.RESTORED_LATE)
            }
        }
    }

    /** Clear the event only if it is not a successful (RESTORED_LATE) one — used on an explicit user write. */
    open suspend fun clearPending() {
        eventValue.update { current ->
            if (current?.outcome == InterruptionOutcome.RESTORED_LATE) current else null
        }
    }

    open suspend fun clear() {
        eventValue.update { null }
    }
}
