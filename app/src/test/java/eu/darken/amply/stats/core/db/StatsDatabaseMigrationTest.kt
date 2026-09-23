package eu.darken.amply.stats.core.db

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Opens an on-disk version-1 stats database through the production [StatsDatabase] and checks that
 * the history survives the upgrade. The v1 file is written with plain SQLite from the statements in
 * `app/schemas/.../StatsDatabase/1.json`, frozen here so the baseline cannot drift with the entities.
 * Room validates the migrated schema against the entities on open, so a migration that misses part of
 * the current schema fails the open itself.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class StatsDatabaseMigrationTest {

    private lateinit var context: Context
    private var database: StatsDatabase? = null

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase(StatsDatabase.NAME)
    }

    @After
    fun teardown() {
        database?.close()
        context.deleteDatabase(StatsDatabase.NAME)
    }

    @Test
    fun `version 1 history survives the upgrade to version 2 and gains the session indices`() = runTest {
        createVersion1Database { db ->
            db.execSQL(sessionInsert(id = 1, startWall = 1_000L, endWall = 5_000L, endReason = "'UNPLUGGED'"))
            db.execSQL(sessionInsert(id = 2, startWall = 8_000L, endWall = null, endReason = "NULL"))
            db.execSQL(sampleInsert(id = 10, sessionId = 1, wall = 1_100L))
            db.execSQL(sampleInsert(id = 11, sessionId = 1, wall = 1_200L))
            db.execSQL(sampleInsert(id = 12, sessionId = 1, wall = 1_300L))
            db.execSQL(sampleInsert(id = 20, sessionId = 2, wall = 8_100L))
        }

        val migrated = Room.databaseBuilder(context, StatsDatabase::class.java, StatsDatabase.NAME)
            .allowMainThreadQueries()
            .build()
            .also { database = it }
        val dao = migrated.statsDao()

        dao.sessionById(1)!!.run {
            startedAtWallMillis shouldBe 1_000L
            startedElapsedRealtimeMillis shouldBe 1_000L
            endedAtWallMillis shouldBe 5_000L
            endedElapsedRealtimeMillis shouldBe 5_000L
            endReason shouldBe "UNPLUGGED"
            startPercent shouldBe 40
            runningSampleCount shouldBe 3
        }
        dao.sessionById(2)!!.run {
            startedAtWallMillis shouldBe 8_000L
            endedAtWallMillis shouldBe null
            endedElapsedRealtimeMillis shouldBe null
        }
        dao.openSessions().map { it.id } shouldBe listOf(2L)

        dao.samplesForSessionNow(1).map { it.id to it.wallMillis } shouldBe
            listOf(10L to 1_100L, 11L to 1_200L, 12L to 1_300L)
        dao.samplesForSessionNow(2).map { it.id to it.wallMillis } shouldBe listOf(20L to 8_100L)
        dao.samplesForSessionNow(2).single().percent shouldBe 50

        val sqlite = migrated.openHelper.writableDatabase
        sqlite.version shouldBe 2
        val indexNames = sqlite.query("PRAGMA index_list(`charge_sessions`)").use { cursor ->
            val nameColumn = cursor.getColumnIndexOrThrow("name")
            buildList { while (cursor.moveToNext()) add(cursor.getString(nameColumn)) }
        }
        indexNames shouldContainAll listOf(
            "index_charge_sessions_startedAtWallMillis",
            "index_charge_sessions_endedAtWallMillis",
        )
    }

    private fun createVersion1Database(populate: (SQLiteDatabase) -> Unit) {
        val file = context.getDatabasePath(StatsDatabase.NAME)
        file.parentFile?.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            VERSION_1_SCHEMA.forEach { db.execSQL(it) }
            populate(db)
            db.version = 1
        }
    }

    private fun sessionInsert(id: Long, startWall: Long, endWall: Long?, endReason: String): String {
        val end = endWall?.toString() ?: "NULL"
        return "INSERT INTO charge_sessions (id, startedAtWallMillis, startedElapsedRealtimeMillis, bootId, " +
            "endedAtWallMillis, endedElapsedRealtimeMillis, endReason, startPercent, partial, runningSampleCount, " +
            "runningPowerWeightedSum, runningPowerWeightedDurationMillis, runningTemperatureWeightedSum, " +
            "runningTemperatureWeightedDurationMillis, limitHitEvidence, overrideSeen) " +
            "VALUES ($id, $startWall, $startWall, 7, $end, $end, $endReason, 40, 0, 3, 0.0, 0, 0.0, 0, 0, 0)"
    }

    private fun sampleInsert(id: Long, sessionId: Long, wall: Long): String =
        "INSERT INTO battery_samples (id, sessionId, wallMillis, elapsedRealtimeMillis, bootId, percent) " +
            "VALUES ($id, $sessionId, $wall, $wall, 7, 50)"

    companion object {
        /** `createSql` + index `createSql` + `setupQueries` of the committed 1.json, verbatim. */
        private val VERSION_1_SCHEMA = listOf(
            "CREATE TABLE IF NOT EXISTS `charge_sessions` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `startedAtWallMillis` INTEGER NOT NULL, `startedElapsedRealtimeMillis` INTEGER NOT NULL, `bootId` INTEGER NOT NULL, `endedAtWallMillis` INTEGER, `endedElapsedRealtimeMillis` INTEGER, `endReason` TEXT, `startPercent` INTEGER, `endPercent` INTEGER, `pluggedRaw` INTEGER, `fullReachedAtWallMillis` INTEGER, `partial` INTEGER NOT NULL, `avgPowerMilliwatts` INTEGER, `avgTemperatureTenthsC` INTEGER, `runningSampleCount` INTEGER NOT NULL, `runningPowerWeightedSum` REAL NOT NULL, `runningPowerWeightedDurationMillis` INTEGER NOT NULL, `runningTemperatureWeightedSum` REAL NOT NULL, `runningTemperatureWeightedDurationMillis` INTEGER NOT NULL, `runningPeakPowerMilliwatts` INTEGER, `runningMinTemperatureTenthsC` INTEGER, `runningMaxTemperatureTenthsC` INTEGER, `runningLastPowerMilliwatts` INTEGER, `runningLastTemperatureTenthsC` INTEGER, `runningLastPercent` INTEGER, `runningLastElapsedRealtimeMillis` INTEGER, `runningLastWallMillis` INTEGER, `limitHitEvidence` INTEGER NOT NULL, `overrideSeen` INTEGER NOT NULL)",
            "CREATE TABLE IF NOT EXISTS `battery_samples` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `sessionId` INTEGER NOT NULL, `wallMillis` INTEGER NOT NULL, `elapsedRealtimeMillis` INTEGER NOT NULL, `bootId` INTEGER NOT NULL, `percent` INTEGER, `batteryStatus` INTEGER, `chargingStatus` INTEGER, `pluggedRaw` INTEGER, `temperatureTenthsC` INTEGER, `voltageMillivolts` INTEGER, `currentNowMicroamps` INTEGER, `powerMilliwatts` INTEGER, FOREIGN KEY(`sessionId`) REFERENCES `charge_sessions`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
            "CREATE INDEX IF NOT EXISTS `index_battery_samples_sessionId_elapsedRealtimeMillis` ON `battery_samples` (`sessionId`, `elapsedRealtimeMillis`)",
            "CREATE INDEX IF NOT EXISTS `index_battery_samples_wallMillis` ON `battery_samples` (`wallMillis`)",
            "CREATE TABLE IF NOT EXISTS room_master_table (id INTEGER PRIMARY KEY,identity_hash TEXT)",
            "INSERT OR REPLACE INTO room_master_table (id,identity_hash) VALUES(42, 'd39f220aefbc6937a7d9e2dde83995f1')",
        )
    }
}
