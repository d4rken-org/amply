package eu.darken.amply.stats.core

import eu.darken.amply.common.AppDataStore
import eu.darken.amply.common.datastore.createValue
import eu.darken.amply.common.datastore.value
import javax.inject.Inject
import javax.inject.Singleton

/**
 * DataStore facade for battery-statistics capture, sharing the single [AppDataStore]. Holds only
 * small pointers — the opt-in flag and the wall time of the last successfully recorded sample (so
 * the UI can honestly show "enabled but not currently capturing" when a gap opens). All time-series
 * data lives in Room, never here.
 *
 * [lastCaptureWallMillis] is rewritten on **every** recorded sample (~20s while charging), which is
 * what makes the shared-store fan-out visible: before these became [eu.darken.amply.common.datastore
 * .DataStoreValue]s, each of those writes re-emitted [captureEnabled] unchanged and restarted the
 * dashboard's stats flow, flashing the charging card's loading state on every tick.
 */
@Singleton
class StatsPreferences @Inject constructor(
    dataStore: AppDataStore,
) {
    val captureEnabled = dataStore.createValue("stats.capture_enabled", false)

    val lastCaptureWallMillis = dataStore.createValue<Long?>("stats.last_capture_wall")

    suspend fun isCaptureEnabledNow(): Boolean = captureEnabled.value()

    suspend fun setCaptureEnabled(enabled: Boolean) {
        captureEnabled.value(enabled)
    }

    suspend fun setLastCaptureWallMillis(millis: Long) {
        lastCaptureWallMillis.value(millis)
    }

    suspend fun clearLastCapture() {
        lastCaptureWallMillis.value(null)
    }
}
