package eu.darken.amply.battery.core

import android.content.Context
import android.os.BatteryManager
import androidx.test.core.app.ApplicationProvider
import eu.darken.amply.charging.core.enforcement.BuildIdentitySource
import eu.darken.amply.common.AppDataStore
import eu.darken.amply.common.serialization.SerializationModule
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowBuild
import javax.inject.Provider

@RunWith(RobolectricTestRunner::class)
class SamsungCurrentUnitCalibrationTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val scheduler = TestCoroutineScheduler()
    private val backing = InMemoryPreferencesStore()
    private val identity = object : BuildIdentitySource {
        override fun current() = "build-a"
    }
    private val unitStore = BatteryUnitStore(AppDataStore(backing), identity, SerializationModule.json())
    private var storeAccesses = 0

    private fun calibration() = BatteryUnitCalibration(
        context = context,
        unitStore = Provider { storeAccesses++; unitStore },
        dispatcher = StandardTestDispatcher(scheduler),
    )

    private fun stored(): CurrentUnit = runBlocking { unitStore.read() }

    private fun store(unit: CurrentUnit) {
        runBlocking { unitStore.learn(unit) }
    }

    private fun BatteryUnitCalibration.unpluggedScreenOn(raw: Int, atMillis: Long) =
        observeCurrent(rawCurrent = raw, plugged = 0, interactive = true, nowElapsedMillis = atMillis)

    private fun BatteryUnitCalibration.charging(raw: Int, atMillis: Long) = observeCurrent(
        rawCurrent = raw,
        plugged = BatteryManager.BATTERY_PLUGGED_AC,
        interactive = false,
        nowElapsedMillis = atMillis,
    )

    /** Three small unplugged, screen-on readings spanning a minute: the milliamp evidence. */
    private fun BatteryUnitCalibration.milliSequence(startMillis: Long = 0L): List<CurrentScale> = listOf(
        unpluggedScreenOn(-254, startMillis),
        unpluggedScreenOn(-313, startMillis + 30_000L),
        unpluggedScreenOn(-438, startMillis + 60_000L),
    )

    @Test
    fun `a non-Samsung device never touches the store and always reads as reported`() {
        ShadowBuild.setManufacturer("Google")
        val calibration = calibration()
        scheduler.advanceUntilIdle()

        calibration.milliSequence() shouldBe List(3) { CurrentScale.AS_REPORTED }
        calibration.unpluggedScreenOn(-500, 90_000L) shouldBe CurrentScale.AS_REPORTED
        calibration.charging(1_500_000, 120_000L) shouldBe CurrentScale.AS_REPORTED
        scheduler.advanceUntilIdle()

        storeAccesses shouldBe 0
        backing.writeAttempts shouldBe 0
    }

    @Test
    fun `Samsung is not ready until the stored unit is loaded`() {
        ShadowBuild.setManufacturer("samsung")
        val calibration = calibration()

        calibration.charging(-254, 0L) shouldBe CurrentScale.NOT_READY

        scheduler.advanceUntilIdle()
        calibration.charging(-254, 30_000L) shouldBe CurrentScale.AS_REPORTED
    }

    @Test
    fun `Samsung matches the manufacturer case-insensitively`() {
        ShadowBuild.setManufacturer("SAMSUNG")
        calibration().charging(-254, 0L) shouldBe CurrentScale.NOT_READY
    }

    @Test
    fun `Samsung learns and persists milliamps from a sustained unplugged screen-on streak`() {
        ShadowBuild.setManufacturer("samsung")
        val calibration = calibration()
        scheduler.advanceUntilIdle()

        calibration.milliSequence() shouldBe listOf(
            CurrentScale.AS_REPORTED,
            CurrentScale.AS_REPORTED,
            CurrentScale.MILLI_SCALED,
        )
        scheduler.advanceUntilIdle()

        stored() shouldBe CurrentUnit.MILLI
        // Plugged readings keep the learned unit.
        calibration.charging(-40, 90_000L) shouldBe CurrentScale.MILLI_SCALED
    }

    @Test
    fun `a stored milliamp unit applies from the first reading after loading`() {
        ShadowBuild.setManufacturer("samsung")
        store(CurrentUnit.MILLI)
        val calibration = calibration()
        scheduler.advanceUntilIdle()

        calibration.charging(-300, 0L) shouldBe CurrentScale.MILLI_SCALED
        scheduler.advanceUntilIdle()
        backing.writeAttempts shouldBe 1
    }

    @Test
    fun `a stored microamp unit wins over milliamps learned before loading`() {
        ShadowBuild.setManufacturer("samsung")
        store(CurrentUnit.MICRO)
        val calibration = calibration()

        calibration.milliSequence() shouldBe List(3) { CurrentScale.NOT_READY }
        scheduler.advanceUntilIdle()

        calibration.charging(-40, 90_000L) shouldBe CurrentScale.AS_REPORTED
        scheduler.advanceUntilIdle()
        stored() shouldBe CurrentUnit.MICRO
    }

    @Test
    fun `a stored microamp unit returned by a write wins over milliamps learned in memory`() {
        ShadowBuild.setManufacturer("samsung")
        val calibration = calibration()
        scheduler.advanceUntilIdle()
        // Landed after loading, so only the write's merged result can carry it back.
        store(CurrentUnit.MICRO)

        calibration.milliSequence().last() shouldBe CurrentScale.MILLI_SCALED
        scheduler.advanceUntilIdle()

        calibration.charging(-40, 90_000L) shouldBe CurrentScale.AS_REPORTED
        stored() shouldBe CurrentUnit.MICRO
    }

    @Test
    fun `a failed write is retried by a later reading`() {
        ShadowBuild.setManufacturer("samsung")
        backing.failNextWrites = 1
        val calibration = calibration()
        scheduler.advanceUntilIdle()

        calibration.milliSequence().last() shouldBe CurrentScale.MILLI_SCALED
        scheduler.advanceUntilIdle()
        backing.writeAttempts shouldBe 1
        stored() shouldBe CurrentUnit.UNKNOWN

        calibration.charging(-40, 90_000L) shouldBe CurrentScale.MILLI_SCALED
        scheduler.advanceUntilIdle()
        backing.writeAttempts shouldBe 2
        stored() shouldBe CurrentUnit.MILLI

        // Persisted now, so no further writes.
        calibration.charging(-40, 120_000L) shouldBe CurrentScale.MILLI_SCALED
        scheduler.advanceUntilIdle()
        backing.writeAttempts shouldBe 2
    }

    @Test
    fun `an unreadable store still completes loading, so readings are not withheld`() {
        ShadowBuild.setManufacturer("samsung")
        backing.failReads = true
        val calibration = calibration()
        scheduler.advanceUntilIdle()

        calibration.charging(-254, 0L) shouldBe CurrentScale.AS_REPORTED
    }

    @Test
    fun `an undecided unit is never written`() {
        ShadowBuild.setManufacturer("samsung")
        val calibration = calibration()
        scheduler.advanceUntilIdle()

        calibration.charging(-254, 0L) shouldBe CurrentScale.AS_REPORTED
        calibration.unpluggedScreenOn(-254, 30_000L) shouldBe CurrentScale.AS_REPORTED
        scheduler.advanceUntilIdle()

        backing.writeAttempts shouldBe 0
    }
}
