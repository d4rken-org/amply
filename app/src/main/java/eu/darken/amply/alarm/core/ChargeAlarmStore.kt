package eu.darken.amply.alarm.core

import eu.darken.amply.common.AppDataStore
import eu.darken.amply.common.datastore.createValue
import eu.darken.amply.common.datastore.value
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.roundToInt

/** User-facing charge-alarm configuration, persisted as one record. */
@Serializable
data class ChargeAlarmConfig(
    @SerialName("enabled") val enabled: Boolean = false,
    @SerialName("targetPercent") val targetPercent: Int = DEFAULT_TARGET_PERCENT,
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
 * one record — `enabled` and `targetPercent` live under a single key, so they can never transiently
 * disagree — plus the durable "fired this plug cycle" latch that makes the alarm fire at most once
 * per charge even across process death.
 *
 * The latch is a separate value on purpose: it is written by the watcher on a completely different
 * cadence than the user's config, and folding it into the record would wake every config collector
 * each time the alarm fires or resets.
 */
@Singleton
class ChargeAlarmStore @Inject constructor(
    dataStore: AppDataStore,
    json: Json,
) {
    private val configValue = dataStore.createValue(
        key = "alarm.config.v2",
        defaultValue = ChargeAlarmConfig(),
        json = json,
        fallbackToDefault = true,
    )

    private val firedCycleValue = dataStore.createValue("alarm.fired_cycle.v2", false)

    /**
     * Normalized on the way out — the setter snaps too, but a hand-edited or future-written record
     * could still carry an off-step target and the UI's stepper assumes a valid tick.
     *
     * Deduped *again* after normalizing, because snapping is many-to-one: a stored 83 and a
     * subsequent explicit 85 are two distinct raw records that both normalize to 85, so the upstream
     * raw-value dedupe cannot catch the duplicate.
     */
    val config: Flow<ChargeAlarmConfig> = configValue.flow.map(::normalize).distinctUntilChanged()

    suspend fun configNow(): ChargeAlarmConfig = normalize(configValue.value())

    suspend fun setEnabled(enabled: Boolean) {
        configValue.update { it.copy(enabled = enabled) }
    }

    suspend fun setTargetPercent(percent: Int) {
        configValue.update { it.copy(targetPercent = normalizeTarget(percent)) }
    }

    /** Whether the alarm has already fired (or been suppressed) for the current plug cycle. */
    suspend fun firedCycle(): Boolean = firedCycleValue.value()

    suspend fun setFiredCycle(fired: Boolean) {
        firedCycleValue.value(fired)
    }

    private fun normalize(raw: ChargeAlarmConfig) = raw.copy(targetPercent = normalizeTarget(raw.targetPercent))
}

/** Snap to the nearest [ChargeAlarmConfig.TARGET_STEP] and clamp to the allowed range. */
internal fun normalizeTarget(percent: Int): Int {
    val step = ChargeAlarmConfig.TARGET_STEP
    val snapped = (percent.toDouble() / step).roundToInt() * step
    return snapped.coerceIn(ChargeAlarmConfig.MIN_TARGET_PERCENT, ChargeAlarmConfig.MAX_TARGET_PERCENT)
}
