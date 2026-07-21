package eu.darken.amply.alarm.core

import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import eu.darken.amply.common.AppDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.roundToInt

/** User-facing charge-alarm configuration, derived from a single DataStore snapshot. */
data class ChargeAlarmConfig(
    val enabled: Boolean = false,
    val targetPercent: Int = DEFAULT_TARGET_PERCENT,
) {
    companion object {
        const val DEFAULT_TARGET_PERCENT = 80
        const val MIN_TARGET_PERCENT = 50
        const val MAX_TARGET_PERCENT = 100
        const val TARGET_STEP = 5
    }
}

/**
 * DataStore facade for the charge alarm, sharing the single [AppDataStore]. Exposes the config as
 * one flow (mapped from a single snapshot, so `enabled` and `targetPercent` never transiently
 * disagree) plus the durable "fired this plug cycle" latch that makes the alarm fire at most once
 * per charge even across process death.
 */
@Singleton
class ChargeAlarmStore @Inject constructor(
    private val dataStore: AppDataStore,
) {
    val config: Flow<ChargeAlarmConfig> = dataStore.store.data.map(::toConfig)

    suspend fun configNow(): ChargeAlarmConfig = config.first()

    suspend fun setEnabled(enabled: Boolean) {
        dataStore.store.edit { it[ENABLED] = enabled }
    }

    suspend fun setTargetPercent(percent: Int) {
        dataStore.store.edit { it[TARGET_PERCENT] = normalizeTarget(percent) }
    }

    /** Whether the alarm has already fired (or been suppressed) for the current plug cycle. */
    suspend fun firedCycle(): Boolean = dataStore.store.data.first()[FIRED_CYCLE] ?: false

    suspend fun setFiredCycle(fired: Boolean) {
        dataStore.store.edit { it[FIRED_CYCLE] = fired }
    }

    private fun toConfig(prefs: Preferences) = ChargeAlarmConfig(
        enabled = prefs[ENABLED] ?: false,
        targetPercent = normalizeTarget(prefs[TARGET_PERCENT] ?: ChargeAlarmConfig.DEFAULT_TARGET_PERCENT),
    )

    private companion object {
        val ENABLED = booleanPreferencesKey("alarm.enabled")
        val TARGET_PERCENT = intPreferencesKey("alarm.target_percent")
        val FIRED_CYCLE = booleanPreferencesKey("alarm.fired_cycle")
    }
}

/** Snap to the nearest [ChargeAlarmConfig.TARGET_STEP] and clamp to the allowed range. */
internal fun normalizeTarget(percent: Int): Int {
    val step = ChargeAlarmConfig.TARGET_STEP
    val snapped = (percent.toDouble() / step).roundToInt() * step
    return snapped.coerceIn(ChargeAlarmConfig.MIN_TARGET_PERCENT, ChargeAlarmConfig.MAX_TARGET_PERCENT)
}
