package eu.darken.amply.battery.core

import android.content.Context
import android.content.Intent
import android.os.BatteryManager
import android.os.PowerManager
import androidx.test.core.app.ApplicationProvider
import eu.darken.amply.charging.core.enforcement.BuildIdentitySource
import eu.darken.amply.common.AppDataStore
import eu.darken.amply.common.serialization.SerializationModule
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowBuild
import org.robolectric.shadows.ShadowSystemClock
import java.time.Duration
import javax.inject.Provider

@RunWith(RobolectricTestRunner::class)
class BatteryReaderCurrentUnitTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val scheduler = TestCoroutineScheduler()
    private val identity = object : BuildIdentitySource {
        override fun current() = "build-a"
    }
    private val unitStore = BatteryUnitStore(
        AppDataStore(InMemoryPreferencesStore()),
        identity,
        SerializationModule.json(),
    )

    @Before
    fun setup() {
        ShadowBuild.setManufacturer("samsung")
        shadowOf(context.getSystemService(PowerManager::class.java)).setIsInteractive(true)
    }

    private fun reader() = BatteryReader(
        context,
        BatteryUnitCalibration(context, Provider { unitStore }, StandardTestDispatcher(scheduler)),
    )

    private fun setProperty(property: Int, value: Int) {
        shadowOf(context.getSystemService(BatteryManager::class.java)).setIntProperty(property, value)
    }

    private fun battery(plugged: Int) = Intent(Intent.ACTION_BATTERY_CHANGED).apply {
        putExtra(BatteryManager.EXTRA_PLUGGED, plugged)
        putExtra(BatteryManager.EXTRA_LEVEL, 60)
        putExtra(BatteryManager.EXTRA_SCALE, 100)
    }

    @Test
    fun `a Samsung current is withheld until the learned unit is loaded`() {
        setProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW, -254)
        val reader = reader()

        reader.read(battery(plugged = 0)).currentNowMicroamps shouldBe null

        scheduler.advanceUntilIdle()
        reader.read(battery(plugged = 0)).currentNowMicroamps shouldBe -254
    }

    @Test
    fun `a stored milliamp unit rescales the current and leaves the charge counter alone`() {
        runBlocking { unitStore.learn(CurrentUnit.MILLI) }
        setProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW, -254)
        setProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER, 2_700_000)
        val reader = reader()
        scheduler.advanceUntilIdle()

        val readout = reader.read(battery(plugged = BatteryManager.BATTERY_PLUGGED_AC))
        readout.currentNowMicroamps shouldBe -254_000
        readout.chargeCounterMicroampHours shouldBe 2_700_000
    }

    @Test
    fun `readings through the reader learn milliamps when unplugged with the screen on`() {
        setProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW, -254)
        val reader = reader()
        scheduler.advanceUntilIdle()

        reader.read(battery(plugged = 0)).currentNowMicroamps shouldBe -254
        ShadowSystemClock.advanceBy(Duration.ofSeconds(30))
        reader.read(battery(plugged = 0)).currentNowMicroamps shouldBe -254
        ShadowSystemClock.advanceBy(Duration.ofSeconds(30))
        reader.read(battery(plugged = 0)).currentNowMicroamps shouldBe -254_000

        scheduler.advanceUntilIdle()
        runBlocking { unitStore.read() } shouldBe CurrentUnit.MILLI
    }

    @Test
    fun `plugged readings never learn milliamps`() {
        setProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW, -254)
        val reader = reader()
        scheduler.advanceUntilIdle()

        repeat(4) {
            reader.read(battery(plugged = BatteryManager.BATTERY_PLUGGED_AC)).currentNowMicroamps shouldBe -254
            ShadowSystemClock.advanceBy(Duration.ofSeconds(30))
        }
    }

    @Test
    fun `a screen-off device never learns milliamps`() {
        shadowOf(context.getSystemService(PowerManager::class.java)).setIsInteractive(false)
        setProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW, -254)
        val reader = reader()
        scheduler.advanceUntilIdle()

        repeat(4) {
            reader.read(battery(plugged = 0)).currentNowMicroamps shouldBe -254
            ShadowSystemClock.advanceBy(Duration.ofSeconds(30))
        }
    }

    @Test
    fun `an absent current stays absent once the unit is loaded`() {
        runBlocking { unitStore.learn(CurrentUnit.MILLI) }
        val reader = reader()
        scheduler.advanceUntilIdle()

        reader.read(battery(plugged = 0)).currentNowMicroamps shouldBe null
    }
}
