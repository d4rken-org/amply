package eu.darken.amply.stats.core

import android.content.Context
import android.os.BatteryManager
import android.provider.Settings
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import eu.darken.amply.battery.core.BatteryReader
import eu.darken.amply.common.AppDataStore
import eu.darken.amply.stats.core.db.BatterySampleEntity
import eu.darken.amply.stats.core.db.ChargeSessionEntity
import eu.darken.amply.stats.core.db.StatsDatabase
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.nulls.shouldNotBeNull
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
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
 * The live-detail read paths against an in-memory Room instance: [ChargeStatsRepository.curveFlow]
 * must reflect newly landed samples (an open session's detail keeps updating), and
 * [ChargeStatsRepository.session] must resolve to null once the row is gone (the detail screen
 * shows its missing notice instead of an eternal spinner).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ChargeStatsRepositoryLiveTest {

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
        // Own temp file per test, matching the project's other DataStore tests. A DataStore over the
        // app's real path would be shared with every other Robolectric class in the same JVM.
        dataStoreScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val bootIdSource = BootIdSource(context)
        val recorder = ChargeStatsRecorder(
            database = { database },
            preferences = StatsPreferences(
                AppDataStore(
                    PreferenceDataStoreFactory.create(scope = dataStoreScope) {
                        File(tempFolder.root, "stats-${System.nanoTime()}.preferences_pb")
                    },
                ),
            ),
            bootIdSource = bootIdSource,
            batteryReader = BatteryReader(context),
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

    private suspend fun insertOpenSession(): Long = database.statsDao().insertSession(
        ChargeSessionEntity(
            startedAtWallMillis = 1_000L,
            startedElapsedRealtimeMillis = 1_000L,
            bootId = 7,
            startPercent = 40,
        ),
    )

    private fun sample(
        sessionId: Long,
        elapsed: Long,
        percent: Int,
        batteryStatus: Int? = BatteryManager.BATTERY_STATUS_CHARGING,
    ) = BatterySampleEntity(
        sessionId = sessionId,
        wallMillis = elapsed,
        elapsedRealtimeMillis = elapsed,
        bootId = 7,
        percent = percent,
        batteryStatus = batteryStatus,
        powerMilliwatts = 9_000,
        temperatureTenthsC = 300,
    )

    @Test
    fun `curve flow updates an active collector as samples land`() = runBlocking {
        val id = insertOpenSession()
        database.statsDao().insertSample(sample(id, elapsed = 1_000L, percent = 40))

        // One continuously active collector: a one-shot snapshot flow would fail this — the second
        // emission must arrive on the SAME subscription after the insert (Room invalidation).
        val emissions = Channel<List<Int?>>(Channel.UNLIMITED)
        val collector = launch(Dispatchers.IO) {
            repository.curveFlow(id).collect { points -> emissions.send(points.map { it.percent }) }
        }
        try {
            withTimeout(10_000L) {
                awaitEmission(emissions, listOf(40))
                database.statsDao().insertSample(sample(id, elapsed = 31_000L, percent = 41))
                awaitEmission(emissions, listOf(40, 41))
            }
        } finally {
            collector.cancelAndJoin()
        }
    }

    @Test
    fun `a curve never plots power from a sample that was not charging`() = runTest {
        // Rows written before the recorder gained its direction gate stored an unsigned magnitude on
        // every sample, charging or not. Reading them back verbatim would draw discharge draw as a
        // charge rate, so the read path filters on the status the row already carries.
        val id = insertOpenSession()
        val dao = database.statsDao()
        dao.insertSample(sample(id, elapsed = 1_000L, percent = 40))
        dao.insertSample(
            sample(id, elapsed = 2_000L, percent = 41, batteryStatus = BatteryManager.BATTERY_STATUS_DISCHARGING),
        )
        dao.insertSample(sample(id, elapsed = 3_000L, percent = 42, batteryStatus = null))

        val curve = repository.curveFlow(id).first()
        curve.map { it.percent } shouldBe listOf(40, 41, 42)
        // Only the charging sample keeps its power; the other two report none rather than a wrong one.
        curve.map { it.powerMilliwatts } shouldBe listOf(9_000, null, null)
    }

    private suspend fun awaitEmission(channel: Channel<List<Int?>>, expected: List<Int?>) {
        while (channel.receive() != expected) {
            // Skip intermediate emissions until the expected curve arrives (withTimeout bounds this).
        }
    }

    @Test
    fun `deleted session resolves to a null summary, not a hang`() = runTest {
        val id = insertOpenSession()
        repository.session(id).first().shouldNotBeNull()

        database.statsDao().deleteSession(id)
        repository.session(id).first().shouldBeNull()
    }

    @Test
    fun `current session flow reports a partial flip on the same row`() = runBlocking {
        // Resuming a session after a process restart flips `partial` in place. Keying the flow on the
        // row id alone would suppress that forever for an already-subscribed collector, because the
        // row is captured inside flatMapLatest — the dashboard would keep rendering the stale copy.
        Settings.Global.putInt(
            ApplicationProvider.getApplicationContext<Context>().contentResolver,
            Settings.Global.BOOT_COUNT,
            7,
        )
        val id = insertOpenSession()
        database.statsDao().insertSample(sample(id, elapsed = 1_000L, percent = 40))

        val emissions = Channel<Boolean?>(Channel.UNLIMITED)
        val collector = launch(Dispatchers.IO) {
            repository.currentSession().collect { emissions.send(it?.partial) }
        }
        try {
            withTimeout(10_000L) {
                while (emissions.receive() != false) {
                    // Await the initial non-partial state before flipping it.
                }
                val row = database.statsDao().sessionById(id)!!
                database.statsDao().updateSession(row.copy(partial = true))
                while (emissions.receive() != true) {
                    // Await the flip on the SAME subscription.
                }
            }
        } finally {
            collector.cancelAndJoin()
        }
    }
}
