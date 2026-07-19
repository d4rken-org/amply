package eu.darken.amply.charging.core.adapter

import android.content.Context
import android.content.Intent
import android.os.BatteryManager
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import eu.darken.amply.charging.core.BackendKind
import eu.darken.amply.charging.core.ChargeObservation
import eu.darken.amply.charging.core.ChargePolicy
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PixelChargingAdapterHardwareTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val adapter = PixelChargingAdapter()

    private fun seedBattery(plugged: Int, chargingStatus: Int) {
        context.sendStickyBroadcast(
            Intent(Intent.ACTION_BATTERY_CHANGED)
                .putExtra(BatteryManager.EXTRA_PLUGGED, plugged)
                .putExtra(BatteryManager.EXTRA_CHARGING_STATUS, chargingStatus),
        )
    }

    @Test
    fun `plugged long life broadcast verifies fixed 80`() {
        seedBattery(plugged = BatteryManager.BATTERY_PLUGGED_USB, chargingStatus = 4)

        assertThat(adapter.readHardware(context)).isEqualTo(
            ChargeObservation.Verified(ChargePolicy.FixedLimit(80), BackendKind.BATTERY_HARDWARE),
        )
    }

    @Test
    fun `unplugged long life broadcast is not verified`() {
        seedBattery(plugged = 0, chargingStatus = 4)

        assertThat(adapter.readHardware(context)).isNull()
    }

    @Test
    fun `unplugged adaptive broadcast is not verified`() {
        seedBattery(plugged = 0, chargingStatus = 5)

        assertThat(adapter.readHardware(context)).isNull()
    }

    @Test
    fun `plugged normal broadcast stays unknown`() {
        seedBattery(plugged = BatteryManager.BATTERY_PLUGGED_AC, chargingStatus = 1)

        assertThat(adapter.readHardware(context)).isInstanceOf(ChargeObservation.Unknown::class.java)
    }
}
