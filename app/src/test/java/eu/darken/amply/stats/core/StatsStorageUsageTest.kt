package eu.darken.amply.stats.core

import android.content.Context
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import eu.darken.amply.stats.core.db.BatterySampleEntity
import eu.darken.amply.stats.core.db.ChargeSessionEntity
import eu.darken.amply.stats.core.db.StatsDatabase
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.longs.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** File-backed on purpose: both the existence check and the PRAGMAs need a real `stats.db`. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class StatsStorageUsageTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val collectScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    // Built from the test thread or from the flow's IO thread, whichever gets there first.
    @Volatile private var database: StatsDatabase? = null

    @Before
    fun setup() {
        context.deleteDatabase(StatsDatabase.NAME)
    }

    @After
    fun teardown() {
        collectScope.cancel()
        database?.close()
        context.deleteDatabase(StatsDatabase.NAME)
    }

    @Synchronized
    private fun openDatabase(): StatsDatabase =
        database ?: Room.databaseBuilder(context, StatsDatabase::class.java, StatsDatabase.NAME)
            .build()
            .also { database = it }

    private fun usage(db: dagger.Lazy<StatsDatabase> = dagger.Lazy { openDatabase() }) =
        StatsStorageUsage(context, db, Dispatchers.IO, POLL_MILLIS)

    private fun dbFileExists() = context.getDatabasePath(StatsDatabase.NAME).exists()

    private fun collect(usage: StatsStorageUsage): MutableStateFlow<List<Long?>> {
        val values = MutableStateFlow<List<Long?>>(emptyList())
        collectScope.launch {
            usage.historyStorageBytes().collect { value -> values.update { it + value } }
        }
        return values
    }

    private suspend fun insertSession(db: StatsDatabase): Long = db.statsDao().insertSession(
        ChargeSessionEntity(
            startedAtWallMillis = 1_000L,
            startedElapsedRealtimeMillis = 1_000L,
            bootId = 7,
            startPercent = 40,
        ),
    )

    @Test
    fun `without a database file it emits null and never creates one`(): Unit = runBlocking {
        var opened = false
        val values = collect(usage(dagger.Lazy { opened = true; error("stats database must not be opened") }))

        withTimeout(TIMEOUT_MS) { values.first { it.isNotEmpty() } }
        delay(POLL_MILLIS * 5)

        values.value shouldBe listOf(null)
        opened.shouldBeFalse()
        dbFileExists().shouldBeFalse()
    }

    @Test
    fun `a database created while collecting makes a size appear`(): Unit = runBlocking {
        val values = collect(usage())
        withTimeout(TIMEOUT_MS) { values.first { it.isNotEmpty() } }
        values.value shouldBe listOf(null)

        insertSession(openDatabase())

        val size = withTimeout(TIMEOUT_MS) { values.first { it.lastOrNull() != null } }.last()!!
        size shouldBeGreaterThan 0L
    }

    @Test
    fun `clearing every sample shrinks the used size`(): Unit = runBlocking {
        val db = openDatabase()
        val sessionId = insertSession(db)
        db.withTransaction {
            repeat(SAMPLE_COUNT) { i ->
                db.statsDao().insertSample(
                    BatterySampleEntity(
                        sessionId = sessionId,
                        wallMillis = 1_000L + i,
                        elapsedRealtimeMillis = 1_000L + i,
                        bootId = 7,
                        percent = 40 + i % 60,
                        batteryStatus = 2,
                        temperatureTenthsC = 300,
                        voltageMillivolts = 4_000,
                        currentNowMicroamps = 2_000_000,
                        powerMilliwatts = 8_000,
                    ),
                )
            }
        }
        val values = collect(usage())
        val before = withTimeout(TIMEOUT_MS) { values.first { it.lastOrNull() != null } }.last()!!

        db.statsDao().deleteAllSamples()

        withTimeout(TIMEOUT_MS) { values.first { (it.lastOrNull() ?: Long.MAX_VALUE) < before } }
    }

    @Test
    fun `a failing database yields null instead of an exception`(): Unit = runBlocking {
        val file = context.getDatabasePath(StatsDatabase.NAME)
        file.parentFile?.mkdirs()
        file.createNewFile()

        val values = withTimeout(TIMEOUT_MS) {
            usage(dagger.Lazy { error("stats database is broken") }).historyStorageBytes().toList()
        }

        values shouldBe listOf(null)
    }

    private companion object {
        const val POLL_MILLIS = 50L
        const val TIMEOUT_MS = 10_000L
        const val SAMPLE_COUNT = 5_000
    }
}
