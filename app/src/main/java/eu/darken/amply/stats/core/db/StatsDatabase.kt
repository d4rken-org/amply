package eu.darken.amply.stats.core.db

import androidx.room.Database
import androidx.room.RoomDatabase

/**
 * Room store for battery statistics. Version 1 is P1-only (charge sessions + their raw samples);
 * discharge and battery-health tables land in later versions as additive, tested migrations.
 * Schemas are exported to `app/schemas` (see the KSP `room.schemaLocation` arg) so those migrations
 * have a committed baseline to diff against.
 */
@Database(
    entities = [
        ChargeSessionEntity::class,
        BatterySampleEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class StatsDatabase : RoomDatabase() {
    abstract fun statsDao(): StatsDao

    companion object {
        const val NAME = "stats.db"
    }
}
