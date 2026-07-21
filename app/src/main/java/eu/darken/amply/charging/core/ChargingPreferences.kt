package eu.darken.amply.charging.core

import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import eu.darken.amply.common.AppDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ChargingPreferences @Inject constructor(
    private val dataStore: AppDataStore,
) {
    val lastRequested: Flow<ChargePolicy?> = dataStore.store.data.map {
        ChargePolicy.fromStableId(it[LAST_REQUESTED])
    }

    /** Wall-clock time of the last request; paired atomically with [lastRequested]. 0 = never requested. */
    val lastRequestedAt: Flow<Long> = dataStore.store.data.map { it[LAST_REQUESTED_AT] ?: 0L }

    val protectivePolicy: Flow<ChargePolicy> = dataStore.store.data.map {
        ChargePolicy.fromStableId(it[PROTECTIVE_POLICY])?.takeUnless { policy ->
            policy == ChargePolicy.Unrestricted
        } ?: ChargePolicy.FixedLimit(80)
    }

    /**
     * The last policy Amply successfully applied as a *persistent* configuration — including
     * [ChargePolicy.Unrestricted], unlike [protectivePolicy]. Temporary session overrides never
     * update this, so it answers "what did the user configure through Amply" without a session's
     * transient Unrestricted write polluting the answer. Null until Amply's first persistent write.
     */
    val lastPersistentPolicy: Flow<ChargePolicy?> = dataStore.store.data.map {
        ChargePolicy.fromStableId(it[LAST_PERSISTENT_POLICY])
    }

    suspend fun recordRequested(
        policy: ChargePolicy,
        persistent: Boolean,
        nowMillis: Long = System.currentTimeMillis(),
    ) {
        dataStore.store.edit {
            it[LAST_REQUESTED] = policy.stableId
            it[LAST_REQUESTED_AT] = nowMillis
            if (persistent) {
                it[LAST_PERSISTENT_POLICY] = policy.stableId
                if (policy != ChargePolicy.Unrestricted) it[PROTECTIVE_POLICY] = policy.stableId
            }
        }
    }

    suspend fun lastRequestedNow(): ChargePolicy? = lastRequested.first()

    suspend fun lastRequestedAtNow(): Long = lastRequestedAt.first()

    suspend fun protectivePolicyNow(): ChargePolicy = protectivePolicy.first()

    suspend fun lastPersistentPolicyNow(): ChargePolicy? = lastPersistentPolicy.first()

    private companion object {
        val LAST_REQUESTED = stringPreferencesKey("policy.last_requested")
        val LAST_REQUESTED_AT = longPreferencesKey("policy.last_requested_at")
        val PROTECTIVE_POLICY = stringPreferencesKey("policy.protective")
        val LAST_PERSISTENT_POLICY = stringPreferencesKey("policy.last_persistent")
    }
}
