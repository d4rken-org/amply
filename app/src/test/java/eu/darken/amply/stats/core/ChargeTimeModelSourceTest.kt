package eu.darken.amply.stats.core

import android.content.Context
import android.os.BatteryManager
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import eu.darken.amply.battery.core.BatteryReader
import eu.darken.amply.battery.core.BatteryUnitCalibration
import eu.darken.amply.common.AppDataStore
import eu.darken.amply.stats.core.db.BatterySampleEntity
import eu.darken.amply.stats.core.db.ChargeSessionEntity
import eu.darken.amply.stats.core.db.StatsDatabase
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * The shared charge-time fold. The property under test is the refresh trigger: Room invalidates per
 * *table*, so every per-tick write to the open session re-runs the finished-session count query and
 * re-emits an unchanged number. Without the explicit `distinctUntilChanged` the whole ten-session
 * extraction would rerun roughly every 20 seconds for the duration of a charge.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ChargeTimeModelSourceTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var database: StatsDatabase
    private lateinit var repository: ChargeStatsRepository
    private lateinit var dataStoreScope: CoroutineScope

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, StatsDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dataStoreScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val bootIdSource = BootIdSource(context)
        val recorder = ChargeStatsRecorder(
            context = context,
            database = { database },
            preferences = StatsPreferences(
                AppDataStore(
                    PreferenceDataStoreFactory.create(scope = dataStoreScope) {
                        File(tempFolder.root, "stats-${System.nanoTime()}.preferences_pb")
                    },
                ),
            ),
            bootIdSource = bootIdSource,
            batteryReader = BatteryReader(context, BatteryUnitCalibration(context)),
            dispatcher = Dispatchers.IO,
        )
        repository = ChargeStatsRepository(
            database = { database },
            recorder = recorder,
            bootIdSource = bootIdSource,
        )
    }

    @After
    fun teardown() {
        dataStoreScope.cancel()
        database.close()
    }

    private suspend fun insertFinishedSession(startPercent: Int, endPercent: Int): Long {
        val id = database.statsDao().insertSession(
            ChargeSessionEntity(
                startedAtWallMillis = 1_000L,
                startedElapsedRealtimeMillis = 0L,
                endedAtWallMillis = 1_000_000L,
                endedElapsedRealtimeMillis = 1_000_000L,
                bootId = 7,
                startPercent = startPercent,
                endPercent = endPercent,
                pluggedRaw = 1,
                avgPowerMilliwatts = 11_000,
            ),
        )
        (0..(endPercent - startPercent)).forEach { i ->
            database.statsDao().insertSample(
                BatterySampleEntity(
                    sessionId = id,
                    wallMillis = i * 60_000L,
                    elapsedRealtimeMillis = i * 60_000L,
                    bootId = 7,
                    percent = startPercent + i,
                    batteryStatus = BatteryManager.BATTERY_STATUS_CHARGING,
                ),
            )
        }
        return id
    }

    @Test
    fun `nothing is read until the flow is collected`() {
        // Every ViewModel that shows an estimate injects this singleton, so merely constructing it —
        // and touching its flow property — must not create stats.db for a user who never enabled
        // recording. The injected repository throws on any database access at all.
        val context = ApplicationProvider.getApplicationContext<Context>()
        val exploding = dagger.Lazy<StatsDatabase> { error("stats database must not be opened here") }
        val source = ChargeTimeModelSource(
            repository = ChargeStatsRepository(
                database = exploding,
                recorder = ChargeStatsRecorder(
                    context = context,
                    database = exploding,
                    preferences = StatsPreferences(
                        AppDataStore(
                            PreferenceDataStoreFactory.create(scope = dataStoreScope) {
                                File(tempFolder.root, "unused-${System.nanoTime()}.preferences_pb")
                            },
                        ),
                    ),
                    bootIdSource = BootIdSource(context),
                    batteryReader = BatteryReader(context, BatteryUnitCalibration(context)),
                    dispatcher = Dispatchers.IO,
                ),
                bootIdSource = BootIdSource(context),
            ),
            dispatcher = Dispatchers.IO,
        )
        // Reading the property builds the cold flow; the trigger query lives inside it.
        source.states shouldNotBe null
    }

    @Test
    fun `an unchanged session count never re-folds the history`() = runBlocking {
        insertFinishedSession(startPercent = 40, endPercent = 50)
        insertFinishedSession(startPercent = 40, endPercent = 50)
        val source = ChargeTimeModelSource(repository, Dispatchers.IO)

        val emissions = Channel<ChargeTimeModelState>(Channel.UNLIMITED)
        val collector = launch(Dispatchers.IO) { source.states.collect { emissions.send(it) } }
        try {
            val ready = withTimeout(TIMEOUT_MS) {
                var next = emissions.receive()
                while (next !is ChargeTimeModelState.Ready) next = emissions.receive()
                next
            }
            ready.model.pooled.bands[40] shouldBe 60_000L

            // Exactly what a recorder tick does: rewrite the open session row. The count query is
            // invalidated and re-runs, but the number it returns has not changed.
            val open = database.statsDao().insertSession(
                ChargeSessionEntity(
                    startedAtWallMillis = 2_000L,
                    startedElapsedRealtimeMillis = 2_000L,
                    bootId = 7,
                    startPercent = 60,
                ),
            )
            repeat(3) { tick ->
                val row = database.statsDao().sessionById(open)!!
                database.statsDao().updateSession(row.copy(runningSampleCount = tick + 1))
            }

            withTimeoutOrNull(QUIET_MS) { emissions.receive() }.shouldBeNull()
        } finally {
            collector.cancelAndJoin()
        }
    }

    @Test
    fun `a newly finished session does re-fold the history`(): Unit = runBlocking {
        insertFinishedSession(startPercent = 40, endPercent = 50)
        insertFinishedSession(startPercent = 40, endPercent = 50)
        val source = ChargeTimeModelSource(repository, Dispatchers.IO)

        val emissions = Channel<ChargeTimeModelState>(Channel.UNLIMITED)
        val collector = launch(Dispatchers.IO) { source.states.collect { emissions.send(it) } }
        try {
            withTimeout(TIMEOUT_MS) {
                var next = emissions.receive()
                while (next !is ChargeTimeModelState.Ready) next = emissions.receive()
            }
            insertFinishedSession(startPercent = 60, endPercent = 70)

            val refolded = withTimeout(TIMEOUT_MS) {
                var next = emissions.receive()
                while (next !is ChargeTimeModelState.Ready || next.model.observedSessions < 3) {
                    next = emissions.receive()
                }
                next
            }
            (refolded as ChargeTimeModelState.Ready).model.observedSessions shouldBe 3
        } finally {
            collector.cancelAndJoin()
        }
    }

    private companion object {
        const val TIMEOUT_MS = 10_000L
        const val QUIET_MS = 1_000L
    }
}
