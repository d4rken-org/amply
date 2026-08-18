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
import kotlinx.coroutines.delay
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
 * *table*, so every per-tick write to the open session re-runs the finished-session query and
 * re-emits an unchanged list. Without the explicit `distinctUntilChanged` the whole ten-session
 * extraction would rerun roughly every 20 seconds for the duration of a charge. The second property
 * is what a returning subscriber sees: a replayed `Ready` must not be followed by a fresh `Loading`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ChargeTimeModelSourceTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var database: StatsDatabase
    private lateinit var repository: ChargeStatsRepository
    private lateinit var preferences: StatsPreferences
    private lateinit var dataStoreScope: CoroutineScope

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, StatsDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dataStoreScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val bootIdSource = BootIdSource(context)
        preferences = StatsPreferences(
            AppDataStore(
                PreferenceDataStoreFactory.create(scope = dataStoreScope) {
                    File(tempFolder.root, "stats-${System.nanoTime()}.preferences_pb")
                },
            ),
        )
        val recorder = ChargeStatsRecorder(
            context = context,
            database = { database },
            preferences = preferences,
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

    /**
     * Wall stamps are anchored to *now*, not to the epoch: the fold applies the retention window to
     * the samples, so epoch-relative stamps would all fall outside it and the model would be empty.
     */
    private suspend fun insertFinishedSession(startPercent: Int, endPercent: Int): Long {
        val nowWallMillis = System.currentTimeMillis()
        val id = database.statsDao().insertSession(
            ChargeSessionEntity(
                startedAtWallMillis = nowWallMillis,
                startedElapsedRealtimeMillis = 0L,
                endedAtWallMillis = nowWallMillis + 1_000_000L,
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
                    wallMillis = nowWallMillis + i * 60_000L,
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
            statsPreferences = preferences,
            dispatcher = Dispatchers.IO,
        )
        // Reading the property builds the cold flow; the trigger query lives inside it.
        source.states shouldNotBe null
    }

    @Test
    fun `an open session's ticks never re-fold the history`() = runBlocking {
        insertFinishedSession(startPercent = 40, endPercent = 50)
        insertFinishedSession(startPercent = 40, endPercent = 50)
        val source = ChargeTimeModelSource(repository, preferences, Dispatchers.IO)

        val emissions = Channel<ChargeTimeModelState>(Channel.UNLIMITED)
        val collector = launch(Dispatchers.IO) { source.states.collect { emissions.send(it) } }
        try {
            val ready = withTimeout(TIMEOUT_MS) {
                var next = emissions.receive()
                while (next !is ChargeTimeModelState.Ready) next = emissions.receive()
                next
            }
            ready.model.pooled.bands[40]!!.medianMillisPerPercent shouldBe 60_000L

            // Exactly what a recorder tick does: rewrite the open session row. The trigger query is
            // invalidated and re-runs, but an open session is not in the finished list it returns.
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
        val source = ChargeTimeModelSource(repository, preferences, Dispatchers.IO)

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

    @Test
    fun `a resubscription after the stop timeout never shows Loading between two Ready values`(): Unit =
        runBlocking {
            // `onStart` upstream of `shareIn` re-runs whenever the upstream restarts, so a returning
            // subscriber would get the replayed Ready followed by a fresh Loading — the charge-time
            // card blinking back to its loading line every time the app is reopened.
            insertFinishedSession(startPercent = 40, endPercent = 50)
            insertFinishedSession(startPercent = 40, endPercent = 50)
            val source = ChargeTimeModelSource(repository, preferences, Dispatchers.IO)

            val first = Channel<ChargeTimeModelState>(Channel.UNLIMITED)
            val warmUp = launch(Dispatchers.IO) { source.states.collect { first.send(it) } }
            withTimeout(TIMEOUT_MS) {
                var next = first.receive()
                while (next !is ChargeTimeModelState.Ready) next = first.receive()
            }
            warmUp.cancelAndJoin()

            // Past the stop timeout the upstream is cancelled while the replay cache keeps its Ready.
            delay(STOP_TIMEOUT_MS + 1_000L)

            val second = Channel<ChargeTimeModelState>(Channel.UNLIMITED)
            val collector = launch(Dispatchers.IO) { source.states.collect { second.send(it) } }
            try {
                val seen = withTimeout(TIMEOUT_MS) { List(2) { second.receive() } }
                seen.filterIsInstance<ChargeTimeModelState.Loading>() shouldBe emptyList()
                seen.all { it is ChargeTimeModelState.Ready } shouldBe true
            } finally {
                collector.cancelAndJoin()
            }
        }

    private companion object {
        const val TIMEOUT_MS = 10_000L
        const val QUIET_MS = 1_000L

        /** Mirrors `ChargeTimeModelSource.STOP_TIMEOUT_MILLIS`, which is private. */
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
