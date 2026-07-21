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

data class ChargeSessionRecord(
    val restorePolicy: ChargePolicy,
    val startedAtMillis: Long,
    val connectedSeen: Boolean,
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

    suspend fun startSession(restorePolicy: ChargePolicy, startedAtMillis: Long) {
        dataStore.store.edit {
            it[SESSION_ACTIVE] = true
            it[SESSION_RESTORE_POLICY] = restorePolicy.stableId
            it[SESSION_STARTED_AT] = startedAtMillis
            it[SESSION_CONNECTED] = false
        }
    }

    suspend fun markConnected() {
        dataStore.store.edit { prefs ->
            if (prefs[SESSION_ACTIVE] == true) prefs[SESSION_CONNECTED] = true
        }
    }

    suspend fun clearSession() {
        dataStore.store.edit {
            it.remove(SESSION_ACTIVE)
            it.remove(SESSION_RESTORE_POLICY)
            it.remove(SESSION_STARTED_AT)
            it.remove(SESSION_CONNECTED)
        }
    }

    suspend fun pendingRecoveryTarget(): ChargePolicy? =
        dataStore.store.data.first()[RECOVERY_PENDING_TARGET]?.let(ChargePolicy::fromStableId)

    suspend fun setPendingRecoveryTarget(policy: ChargePolicy) {
        dataStore.store.edit { it[RECOVERY_PENDING_TARGET] = policy.stableId }
    }

    suspend fun clearPendingRecoveryTarget() {
        dataStore.store.edit { it.remove(RECOVERY_PENDING_TARGET) }
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
        )
    }

    private companion object {
        val SESSION_ACTIVE = booleanPreferencesKey("session.active")
        val SESSION_RESTORE_POLICY = stringPreferencesKey("session.restore_policy")
        val SESSION_STARTED_AT = longPreferencesKey("session.started_at")
        val SESSION_CONNECTED = booleanPreferencesKey("session.connected_seen")
        val QUICK_FULL_CHARGE_ENABLED = booleanPreferencesKey("fullcharge.quick_replug_enabled")
        val QUICK_FULL_CHARGE_ANY_LEVEL = booleanPreferencesKey("fullcharge.quick_replug_any_level")
        val RECOVERY_PENDING_TARGET = stringPreferencesKey("recovery.pending_target")
        val LAST_SEEN_BOOT_COUNT = intPreferencesKey("recovery.last_seen_boot_count")
    }
}
