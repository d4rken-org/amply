package eu.darken.amply.fullcharge.core

import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import eu.darken.amply.charging.core.ChargePolicy
import eu.darken.amply.common.AppDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Provenance of persisted work: which process created it and during which boot. Used to spot work
 * that survived a process death ([token] mismatch) within the same boot ([bootCount] match). A null
 * value marks a legacy record written before provenance existed.
 */
data class WorkProvenance(
    val token: String,
    val pid: Int,
    val bootCount: Int?,
    val createdAtMillis: Long,
)

data class ChargeSessionRecord(
    val restorePolicy: ChargePolicy,
    val startedAtMillis: Long,
    val connectedSeen: Boolean,
    val provenance: WorkProvenance? = null,
    /**
     * Stable correlation id for the owed work, generated once at creation and preserved across
     * process-adoption (unlike [WorkProvenance.token], which is re-stamped to the current owner). An
     * interruption event ties back to this so a later restore can resolve it. Null on legacy records.
     */
    val workId: String? = null,
)

@Singleton
class FullChargeStore @Inject constructor(
    private val dataStore: AppDataStore,
) {
    val session: Flow<ChargeSessionRecord?> = dataStore.store.data.map(::toRecord)
    val quickFullChargeEnabled: Flow<Boolean> = dataStore.store.data.map {
        it[QUICK_FULL_CHARGE_ENABLED] ?: false
    }
    val quickFullChargeAnyLevel: Flow<Boolean> = dataStore.store.data.map {
        it[QUICK_FULL_CHARGE_ANY_LEVEL] ?: false
    }

    suspend fun currentSession(): ChargeSessionRecord? = session.first()

    suspend fun startSession(
        restorePolicy: ChargePolicy,
        startedAtMillis: Long,
        workId: String? = null,
        provenance: WorkProvenance? = null,
    ) {
        dataStore.store.edit {
            it[SESSION_ACTIVE] = true
            it[SESSION_RESTORE_POLICY] = restorePolicy.stableId
            it[SESSION_STARTED_AT] = startedAtMillis
            it[SESSION_CONNECTED] = false
            workId?.let { id -> it[SESSION_WORK_ID] = id } ?: it.remove(SESSION_WORK_ID)
            it.writeSessionProvenance(provenance)
        }
    }

    suspend fun markConnected() {
        dataStore.store.edit { prefs ->
            if (prefs[SESSION_ACTIVE] == true) prefs[SESSION_CONNECTED] = true
        }
    }

    /** Adopt the current process as the session's owner and flag CONNECTED in a single atomic edit. */
    suspend fun markConnectedAndAdopt(provenance: WorkProvenance) {
        dataStore.store.edit { prefs ->
            if (prefs[SESSION_ACTIVE] != true) return@edit
            prefs[SESSION_CONNECTED] = true
            prefs.writeSessionProvenance(provenance)
        }
    }

    /** Re-stamp the active session's provenance to the current process, if a session is present. */
    suspend fun adoptSessionOwner(provenance: WorkProvenance) {
        dataStore.store.edit { prefs ->
            if (prefs[SESSION_ACTIVE] != true) return@edit
            prefs.writeSessionProvenance(provenance)
        }
    }

    suspend fun clearSession() {
        dataStore.store.edit {
            it.remove(SESSION_ACTIVE)
            it.remove(SESSION_RESTORE_POLICY)
            it.remove(SESSION_STARTED_AT)
            it.remove(SESSION_CONNECTED)
            it.remove(SESSION_WORK_ID)
            it.remove(SESSION_OWNER_TOKEN)
            it.remove(SESSION_OWNER_PID)
            it.remove(SESSION_BOOT_COUNT)
            it.remove(SESSION_OWNER_SET_AT)
        }
    }

    suspend fun pendingRecoveryTarget(): ChargePolicy? =
        dataStore.store.data.first()[RECOVERY_PENDING_TARGET]?.let(ChargePolicy::fromStableId)

    suspend fun pendingRecoveryProvenance(): WorkProvenance? =
        dataStore.store.data.first().recoveryProvenance()

    suspend fun pendingRecoveryWorkId(): String? =
        dataStore.store.data.first()[RECOVERY_WORK_ID]

    suspend fun setPendingRecoveryTarget(
        policy: ChargePolicy,
        workId: String? = null,
        provenance: WorkProvenance? = null,
    ) {
        dataStore.store.edit {
            it[RECOVERY_PENDING_TARGET] = policy.stableId
            workId?.let { id -> it[RECOVERY_WORK_ID] = id } ?: it.remove(RECOVERY_WORK_ID)
            it.writeRecoveryProvenance(provenance)
        }
    }

    /** Re-stamp the pending recovery target's provenance to the current process, if one is present. */
    suspend fun adoptRecoveryOwner(provenance: WorkProvenance) {
        dataStore.store.edit { prefs ->
            if (prefs[RECOVERY_PENDING_TARGET] == null) return@edit
            prefs.writeRecoveryProvenance(provenance)
        }
    }

    suspend fun clearPendingRecoveryTarget() {
        dataStore.store.edit {
            it.remove(RECOVERY_PENDING_TARGET)
            it.remove(RECOVERY_WORK_ID)
            it.remove(RECOVERY_OWNER_TOKEN)
            it.remove(RECOVERY_OWNER_PID)
            it.remove(RECOVERY_BOOT_COUNT)
            it.remove(RECOVERY_SET_AT)
        }
    }

    /** The boot count during which Amply last ran — used to spot re-delivered BOOT_COMPLETED broadcasts. */
    suspend fun lastSeenBootCount(): Int? = dataStore.store.data.first()[LAST_SEEN_BOOT_COUNT]

    suspend fun setLastSeenBootCount(count: Int) {
        dataStore.store.edit { it[LAST_SEEN_BOOT_COUNT] = count }
    }

    suspend fun isQuickFullChargeEnabled(): Boolean = quickFullChargeEnabled.first()

    suspend fun setQuickFullChargeEnabled(enabled: Boolean) {
        dataStore.store.edit { it[QUICK_FULL_CHARGE_ENABLED] = enabled }
    }

    suspend fun isQuickFullChargeAnyLevel(): Boolean = quickFullChargeAnyLevel.first()

    suspend fun setQuickFullChargeAnyLevel(enabled: Boolean) {
        dataStore.store.edit { it[QUICK_FULL_CHARGE_ANY_LEVEL] = enabled }
    }

    private fun toRecord(prefs: Preferences): ChargeSessionRecord? {
        if (prefs[SESSION_ACTIVE] != true) return null
        val policy = ChargePolicy.fromStableId(prefs[SESSION_RESTORE_POLICY]) ?: return null
        return ChargeSessionRecord(
            restorePolicy = policy,
            startedAtMillis = prefs[SESSION_STARTED_AT] ?: 0L,
            connectedSeen = prefs[SESSION_CONNECTED] ?: false,
            provenance = prefs.sessionProvenance(),
            workId = prefs[SESSION_WORK_ID],
        )
    }

    // A record is only stamped with provenance when a token is present; a missing token (legacy
    // record) decodes to null so the assessor treats it as un-owned and adopts it silently.
    private fun Preferences.sessionProvenance(): WorkProvenance? {
        val token = this[SESSION_OWNER_TOKEN] ?: return null
        return WorkProvenance(
            token = token,
            pid = this[SESSION_OWNER_PID] ?: -1,
            bootCount = this[SESSION_BOOT_COUNT],
            // The owner timestamp is written on every (re-)stamp; fall back to the session start for
            // legacy records that predate it.
            createdAtMillis = this[SESSION_OWNER_SET_AT] ?: this[SESSION_STARTED_AT] ?: 0L,
        )
    }

    private fun Preferences.recoveryProvenance(): WorkProvenance? {
        val token = this[RECOVERY_OWNER_TOKEN] ?: return null
        return WorkProvenance(
            token = token,
            pid = this[RECOVERY_OWNER_PID] ?: -1,
            bootCount = this[RECOVERY_BOOT_COUNT],
            createdAtMillis = this[RECOVERY_SET_AT] ?: 0L,
        )
    }

    private fun androidx.datastore.preferences.core.MutablePreferences.writeSessionProvenance(
        provenance: WorkProvenance?,
    ) {
        if (provenance == null) {
            remove(SESSION_OWNER_TOKEN)
            remove(SESSION_OWNER_PID)
            remove(SESSION_BOOT_COUNT)
            remove(SESSION_OWNER_SET_AT)
            return
        }
        this[SESSION_OWNER_TOKEN] = provenance.token
        this[SESSION_OWNER_PID] = provenance.pid
        provenance.bootCount?.let { this[SESSION_BOOT_COUNT] = it } ?: remove(SESSION_BOOT_COUNT)
        this[SESSION_OWNER_SET_AT] = provenance.createdAtMillis
    }

    private fun androidx.datastore.preferences.core.MutablePreferences.writeRecoveryProvenance(
        provenance: WorkProvenance?,
    ) {
        if (provenance == null) {
            remove(RECOVERY_OWNER_TOKEN)
            remove(RECOVERY_OWNER_PID)
            remove(RECOVERY_BOOT_COUNT)
            remove(RECOVERY_SET_AT)
            return
        }
        this[RECOVERY_OWNER_TOKEN] = provenance.token
        this[RECOVERY_OWNER_PID] = provenance.pid
        provenance.bootCount?.let { this[RECOVERY_BOOT_COUNT] = it } ?: remove(RECOVERY_BOOT_COUNT)
        this[RECOVERY_SET_AT] = provenance.createdAtMillis
    }

    private companion object {
        val SESSION_ACTIVE = booleanPreferencesKey("session.active")
        val SESSION_RESTORE_POLICY = stringPreferencesKey("session.restore_policy")
        val SESSION_STARTED_AT = longPreferencesKey("session.started_at")
        val SESSION_CONNECTED = booleanPreferencesKey("session.connected_seen")
        val SESSION_WORK_ID = stringPreferencesKey("session.work_id")
        val SESSION_OWNER_TOKEN = stringPreferencesKey("session.owner_token")
        val SESSION_OWNER_PID = intPreferencesKey("session.owner_pid")
        val SESSION_BOOT_COUNT = intPreferencesKey("session.boot_count")
        val SESSION_OWNER_SET_AT = longPreferencesKey("session.owner_set_at")
        val QUICK_FULL_CHARGE_ENABLED = booleanPreferencesKey("fullcharge.quick_replug_enabled")
        val QUICK_FULL_CHARGE_ANY_LEVEL = booleanPreferencesKey("fullcharge.quick_replug_any_level")
        val RECOVERY_PENDING_TARGET = stringPreferencesKey("recovery.pending_target")
        val RECOVERY_WORK_ID = stringPreferencesKey("recovery.work_id")
        val RECOVERY_OWNER_TOKEN = stringPreferencesKey("recovery.owner_token")
        val RECOVERY_OWNER_PID = intPreferencesKey("recovery.owner_pid")
        val RECOVERY_BOOT_COUNT = intPreferencesKey("recovery.boot_count")
        val RECOVERY_SET_AT = longPreferencesKey("recovery.set_at")
        val LAST_SEEN_BOOT_COUNT = intPreferencesKey("recovery.last_seen_boot_count")
    }
}
