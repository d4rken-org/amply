package eu.darken.amply.main.core

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import eu.darken.amply.common.AppDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class OnboardingSettings @Inject constructor(
    private val dataStore: AppDataStore,
) {
    val isComplete: Flow<Boolean> = dataStore.store.data.map { it[IS_COMPLETE] ?: false }

    suspend fun isCompleteNow(): Boolean = isComplete.first()

    suspend fun complete() {
        dataStore.store.edit { it[IS_COMPLETE] = true }
    }

    private companion object {
        val IS_COMPLETE = booleanPreferencesKey("onboarding.v1.complete")
    }
}
