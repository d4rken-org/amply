package eu.darken.amply.stats.core.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One raw battery observation persisted for a charge curve. Belongs to a [ChargeSessionEntity]
 * ([sessionId], cascade-deleted with its session). Retention purges old rows by [wallMillis]; the
 * owning session's summary is unaffected because it is accumulated online, not recomputed from these.
 *
 * [powerMilliwatts] is battery-terminal power (battery voltage × |current|), not charger/input
 * power, and cannot distinguish charging from discharging by itself — the owning session's phase and
 * the [chargingStatus]/[batteryStatus] provide direction.
 */
@Entity(
    tableName = "battery_samples",
    foreignKeys = [
        ForeignKey(
            entity = ChargeSessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        // Curve reads and cascade deletes go by session, ordered by time.
        Index("sessionId", "elapsedRealtimeMillis"),
        // Retention purges by wall time.
        Index("wallMillis"),
    ],
)
data class BatterySampleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,

    val sessionId: Long,

    val wallMillis: Long,
    val elapsedRealtimeMillis: Long,
    val bootId: Long,

    val percent: Int? = null,
    val batteryStatus: Int? = null,
    /** Raw [android.os.BatteryManager.EXTRA_CHARGING_STATUS] (hidden Pixel charge-policy state). */
    val chargingStatus: Int? = null,
    val pluggedRaw: Int? = null,

    val temperatureTenthsC: Int? = null,
    val voltageMillivolts: Int? = null,
    val currentNowMicroamps: Int? = null,
    /** Precomputed battery-terminal power magnitude in milliwatts; null if inputs were absent. */
    val powerMilliwatts: Int? = null,
)
