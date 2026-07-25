package eu.darken.amply.stats.core

import android.content.Context
import android.content.Intent
import android.os.BatteryManager
import android.provider.Settings
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import eu.darken.amply.battery.core.BatteryReader
import eu.darken.amply.common.AppDataStore
import eu.darken.amply.stats.core.db.ChargeSessionEntity
import eu.darken.amply.stats.core.db.StatsDatabase
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File

/**
 * Startup reconciliation of a session left open by a process death, against an in-memory Room
 * instance: does the recorder reattach the row (keeping the real plug-in time and one history entry),
 * or seal it? The decision itself is unit-tested in [StatsSessionEngineTest]; what is covered here is
 * the orchestration around it — probing live battery state, choosing among multiple open rows, and
 * the capture-disabled short circuit.
 *
 * Robolectric (JUnit 4) per the project's convention for anything needing the Android framework. The
 * recorder's own command loop is injected with a dispatcher, but its database work still crosses
 * Room's executors, so assertions await an observable database state under a generous timeout rather
 * than assuming a synchronous handoff.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ChargeStatsRecorderTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var context: Context
    private lateinit var database: StatsDatabase
    private lateinit var preferences: StatsPreferences
    private lateinit var dataStoreScope: CoroutineScope

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, StatsDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        // Own temp file per test, matching the project's other DataStore tests. A DataStore over the
        // app's real path would be shared with every other Robolectric class in the same JVM.
        dataStoreScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        preferences = StatsPreferences(
            AppDataStore(
                PreferenceDataStoreFactory.create(scope = dataStoreScope) {
                    File(tempFolder.root, "stats-${System.nanoTime()}.preferences_pb")
                },
            ),
        )
    }

    @After
    fun teardown() {
        dataStoreScope.cancel()
        database.close()
    }

    /**
     * Construct the recorder, which runs its startup reconciliation immediately. Everything the
     * reconciliation reads (capture flag, last-capture stamp, boot count, sticky battery state) must
     * already be seeded.
     */
    private fun startRecorder() = ChargeStatsRecorder(
        database = { database },
        preferences = preferences,
        bootIdSource = BootIdSource(context),
        batteryReader = BatteryReader(context),
        dispatcher = Dispatchers.Unconfined,
    )

    private suspend fun enableCapture(enabled: Boolean = true, stamped: Boolean = true) {
        preferences.setCaptureEnabled(enabled)
        // With capture off, the last-capture stamp is what tells the recorder there may be data worth
        // reconciling; without either it refuses to touch the DB at all.
        if (stamped) preferences.setLastCaptureWallMillis(WALL_START)
    }

    private fun setBootCount(count: Int) {
        Settings.Global.putInt(context.contentResolver, Settings.Global.BOOT_COUNT, count)
    }

    private fun setBattery(plugged: Boolean, percent: Int) {
        val intent = Intent(Intent.ACTION_BATTERY_CHANGED).apply {
            putExtra(BatteryManager.EXTRA_PLUGGED, if (plugged) BatteryManager.BATTERY_PLUGGED_AC else 0)
            putExtra(BatteryManager.EXTRA_LEVEL, percent)
            putExtra(BatteryManager.EXTRA_SCALE, 100)
            putExtra(
                BatteryManager.EXTRA_STATUS,
                if (plugged) BatteryManager.BATTERY_STATUS_CHARGING else BatteryManager.BATTERY_STATUS_DISCHARGING,
            )
        }
        @Suppress("DEPRECATION")
        context.sendStickyBroadcast(intent)
    }

    /** Seeds the live current property the reader queries off the intent. */
    private fun setCurrentNowMicroamps(microamps: Int) {
        val manager = context.getSystemService(BatteryManager::class.java)
        shadowOf(manager).setIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW, microamps)
    }

    private suspend fun insertOpenSession(
        bootId: Long = BOOT_ID,
        lastPercent: Int? = 50,
        startWall: Long = WALL_START,
    ): Long = database.statsDao().insertSession(
        ChargeSessionEntity(
            startedAtWallMillis = startWall,
            startedElapsedRealtimeMillis = 0,
            bootId = bootId,
            startPercent = 40,
            runningSampleCount = 1,
            runningLastPercent = lastPercent,
            runningLastElapsedRealtimeMillis = 0,
            runningLastWallMillis = startWall,
            runningLastPowerMilliwatts = 9_000,
        ),
    )

    private suspend fun await(condition: suspend () -> Boolean) {
        withTimeout(AWAIT_TIMEOUT_MILLIS) {
            while (!condition()) delay(20)
        }
    }

    private suspend fun awaitSealed(id: Long) = await { database.statsDao().sessionById(id)?.endedAtWallMillis != null }

    private suspend fun awaitResumed(id: Long) = await { database.statsDao().sessionById(id)?.partial == true }

    @Test
    fun `a restart while still plugged resumes the open session instead of restarting it`(): Unit = runBlocking {
        enableCapture()
        setBootCount(BOOT_ID.toInt())
        setBattery(plugged = true, percent = 55)
        val id = insertOpenSession()

        startRecorder()
        awaitResumed(id)

        val row = database.statsDao().sessionById(id).shouldNotBeNull()
        // The whole point: the card's "Since …" keeps the real plug-in time rather than jumping to
        // the process launch time.
        row.startedAtWallMillis shouldBe WALL_START
        row.startPercent shouldBe 40
        row.endedAtWallMillis shouldBe null
        // Continuity across the gap is inferred, so the row is flagged...
        row.partial shouldBe true
        // ...and the pre-death readings are dropped so the unwitnessed gap is never credited.
        row.runningLastPowerMilliwatts shouldBe null
        // One physical charge stays one history row.
        database.statsDao().openSessions().size shouldBe 1
    }

    @Test
    fun `a restart after the charger was pulled seals the session`(): Unit = runBlocking {
        enableCapture()
        setBootCount(BOOT_ID.toInt())
        setBattery(plugged = false, percent = 55)
        val id = insertOpenSession()

        startRecorder()
        awaitSealed(id)

        database.statsDao().sessionById(id)?.endReason shouldBe StatsSealReason.INTERRUPTED.name
    }

    @Test
    fun `a level that dropped during the gap seals the session`(): Unit = runBlocking {
        enableCapture()
        setBootCount(BOOT_ID.toInt())
        // Plugged again now, but below the last level we recorded — the device discharged in between,
        // so this is a different plug event.
        setBattery(plugged = true, percent = 41)
        val id = insertOpenSession(lastPercent = 50)

        startRecorder()
        awaitSealed(id)
    }

    @Test
    fun `a reboot seals the session rather than splicing two boots' clocks`(): Unit = runBlocking {
        enableCapture()
        setBootCount(9)
        setBattery(plugged = true, percent = 55)
        val id = insertOpenSession(bootId = 8)

        startRecorder()
        awaitSealed(id)

        database.statsDao().sessionById(id)?.endReason shouldBe StatsSealReason.REBOOT.name
    }

    @Test
    fun `an unreported boot count never resumes, because the sentinel equals itself across boots`(): Unit = runBlocking {
        enableCapture()
        // BOOT_COUNT deliberately unset → BootIdSource.UNAVAILABLE on both the row and the probe.
        setBattery(plugged = true, percent = 55)
        val id = insertOpenSession(bootId = BootIdSource.UNAVAILABLE)

        startRecorder()
        awaitSealed(id)
    }

    @Test
    fun `an open row with no last-capture stamp is still reconciled`(): Unit = runBlocking {
        // The stamp is written *after* the session row is committed, so a process death in between
        // leaves an open row with no stamp. Gating reconciliation on the stamp alone would strand that
        // row open forever and let the next tick open a second one alongside it.
        enableCapture(stamped = false)
        setBootCount(BOOT_ID.toInt())
        setBattery(plugged = true, percent = 55)
        val id = insertOpenSession()

        startRecorder()
        awaitResumed(id)

        database.statsDao().openSessions().map { it.id } shouldBe listOf(id)
    }

    @Test
    fun `capture disabled at startup seals without resuming`(): Unit = runBlocking {
        // Reachable for real: the capture preference is written before the recorder's disable command
        // is enqueued, so a crash in between leaves durable-off with a row still open. No tick will
        // ever arrive to advance a resumed row.
        enableCapture(enabled = false)
        setBootCount(BOOT_ID.toInt())
        setBattery(plugged = true, percent = 55)
        val id = insertOpenSession()

        startRecorder()
        awaitSealed(id)
    }

    @Test
    fun `with several open rows only the newest is resumed and the rest are sealed`(): Unit = runBlocking {
        enableCapture()
        setBootCount(BOOT_ID.toInt())
        setBattery(plugged = true, percent = 55)
        val older = insertOpenSession(startWall = WALL_START)
        val newer = insertOpenSession(startWall = WALL_START + 60_000)

        startRecorder()
        awaitResumed(newer)
        awaitSealed(older)

        database.statsDao().openSessions().map { it.id } shouldBe listOf(newer)
    }

    @Test
    fun `the first tick after a resume appends to the same row`(): Unit = runBlocking {
        enableCapture()
        setBootCount(BOOT_ID.toInt())
        setBattery(plugged = true, percent = 55)
        val id = insertOpenSession()

        val recorder = startRecorder()
        awaitResumed(id)

        recorder.offer(
            RawStatsTick(
                plugged = true,
                percent = 56,
                batteryStatus = BatteryManager.BATTERY_STATUS_CHARGING,
                sessionActive = false,
                batteryIntent = null,
                observedElapsedRealtimeMillis = 60_000,
                wallMillis = WALL_START + 60_000,
            ),
        )
        await { database.statsDao().sessionById(id)?.runningLastPercent == 56 }

        // Appended, not reopened: still one session, and it is still the original row.
        database.statsDao().openSessions().map { it.id } shouldBe listOf(id)
        database.statsDao().sessionById(id)?.startedAtWallMillis shouldBe WALL_START
    }

    // The recorder's power gate. Both cases seed real voltage AND current, and assert those raw
    // inputs landed on the sample — so a null power proves the direction gate fired, not that
    // Robolectric had nothing to report.
    @Test
    fun `power is not recorded for a sample that was not charging`(): Unit = runBlocking {
        enableCapture()
        setBootCount(BOOT_ID.toInt())
        setBattery(plugged = true, percent = 55)
        setCurrentNowMicroamps(-2_000_000)
        val id = insertOpenSession()

        val recorder = startRecorder()
        awaitResumed(id)
        // Plugged in but losing charge: the load exceeds what the charger supplies.
        recorder.offer(electricalTick(BatteryManager.BATTERY_STATUS_DISCHARGING))
        await { database.statsDao().samplesForSessionNow(id).any { it.percent == 56 } }

        val sample = database.statsDao().samplesForSessionNow(id).first { it.percent == 56 }
        // The inputs were there…
        sample.voltageMillivolts shouldBe 4_000
        sample.currentNowMicroamps shouldBe -2_000_000
        sample.batteryStatus shouldBe BatteryManager.BATTERY_STATUS_DISCHARGING
        // …so this null is the gate, not a missing reading. 4000 mV x 2 A would have stored 8000 mW.
        sample.powerMilliwatts shouldBe null
    }

    @Test
    fun `power is recorded while charging`(): Unit = runBlocking {
        enableCapture()
        setBootCount(BOOT_ID.toInt())
        setBattery(plugged = true, percent = 55)
        setCurrentNowMicroamps(2_000_000)
        val id = insertOpenSession()

        val recorder = startRecorder()
        awaitResumed(id)
        recorder.offer(electricalTick(BatteryManager.BATTERY_STATUS_CHARGING))
        await { database.statsDao().samplesForSessionNow(id).any { it.percent == 56 } }

        database.statsDao().samplesForSessionNow(id).first { it.percent == 56 }
            .powerMilliwatts shouldBe 8_000
    }

    /**
     * A tick carrying its own battery intent — the recorder reads the electrical values from that
     * intent, not from a second sticky read, so a tick without one has no voltage or current at all.
     */
    private fun electricalTick(batteryStatus: Int) = RawStatsTick(
        plugged = true,
        percent = 56,
        batteryStatus = batteryStatus,
        sessionActive = false,
        batteryIntent = Intent(Intent.ACTION_BATTERY_CHANGED).apply {
            putExtra(BatteryManager.EXTRA_PLUGGED, BatteryManager.BATTERY_PLUGGED_AC)
            putExtra(BatteryManager.EXTRA_LEVEL, 56)
            putExtra(BatteryManager.EXTRA_SCALE, 100)
            putExtra(BatteryManager.EXTRA_STATUS, batteryStatus)
            putExtra(BatteryManager.EXTRA_VOLTAGE, 4_000)
        },
        observedElapsedRealtimeMillis = 60_000,
        wallMillis = WALL_START + 60_000,
    )

    private companion object {
        const val BOOT_ID = 7L
        const val WALL_START = 1_000L
        const val AWAIT_TIMEOUT_MILLIS = 10_000L
    }
}
