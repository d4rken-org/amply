package eu.darken.amply.stats.core

import android.os.BatteryManager
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class StatsPowerCalculatorTest {

    @Test
    fun `computes battery power in milliwatts from mV and uA`() {
        // 4000 mV * 2_000_000 uA = 8 W = 8000 mW
        StatsPowerCalculator.milliwatts(4000, 2_000_000) shouldBe 8000
    }

    @Test
    fun `current magnitude is used so discharge sign does not matter`() {
        StatsPowerCalculator.milliwatts(4000, -2_000_000) shouldBe 8000
    }

    @Test
    fun `null inputs yield null`() {
        StatsPowerCalculator.milliwatts(null, 1_000_000) shouldBe null
        StatsPowerCalculator.milliwatts(4000, null) shouldBe null
    }

    @Test
    fun `non-positive voltage is rejected`() {
        StatsPowerCalculator.milliwatts(0, 1_000_000) shouldBe null
        StatsPowerCalculator.milliwatts(-10, 1_000_000) shouldBe null
    }

    @Test
    fun `implausible OEM current is rejected rather than poisoning the average`() {
        // A device reporting current in mA instead of uA would look like ~1000x too much power.
        StatsPowerCalculator.milliwatts(4000, 2_000_000_0 /* 20 A in uA-ish scale */).let {
            // 4000mV * 20_000_000uA = 80W = 80000mW, still under cap
            it shouldBe 80000
        }
        // 4000 mV * 100_000_000 (bad units) = 400 W > cap → null
        StatsPowerCalculator.milliwatts(4000, 100_000_000) shouldBe null
    }

    @Test
    fun `long math avoids int overflow`() {
        // 4300 * 3_000_000 = 12_900_000_000 which overflows Int; result 12_900 mW.
        StatsPowerCalculator.milliwatts(4300, 3_000_000) shouldBe 12_900
    }

    // The direction gate. `milliwatts` is unsigned, so without this the same number means "charging
    // at 8 W" and "draining at 8 W" — and the sign of currentNow can't disambiguate, being
    // OEM-defined. Everything that shows or stores charge power goes through here.

    @Test
    fun `charge power is reported while charging on a charger`() {
        StatsPowerCalculator.chargeMilliwatts(
            batteryStatus = BatteryManager.BATTERY_STATUS_CHARGING,
            plugged = true,
            voltageMillivolts = 4000,
            currentNowMicroamps = 2_000_000,
        ) shouldBe 8000
    }

    @Test
    fun `discharge draw is never reported as charge power`() {
        // Plugged in but losing charge (load exceeds the supply): the magnitude is real, but calling
        // it charge power would invert its meaning.
        StatsPowerCalculator.chargeMilliwatts(
            batteryStatus = BatteryManager.BATTERY_STATUS_DISCHARGING,
            plugged = true,
            voltageMillivolts = 4000,
            currentNowMicroamps = -2_000_000,
        ) shouldBe null
    }

    @Test
    fun `a limit hold reports no charge power`() {
        StatsPowerCalculator.chargeMilliwatts(
            batteryStatus = BatteryManager.BATTERY_STATUS_NOT_CHARGING,
            plugged = true,
            voltageMillivolts = 4000,
            currentNowMicroamps = 0,
        ) shouldBe null
    }

    @Test
    fun `a full battery reports no charge power`() {
        StatsPowerCalculator.chargeMilliwatts(
            batteryStatus = BatteryManager.BATTERY_STATUS_FULL,
            plugged = true,
            voltageMillivolts = 4000,
            currentNowMicroamps = 100_000,
        ) shouldBe null
    }

    @Test
    fun `an unknown or absent status reports no charge power`() {
        StatsPowerCalculator.chargeMilliwatts(
            batteryStatus = BatteryManager.BATTERY_STATUS_UNKNOWN,
            plugged = true,
            voltageMillivolts = 4000,
            currentNowMicroamps = 2_000_000,
        ) shouldBe null
        StatsPowerCalculator.chargeMilliwatts(
            batteryStatus = null,
            plugged = true,
            voltageMillivolts = 4000,
            currentNowMicroamps = 2_000_000,
        ) shouldBe null
    }

    @Test
    fun `a claimed charging status off the charger is not trusted`() {
        StatsPowerCalculator.chargeMilliwatts(
            batteryStatus = BatteryManager.BATTERY_STATUS_CHARGING,
            plugged = false,
            voltageMillivolts = 4000,
            currentNowMicroamps = 2_000_000,
        ) shouldBe null
    }

    @Test
    fun `the magnitude rules still apply through the gate`() {
        StatsPowerCalculator.chargeMilliwatts(
            batteryStatus = BatteryManager.BATTERY_STATUS_CHARGING,
            plugged = true,
            voltageMillivolts = 4000,
            currentNowMicroamps = null,
        ) shouldBe null
        // Implausible reading (bad OEM units) is rejected here too.
        StatsPowerCalculator.chargeMilliwatts(
            batteryStatus = BatteryManager.BATTERY_STATUS_CHARGING,
            plugged = true,
            voltageMillivolts = 4000,
            currentNowMicroamps = 100_000_000,
        ) shouldBe null
    }
}
