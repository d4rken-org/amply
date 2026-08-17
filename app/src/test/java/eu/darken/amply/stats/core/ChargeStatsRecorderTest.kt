package eu.darken.amply.stats.core

import android.content.Context
import android.content.Intent
import android.os.BatteryManager
import android.provider.Settings
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import eu.darken.amply.battery.core.BatteryReader
import eu.darken.amply.battery.core.BatteryUnitCalibration
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
 * Recorder-level orchestration against an in-memory Room instance: startup reconciliation of a
 * session left open by a process death (reattach the row, keeping the real plug-in time and one
 * history entry, or seal it?) and the retention purges layered on top of it. The individual decisions
 * are unit-tested in [StatsSessionEngineTest] / [StatsRetentionTest]; what is covered here is the
 * orchestration — probing live battery state, choosing among multiple open rows, the capture-disabled
 * short circuit, and *when* a purge actually runs.
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
    private var databaseAccessCount = 0

    /**
     * Retention runs against real wall time (the recorder reads `System.currentTimeMillis()`), so the
     * fixtures are anchored to *now* rather than to a small literal: a session sealed at epoch+1s would
     * sit outside every retention window and be purged by the very startup pass that sealed it.
     */
    private val now = System.currentTimeMillis()
    private val wallStart = now - HOUR

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, StatsDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        databaseAccessCount = 0
        // Room is in-memory here, so the recorder's existence guard must see no file unless a test
        // deliberately creates one — a leftover from another test would silently defeat it.
        context.getDatabasePath(StatsDatabase.NAME).delete()
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
     * Construct the recorder, which runs its startup repair immediately. Everything that repair reads
     * (capture flag, retention window, boot count, sticky battery state) must already be seeded. The
     * database is handed over through a counting provider so a test can assert Room was never opened.
     */
    private fun startRecorder() = ChargeStatsRecorder(
        context = context,
        database = { databaseAccessCount++; database },
        preferences = preferences,
        bootIdSource = BootIdSource(context),
        batteryReader = BatteryReader(context, BatteryUnitCalibration(context)),
        dispatcher = Dispatchers.Unconfined,
    )

    private suspend fun enableCapture(enabled: Boolean = true) {
        preferences.setCaptureEnabled(enabled)
    }

    /**
     * Materialize the on-disk `stats.db` the recorder's existence guard checks. Room is in-memory
     * here, so without this a capture-off recorder correctly refuses to touch the database at all.
     */
    private fun createStatsDatabaseFile() {
        val file = context.getDatabasePath(StatsDatabase.NAME)
        file.parentFile?.mkdirs()
        file.createNewFile()
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
        startWall: Long = wallStart,
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

    /** A finished entry, for the retention cases. Elapsed stamps are irrelevant to a wall-time purge. */
    private suspend fun insertClosedSession(endWall: Long, startWall: Long = endWall - 60_000): Long {
        val id = insertOpenSession(startWall = startWall)
        val row = database.statsDao().sessionById(id)!!
        database.statsDao().updateSession(
            row.copy(endedAtWallMillis = endWall, endedElapsedRealtimeMillis = endWall),
        )
        return id
    }

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
        row.startedAtWallMillis shouldBe wallStart
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
    fun `an open row is reconciled even with no stats db file on disk`(): Unit = runBlocking {
        // Capture being enabled is sufficient on its own: a recorder that refused to look until it saw
        // a database file would strand this row open forever and let the next tick open a second one
        // alongside it. (Room is in-memory here, so no file exists.)
        enableCapture()
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
        createStatsDatabaseFile()
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
        val older = insertOpenSession(startWall = wallStart)
        val newer = insertOpenSession(startWall = wallStart + 60_000)

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
                wallMillis = wallStart + 60_000,
            ),
        )
        await { database.statsDao().sessionById(id)?.runningLastPercent == 56 }

        // Appended, not reopened: still one session, and it is still the original row.
        database.statsDao().openSessions().map { it.id } shouldBe listOf(id)
        database.statsDao().sessionById(id)?.startedAtWallMillis shouldBe wallStart
    }

    @Test
    fun `startup purges expired entries with no dangling open row to reconcile`(): Unit = runBlocking {
        // The regression this pins: with the purge nested inside reconciliation, a clean shutdown (no
        // open rows) meant no purge ran at all — retention only ever applied after a crash.
        preferences.setRetentionDays(3)
        enableCapture()
        setBootCount(BOOT_ID.toInt())
        setBattery(plugged = false, percent = 55)
        val expired = insertClosedSession(endWall = now - 10 * DAY)

        startRecorder()

        await { database.statsDao().sessionById(expired) == null }
    }

    @Test
    fun `purgeNow applies the stored retention window, not the default`(): Unit = runBlocking {
        preferences.setRetentionDays(3)
        enableCapture()
        setBootCount(BOOT_ID.toInt())
        setBattery(plugged = false, percent = 55)

        val recorder = startRecorder()
        // 5 days old: inside the 14-day default, outside the 3 days actually configured.
        val expired = insertClosedSession(endWall = now - 5 * DAY)
        val kept = insertClosedSession(endWall = now - DAY)
        recorder.purgeNow()

        await { database.statsDao().sessionById(expired) == null }
        database.statsDao().sessionById(kept).shouldNotBeNull()
    }

    @Test
    fun `an open row survives a purge and expires only once a seal makes it eligible`(): Unit = runBlocking {
        preferences.setRetentionDays(3)
        enableCapture()
        createStatsDatabaseFile()
        setBootCount(BOOT_ID.toInt())
        setBattery(plugged = true, percent = 55)
        val id = insertOpenSession(startWall = now - 10 * DAY)

        val recorder = startRecorder()
        awaitResumed(id)

        // Commands are FIFO on one loop, so the seal below is strictly after this purge: had the purge
        // taken the still-open row, there would be nothing left to seal and the await would time out.
        recorder.purgeNow()
        recorder.setEnabled(false)
        awaitSealed(id)

        recorder.purgeNow()
        await { database.statsDao().sessionById(id) == null }
    }

    @Test
    fun `recorded data is purged even with capture switched off`(): Unit = runBlocking {
        // The case the old last-capture stamp could miss: capture is off, but a database file (and rows)
        // are on disk, so retention still has work to do.
        preferences.setRetentionDays(3)
        enableCapture(enabled = false)
        createStatsDatabaseFile()
        setBootCount(BOOT_ID.toInt())
        setBattery(plugged = false, percent = 55)
        val expired = insertClosedSession(endWall = now - 10 * DAY)

        startRecorder()

        await { database.statsDao().sessionById(expired) == null }
    }

    @Test
    fun `a purge on a never-enabled recorder never opens the database`(): Unit = runBlocking {
        // Dragging the retention slider must not be what creates stats.db for a user who never recorded
        // anything. No stats.db file exists here (Room is in-memory) and capture was never enabled.
        enableCapture(enabled = false)
        setBootCount(BOOT_ID.toInt())
        setBattery(plugged = false, percent = 55)

        val recorder = startRecorder()
        recorder.purgeNow()
        // A negative assertion has nothing to await, so give the command loop a generous window in
        // which to misbehave.
        delay(500)

        databaseAccessCount shouldBe 0
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
        wallMillis = wallStart + 60_000,
    )

    private companion object {
        const val BOOT_ID = 7L
        const val AWAIT_TIMEOUT_MILLIS = 10_000L
        const val HOUR = 60L * 60 * 1000
        const val DAY = 24 * HOUR
    }
}
