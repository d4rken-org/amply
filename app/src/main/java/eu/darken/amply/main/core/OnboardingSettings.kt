package eu.darken.amply.main.core

import eu.darken.amply.common.AppDataStore
import eu.darken.amply.common.datastore.createValue
import eu.darken.amply.common.datastore.value
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class OnboardingSettings @Inject constructor(
    dataStore: AppDataStore,
) {
    val isComplete = dataStore.createValue("onboarding.v1.complete", false)

    suspend fun isCompleteNow(): Boolean = isComplete.value()

    suspend fun complete() {
        isComplete.value(true)
    }
}
