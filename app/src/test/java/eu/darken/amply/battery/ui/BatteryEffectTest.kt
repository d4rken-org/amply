package eu.darken.amply.battery.ui

import android.os.BatteryManager
import eu.darken.amply.battery.core.BatteryReadout
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class BatteryEffectTest {

    private fun readout(
        level: Int? = 82,
        status: Int? = BatteryManager.BATTERY_STATUS_CHARGING,
        plugged: Int? = BatteryManager.BATTERY_PLUGGED_AC,
        voltage: Int? = 4_100,
        current: Int? = 2_000_000,
    ) = BatteryReadout(
        levelPercent = level,
        status = status,
        plugged = plugged,
        voltageMillivolts = voltage,
        currentNowMicroamps = current,
    )

    @Test
    fun `plugged and charging is charging`() {
        BatteryEffect.from(readout()) shouldBe BatteryEffect.Charging(82)
    }

    @Test
    fun `plugged and not charging never claims a policy caused it`() {
        BatteryEffect.from(readout(status = BatteryManager.BATTERY_STATUS_NOT_CHARGING)) shouldBe
            BatteryEffect.ConnectedNotCharging(82)
    }

    @Test
    fun `plugged and discharging is still only connected-not-charging`() {
        BatteryEffect.from(readout(status = BatteryManager.BATTERY_STATUS_DISCHARGING)) shouldBe
            BatteryEffect.ConnectedNotCharging(82)
    }

    // "Connected" and "not charging" are two separate observations. A device that reports a plug but
    // no usable status has told us the first only, so claiming the second would invent it.
    @Test
    fun `plugged with an unknown status claims the connection only`() {
        BatteryEffect.from(readout(status = BatteryManager.BATTERY_STATUS_UNKNOWN)) shouldBe
            BatteryEffect.Connected(82)
    }

    @Test
    fun `plugged with an absent status claims the connection only`() {
        BatteryEffect.from(readout(status = null)) shouldBe BatteryEffect.Connected(82)
    }

    @Test
    fun `a future status constant claims the connection only`() {
        BatteryEffect.from(readout(status = 99)) shouldBe BatteryEffect.Connected(82)
    }

    @Test
    fun `full reports the observed level, not a hardcoded 100`() {
        BatteryEffect.from(readout(level = 80, status = BatteryManager.BATTERY_STATUS_FULL)) shouldBe
            BatteryEffect.Full(80)
    }

    @Test
    fun `full with an unreported level stays null rather than inventing 100`() {
        BatteryEffect.from(readout(level = null, status = BatteryManager.BATTERY_STATUS_FULL)) shouldBe
            BatteryEffect.Full(null)
    }

    @Test
    fun `plugged reported as zero is on battery`() {
        BatteryEffect.from(readout(plugged = 0, status = BatteryManager.BATTERY_STATUS_DISCHARGING)) shouldBe
            BatteryEffect.OnBattery(82)
    }

    @Test
    fun `an unreported plug state claims neither connected nor on battery`() {
        BatteryEffect.from(readout(plugged = null)) shouldBe BatteryEffect.Unknown
    }

    @Test
    fun `an absent readout is unknown`() {
        BatteryEffect.from(null) shouldBe BatteryEffect.Unknown
    }

    @Test
    fun `charge power is shown while charging`() {
        chargePowerMilliwatts(readout()) shouldBe 8_200
    }

    @Test
    fun `charge power is withheld while discharging so draw is never labelled charge power`() {
        chargePowerMilliwatts(readout(plugged = 0, status = BatteryManager.BATTERY_STATUS_DISCHARGING)) shouldBe null
    }

    @Test
    fun `charge power is withheld at a protection hold`() {
        chargePowerMilliwatts(readout(status = BatteryManager.BATTERY_STATUS_NOT_CHARGING)) shouldBe null
    }

    @Test
    fun `charge power is withheld at full`() {
        chargePowerMilliwatts(readout(status = BatteryManager.BATTERY_STATUS_FULL)) shouldBe null
    }

    @Test
    fun `charge power is null when the electrical readings are missing`() {
        chargePowerMilliwatts(readout(voltage = null)) shouldBe null
        chargePowerMilliwatts(readout(current = null)) shouldBe null
    }
}
