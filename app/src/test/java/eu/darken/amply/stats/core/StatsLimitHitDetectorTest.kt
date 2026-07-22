package eu.darken.amply.stats.core

import android.os.BatteryManager
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class StatsLimitHitDetectorTest {

    @Test
    fun `unplugged is never a limit hold`() {
        StatsLimitHitDetector.heldNow(
            plugged = false,
            chargingStatus = StatsLimitHitDetector.HARDWARE_HOLD_STATE,
            batteryStatus = BatteryManager.BATTERY_STATUS_NOT_CHARGING,
            percent = 80,
        ) shouldBe false
    }

    @Test
    fun `hardware hold state signals a limit`() {
        StatsLimitHitDetector.heldNow(
            plugged = true,
            chargingStatus = StatsLimitHitDetector.HARDWARE_HOLD_STATE,
            batteryStatus = BatteryManager.BATTERY_STATUS_CHARGING,
            percent = 80,
        ) shouldBe true
    }

    @Test
    fun `not-charging below full is a heuristic hold`() {
        StatsLimitHitDetector.heldNow(
            plugged = true,
            chargingStatus = 0,
            batteryStatus = BatteryManager.BATTERY_STATUS_NOT_CHARGING,
            percent = 80,
        ) shouldBe true
    }

    @Test
    fun `not-charging at full is not a hold`() {
        StatsLimitHitDetector.heldNow(
            plugged = true,
            chargingStatus = 0,
            batteryStatus = BatteryManager.BATTERY_STATUS_NOT_CHARGING,
            percent = 100,
        ) shouldBe false
    }

    @Test
    fun `plain charging is not a hold`() {
        StatsLimitHitDetector.heldNow(
            plugged = true,
            chargingStatus = 0,
            batteryStatus = BatteryManager.BATTERY_STATUS_CHARGING,
            percent = 50,
        ) shouldBe false
    }
}
