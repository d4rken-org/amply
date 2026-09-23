package eu.darken.amply.battery.core

import eu.darken.amply.charging.core.enforcement.BuildIdentitySource
import eu.darken.amply.common.AppDataStore
import eu.darken.amply.common.datastore.createValue
import eu.darken.amply.common.datastore.value
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The `CURRENT_NOW` unit learned for one ROM build, so a verdict survives process death instead of
 * being re-learned on every start. Scoped to the build because an update can change the HAL's units.
 */
@Singleton
class BatteryUnitStore @Inject constructor(
    dataStore: AppDataStore,
    private val buildIdentity: BuildIdentitySource,
    json: Json,
) {
    private val stored = dataStore.createValue<StoredCurrentUnit?>(
        key = KEY,
        defaultValue = null,
        json = json,
        fallbackToDefault = true,
    )

    /** The unit learned for this build, or [CurrentUnit.UNKNOWN]. */
    suspend fun read(): CurrentUnit = stored.value().unitFor(buildIdentity.current())

    /**
     * Folds [unit] into this build's record in one transaction and returns the unit now stored. A
     * record for another build is replaced. [CurrentUnit.UNKNOWN] writes nothing.
     */
    suspend fun learn(unit: CurrentUnit): CurrentUnit {
        val learned = unit.toWire() ?: return read()
        val build = buildIdentity.current()
        val updated = stored.update { record ->
            val merged = CurrentUnitInference.merge(record.unitFor(build), learned.toUnit())
            StoredCurrentUnit(build = build, unit = merged.toWire() ?: learned)
        }
        return updated.new.unitFor(build)
    }

    private fun StoredCurrentUnit?.unitFor(build: String): CurrentUnit =
        this?.takeIf { it.build == build }?.unit?.toUnit() ?: CurrentUnit.UNKNOWN

    private fun CurrentUnit.toWire(): StoredUnit? = when (this) {
        CurrentUnit.MILLI -> StoredUnit.MILLI
        CurrentUnit.MICRO -> StoredUnit.MICRO
        CurrentUnit.UNKNOWN -> null
    }

    private fun StoredUnit.toUnit(): CurrentUnit = when (this) {
        StoredUnit.MILLI -> CurrentUnit.MILLI
        StoredUnit.MICRO -> CurrentUnit.MICRO
    }

    companion object {
        internal const val KEY = "battery.current_unit.v1"
    }
}

/** Only a decided unit is ever stored; "not learned yet" is the absence of a record. */
@Serializable
internal data class StoredCurrentUnit(
    @SerialName("build") val build: String = "",
    @SerialName("unit") val unit: StoredUnit? = null,
)

@Serializable
internal enum class StoredUnit {
    @SerialName("milli")
    MILLI,

    @SerialName("micro")
    MICRO,
}
