package eu.darken.amply.stats.core.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import eu.darken.amply.stats.core.StatsPowerCalculator

/**
 * One raw battery observation persisted for a charge curve. Belongs to a [ChargeSessionEntity]
 * ([sessionId], cascade-deleted with its session). Retention purges old rows by [wallMillis]; the
 * owning session's summary is unaffected because it is accumulated online, not recomputed from these.
 * Whole entries are additionally purged by session end, which cascades here.
 *
 * [powerMilliwatts] is battery-terminal *charge* power (battery voltage × |current|), not charger/
 * input power. Because the magnitude carries no direction, it is recorded **only while the battery is
 * actually taking charge** (see [StatsPowerCalculator.chargeMilliwatts]) — otherwise draw would be
 * stored as if it were a charge rate. [voltageMillivolts], [currentNowMicroamps] and [batteryStatus]
 * are always kept, so the raw observation survives even where the derived field is null.
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
    /**
     * Precomputed battery-terminal charge power in milliwatts; null if the inputs were absent **or**
     * the battery was not taking charge at the time.
     */
    val powerMilliwatts: Int? = null,
)
