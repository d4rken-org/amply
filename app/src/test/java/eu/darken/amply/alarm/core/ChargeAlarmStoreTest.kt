package eu.darken.amply.alarm.core

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import eu.darken.amply.common.AppDataStore
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class ChargeAlarmStoreTest {

    @TempDir
    lateinit var tempDir: File

    private fun store(scope: kotlinx.coroutines.CoroutineScope): ChargeAlarmStore {
        val prefs: DataStore<Preferences> = PreferenceDataStoreFactory.create(scope = scope) {
            File(tempDir, "alarm-${System.nanoTime()}.preferences_pb")
        }
        return ChargeAlarmStore(AppDataStore(prefs))
    }

    @Test
    fun `defaults are disabled at 80 percent`() = runTest {
        val config = store(this).configNow()
        config.enabled shouldBe false
        config.targetPercent shouldBe 80
    }

    @Test
    fun `enabled and target round-trip`() = runTest {
        val store = store(this)
        store.setEnabled(true)
        store.setTargetPercent(90)
        val config = store.config.first()
        config.enabled shouldBe true
        config.targetPercent shouldBe 90
    }

    @Test
    fun `target snaps to the nearest step of five and clamps`() = runTest {
        val store = store(this)
        store.setTargetPercent(83)
        store.configNow().targetPercent shouldBe 85
        store.setTargetPercent(10)
        store.configNow().targetPercent shouldBe 50
        store.setTargetPercent(999)
        store.configNow().targetPercent shouldBe 100
    }

    @Test
    fun `fired-cycle latch round-trips`() = runTest {
        val store = store(this)
        store.firedCycle() shouldBe false
        store.setFiredCycle(true)
        store.firedCycle() shouldBe true
        store.setFiredCycle(false)
        store.firedCycle() shouldBe false
    }
}

class NormalizeTargetTest {
    @Test
    fun `snapping and clamping`() {
        normalizeTarget(52) shouldBe 50
        normalizeTarget(53) shouldBe 55
        normalizeTarget(87) shouldBe 85
        normalizeTarget(88) shouldBe 90
        normalizeTarget(-5) shouldBe 50
        normalizeTarget(140) shouldBe 100
    }
}
