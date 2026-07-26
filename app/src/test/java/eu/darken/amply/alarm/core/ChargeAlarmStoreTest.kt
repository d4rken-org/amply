package eu.darken.amply.alarm.core

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import eu.darken.amply.common.AppDataStore
import eu.darken.amply.common.serialization.SerializationModule
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

class ChargeAlarmStoreTest {

    @TempDir
    lateinit var tempDir: File

    private fun appDataStore(scope: kotlinx.coroutines.CoroutineScope) = AppDataStore(
        PreferenceDataStoreFactory.create(scope = scope) {
            File(tempDir, "alarm-${System.nanoTime()}.preferences_pb")
        },
    )

    private fun store(scope: kotlinx.coroutines.CoroutineScope): ChargeAlarmStore =
        ChargeAlarmStore(appDataStore(scope), SerializationModule.json())

    /**
     * Snapping is many-to-one, so two *different* stored records can normalize to the same config.
     * The upstream dedupe compares the raw stored string and cannot see that, so the config flow
     * needs its own guard or the UI gets a pointless re-emission.
     *
     * The off-step record is written by hand because the setter snaps on the way in — only a
     * hand-edited or future-written record can hold an off-tick target.
     */
    @Test
    fun `a raw target that snaps to the current value does not re-emit`() = runBlocking {
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val prefs = appDataStore(scope)
        val store = ChargeAlarmStore(prefs, SerializationModule.json())
        prefs.store.edit { it[stringPreferencesKey("alarm.config.v2")] = """{"enabled":true,"targetPercent":83}""" }

        val seen = CopyOnWriteArrayList<ChargeAlarmConfig>()
        val collector = launch(Dispatchers.IO) { store.config.toList(seen) }
        withTimeout(5_000) { while (seen.isEmpty()) delay(5) }

        // A genuinely different raw record (83 -> 85) that normalizes to the same 85.
        store.setTargetPercent(85)
        delay(200)

        seen.map { it.targetPercent } shouldBe listOf(85)
        collector.cancel()
        scope.cancel()
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
