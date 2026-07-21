package eu.darken.amply.alarm.core

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import eu.darken.amply.common.AppDataStore
import eu.darken.amply.monitor.core.ChargeMonitorTick
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class ChargeAlarmWatcherTest {

    @TempDir
    lateinit var tempDir: File

    private class RecordingNotifier : ChargeAlarmNotifier {
        var shows = 0
        var cancels = 0
        var lastPercent: Int? = null
        var throwOnShow = false
        var deliverable = true

        override fun canDeliver(): Boolean = deliverable

        override fun show(percent: Int) {
            shows++
            lastPercent = percent
            if (throwOnShow) throw RuntimeException("notifier boom")
        }

        override fun cancel() {
            cancels++
        }
    }

    private fun store(scope: CoroutineScope): ChargeAlarmStore {
        val prefs: DataStore<Preferences> = PreferenceDataStoreFactory.create(scope = scope) {
            File(tempDir, "alarm-${System.nanoTime()}.preferences_pb")
        }
        return ChargeAlarmStore(AppDataStore(prefs))
    }

    private fun tick(plugged: Boolean, percent: Int, sessionActive: Boolean = false) =
        ChargeMonitorTick(plugged = plugged, percent = percent, batteryStatus = 0, sessionActive = sessionActive)

    @Test
    fun `disabled watcher does nothing`() = runTest {
        val store = store(this)
        val notifier = RecordingNotifier()
        val watcher = ChargeAlarmWatcher(store, notifier)

        watcher.onBatteryTick(tick(plugged = true, percent = 95))

        notifier.shows shouldBe 0
        store.firedCycle() shouldBe false
    }

    @Test
    fun `reaching target fires once with the level, not again this cycle`() = runTest {
        val store = store(this)
        store.setEnabled(true)
        val notifier = RecordingNotifier()
        val watcher = ChargeAlarmWatcher(store, notifier)

        watcher.onBatteryTick(tick(plugged = true, percent = 80))
        watcher.onBatteryTick(tick(plugged = true, percent = 85))

        notifier.shows shouldBe 1
        notifier.lastPercent shouldBe 80
        store.firedCycle() shouldBe true
    }

    @Test
    fun `unplug after firing cancels and re-arms`() = runTest {
        val store = store(this)
        store.setEnabled(true)
        val notifier = RecordingNotifier()
        val watcher = ChargeAlarmWatcher(store, notifier)

        watcher.onBatteryTick(tick(plugged = true, percent = 90))
        watcher.onBatteryTick(tick(plugged = false, percent = 90))

        notifier.cancels shouldBe 1
        store.firedCycle() shouldBe false
    }

    @Test
    fun `active session suppresses without notifying`() = runTest {
        val store = store(this)
        store.setEnabled(true)
        val notifier = RecordingNotifier()
        val watcher = ChargeAlarmWatcher(store, notifier)

        watcher.onBatteryTick(tick(plugged = true, percent = 40, sessionActive = true))

        notifier.shows shouldBe 0
        store.firedCycle() shouldBe true
    }

    @Test
    fun `an undeliverable alert does not consume the cycle`() = runTest {
        val store = store(this)
        store.setEnabled(true)
        val notifier = RecordingNotifier().apply { deliverable = false }
        val watcher = ChargeAlarmWatcher(store, notifier)

        watcher.onBatteryTick(tick(plugged = true, percent = 90))

        // Nothing shown and the latch stays clear, so re-enabling delivery later still fires.
        notifier.shows shouldBe 0
        store.firedCycle() shouldBe false
    }

    @Test
    fun `a throwing notifier does not roll back the latch`() = runTest {
        val store = store(this)
        store.setEnabled(true)
        val notifier = RecordingNotifier().apply { throwOnShow = true }
        val watcher = ChargeAlarmWatcher(store, notifier)

        runCatching { watcher.onBatteryTick(tick(plugged = true, percent = 95)) }

        // The latch stays true only because it was persisted BEFORE the (throwing) notify — proving
        // persist-before-notify ordering, so a failed alert can never cause a re-fire.
        store.firedCycle() shouldBe true
    }
}
