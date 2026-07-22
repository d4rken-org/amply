package eu.darken.amply.stats.core

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import eu.darken.amply.common.AppDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * DataStore facade for battery-statistics capture, sharing the single [AppDataStore]. Holds only
 * small pointers — the opt-in flag and the wall time of the last successfully recorded sample (so
 * the UI can honestly show "enabled but not currently capturing" when a gap opens). All time-series
 * data lives in Room, never here.
 */
@Singleton
class StatsPreferences @Inject constructor(
    private val dataStore: AppDataStore,
) {
    val captureEnabled: Flow<Boolean> = dataStore.store.data.map { it[ENABLED] ?: false }

    val lastCaptureWallMillis: Flow<Long?> = dataStore.store.data.map { it[LAST_CAPTURE] }

    suspend fun isCaptureEnabledNow(): Boolean = captureEnabled.first()

    suspend fun setCaptureEnabled(enabled: Boolean) {
        dataStore.store.edit { it[ENABLED] = enabled }
    }

    suspend fun setLastCaptureWallMillis(millis: Long) {
        dataStore.store.edit { it[LAST_CAPTURE] = millis }
    }

    suspend fun clearLastCapture() {
        dataStore.store.edit { it.remove(LAST_CAPTURE) }
    }

    private companion object {
        val ENABLED = booleanPreferencesKey("stats.capture_enabled")
        val LAST_CAPTURE = longPreferencesKey("stats.last_capture_wall")
    }
}
