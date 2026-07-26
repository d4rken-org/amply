package eu.darken.amply.stats.core

import eu.darken.amply.common.AppDataStore
import eu.darken.amply.common.datastore.createValue
import eu.darken.amply.common.datastore.value
import javax.inject.Inject
import javax.inject.Singleton

/**
 * DataStore facade for battery-statistics capture, sharing the single [AppDataStore]. Holds only the
 * two knobs the feature is configured with — the opt-in flag and the retention window. All time-series
 * data lives in Room, never here.
 *
 * Both are cold: nothing on a charging hot path writes here. This facade is nevertheless where the
 * shared-store fan-out first became visible, through a since-removed per-sample "last capture"
 * timestamp that was rewritten every ~20s while charging: before these became
 * [eu.darken.amply.common.datastore.DataStoreValue]s, each of those writes re-emitted [captureEnabled]
 * unchanged and restarted the dashboard's stats flow, flashing the charging card's loading state on
 * every tick (see `code-style.md`). The orphaned `stats.last_capture_wall` key is left behind in
 * existing stores — harmless, and Amply is pre-launch, so no migration is written.
 */
@Singleton
class StatsPreferences @Inject constructor(
    dataStore: AppDataStore,
) {
    val captureEnabled = dataStore.createValue("stats.capture_enabled", false)

    val retentionDays = dataStore.createValue("stats.retention_days", StatsRetention.DEFAULT_DAYS)

    suspend fun isCaptureEnabledNow(): Boolean = captureEnabled.value()

    suspend fun setCaptureEnabled(enabled: Boolean) {
        captureEnabled.value(enabled)
    }

    suspend fun retentionDaysNow(): Int = StatsRetention.clampDays(retentionDays.value())

    suspend fun setRetentionDays(days: Int) {
        retentionDays.value(StatsRetention.clampDays(days))
    }
}
