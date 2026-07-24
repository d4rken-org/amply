package eu.darken.amply.fullcharge.core

import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import eu.darken.amply.common.AppDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/** Why Amply's process most likely went away. Cosmetic only — never asserts a force-stop. */
enum class InterruptionReason {
    /** The exit reason matched a user-requested stop (ApplicationExitInfo USER_REQUESTED / USER_STOPPED). */
    USER_STOPPED,

    /** Anything else, no matching exit record, or the API is unavailable. */
    OTHER,
}

enum class InterruptionOutcome {
    /** The catch-up restore succeeded — the protective limit is back. */
    RESTORED_LATE,

    /** The restore failed and retry work remains persisted. */
    STILL_PENDING,

    /** Recovery gave up without hardware confirmation and cleared its retry state. */
    UNCONFIRMED,
}

data class InterruptionEvent(
    val occurredAtMillis: Long,
    val reason: InterruptionReason,
    val outcome: InterruptionOutcome,
    /** Stable correlation id of the owed work (see [ChargeSessionRecord.workId]); survives adoption. */
    val workId: String,
)

/**
 * Single-slot persistence for the "Amply was interrupted while owing a restore" dashboard signal.
 * At most one event is held at a time — a newer event overwrites the old. A malformed/unknown stored
 * enum decodes to no event. Shares the single [AppDataStore] like the other feature facades.
 */
@Singleton
open class InterruptionStore @Inject constructor(
    private val dataStore: AppDataStore,
) {
    val event: Flow<InterruptionEvent?> = dataStore.store.data.map(::toEvent)

    open suspend fun record(event: InterruptionEvent) {
        dataStore.store.edit {
            it[OCCURRED_AT] = event.occurredAtMillis
            it[REASON] = event.reason.name
            it[OUTCOME] = event.outcome.name
            it[WORK_TOKEN] = event.workId
        }
    }

    /**
     * Upgrade a still-pending / unconfirmed event to [InterruptionOutcome.RESTORED_LATE] once a later
     * restore for the *same* work token succeeds. No-op when there is no event, the token differs, or
     * it is already RESTORED_LATE.
     */
    open suspend fun markRestored(workId: String) {
        dataStore.store.edit { prefs ->
            val current = toEvent(prefs) ?: return@edit
            if (current.workId != workId) return@edit
            if (current.outcome == InterruptionOutcome.RESTORED_LATE) return@edit
            prefs[OUTCOME] = InterruptionOutcome.RESTORED_LATE.name
        }
    }

    /** Clear the event only if it is not a successful (RESTORED_LATE) one — used on an explicit user write. */
    open suspend fun clearPending() {
        dataStore.store.edit { prefs ->
            val current = toEvent(prefs) ?: return@edit
            if (current.outcome == InterruptionOutcome.RESTORED_LATE) return@edit
            prefs.clearKeys()
        }
    }

    open suspend fun clear() {
        dataStore.store.edit { it.clearKeys() }
    }

    private fun androidx.datastore.preferences.core.MutablePreferences.clearKeys() {
        remove(OCCURRED_AT)
        remove(REASON)
        remove(OUTCOME)
        remove(WORK_TOKEN)
    }

    private fun toEvent(prefs: Preferences): InterruptionEvent? {
        val occurredAt = prefs[OCCURRED_AT] ?: return null
        val reason = prefs[REASON]?.let { name ->
            InterruptionReason.entries.firstOrNull { it.name == name }
        } ?: return null
        val outcome = prefs[OUTCOME]?.let { name ->
            InterruptionOutcome.entries.firstOrNull { it.name == name }
        } ?: return null
        val workId = prefs[WORK_TOKEN] ?: return null
        return InterruptionEvent(occurredAt, reason, outcome, workId)
    }

    private companion object {
        val OCCURRED_AT = longPreferencesKey("interruption.v1.occurred_at")
        val REASON = stringPreferencesKey("interruption.v1.reason")
        val OUTCOME = stringPreferencesKey("interruption.v1.outcome")
        val WORK_TOKEN = stringPreferencesKey("interruption.v1.work_token")
    }
}
