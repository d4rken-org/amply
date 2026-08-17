package eu.darken.amply.battery.core

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.kotest.matchers.shouldBe
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class BatteryUnitCalibrationTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `an ordinary device is never flagged, so its telemetry is never touched`() {
        BatteryUnitCalibration(context).romMisreportsUnits shouldBe false
    }

    @Test
    fun `MagicOS is recognised by its honor system feature`() {
        shadowOf(context.packageManager).setSystemFeature("com.hihonor.software.features.honor", true)

        BatteryUnitCalibration(context).romMisreportsUnits shouldBe true
    }

    @Test
    fun `any one of the known features is enough`() {
        // A slimmed or renamed component must not break detection, so the list is an OR.
        shadowOf(context.packageManager).setSystemFeature("com.hihonor.system.feature", true)

        BatteryUnitCalibration(context).romMisreportsUnits shouldBe true
    }

    @Test
    fun `a lookalike feature name does not match`() {
        shadowOf(context.packageManager).setSystemFeature("com.hihonor.software.features.oversea", true)

        BatteryUnitCalibration(context).romMisreportsUnits shouldBe false
    }
}
