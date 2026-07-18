package eu.darken.amply.charging.core

import androidx.datastore.preferences.core.edit
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

    val protectivePolicy: Flow<ChargePolicy> = dataStore.store.data.map {
        ChargePolicy.fromStableId(it[PROTECTIVE_POLICY])?.takeUnless { policy ->
            policy == ChargePolicy.Unrestricted
        } ?: ChargePolicy.FixedLimit(80)
    }

    suspend fun recordRequested(policy: ChargePolicy, updateProtective: Boolean) {
        dataStore.store.edit {
            it[LAST_REQUESTED] = policy.stableId
            if (updateProtective && policy != ChargePolicy.Unrestricted) {
                it[PROTECTIVE_POLICY] = policy.stableId
            }
        }
    }

    suspend fun lastRequestedNow(): ChargePolicy? = lastRequested.first()

    suspend fun protectivePolicyNow(): ChargePolicy = protectivePolicy.first()

    private companion object {
        val LAST_REQUESTED = stringPreferencesKey("policy.last_requested")
        val PROTECTIVE_POLICY = stringPreferencesKey("policy.protective")
    }
}
