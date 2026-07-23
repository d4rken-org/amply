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
            currentNowMicroamps = 0,
        ) shouldBe false
    }

    @Test
    fun `not-charging below full is a hold`() {
        StatsLimitHitDetector.heldNow(
            plugged = true,
            chargingStatus = 0,
            batteryStatus = BatteryManager.BATTERY_STATUS_NOT_CHARGING,
            percent = 80,
            currentNowMicroamps = 0,
        ) shouldBe true
    }

    @Test
    fun `not-charging below full is a hold even when current is unreported`() {
        // Devices that don't expose charge current must still latch via the NOT_CHARGING fallback.
        StatsLimitHitDetector.heldNow(
            plugged = true,
            chargingStatus = 0,
            batteryStatus = BatteryManager.BATTERY_STATUS_NOT_CHARGING,
            percent = 80,
            currentNowMicroamps = null,
        ) shouldBe true
    }

    @Test
    fun `not-charging at full is not a hold`() {
        StatsLimitHitDetector.heldNow(
            plugged = true,
            chargingStatus = 0,
            batteryStatus = BatteryManager.BATTERY_STATUS_NOT_CHARGING,
            percent = 100,
            currentNowMicroamps = 0,
        ) shouldBe false
    }

    @Test
    fun `plain charging is not a hold`() {
        StatsLimitHitDetector.heldNow(
            plugged = true,
            chargingStatus = 0,
            batteryStatus = BatteryManager.BATTERY_STATUS_CHARGING,
            percent = 50,
            currentNowMicroamps = 1_300_000,
        ) shouldBe false
    }

    @Test
    fun `long-life mode while actively charging is not a hold`() {
        // The reported false positive: Pixel reports EXTRA_CHARGING_STATUS==4 for the whole
        // fixed-limit session, including a 5 s plug at 68 % pulling 0.56 A toward the cap.
        StatsLimitHitDetector.heldNow(
            plugged = true,
            chargingStatus = StatsLimitHitDetector.HARDWARE_HOLD_STATE,
            batteryStatus = BatteryManager.BATTERY_STATUS_CHARGING,
            percent = 68,
            currentNowMicroamps = 563_281,
        ) shouldBe false
    }

    @Test
    fun `long-life mode holding at the cap with near-zero current is a hold`() {
        // The transitional window: the cap is holding but status still reads CHARGING; current has
        // already collapsed to ~0 (measured hold noise on a held Pixel).
        StatsLimitHitDetector.heldNow(
            plugged = true,
            chargingStatus = StatsLimitHitDetector.HARDWARE_HOLD_STATE,
            batteryStatus = BatteryManager.BATTERY_STATUS_CHARGING,
            percent = 80,
            currentNowMicroamps = -18_750,
        ) shouldBe true
    }

    @Test
    fun `long-life mode with near-zero current but not a charging status is not a hold`() {
        // The near-zero-current refinement only rescues the CHARGING transitional window. FULL /
        // DISCHARGING / unknown with mode 4 is not the documented hold and must not latch.
        StatsLimitHitDetector.heldNow(
            plugged = true,
            chargingStatus = StatsLimitHitDetector.HARDWARE_HOLD_STATE,
            batteryStatus = BatteryManager.BATTERY_STATUS_FULL,
            percent = 80,
            currentNowMicroamps = 0,
        ) shouldBe false
    }

    @Test
    fun `unknown percent is never a hold`() {
        // Without a known level we can't claim a limit was reached, even when not charging.
        StatsLimitHitDetector.heldNow(
            plugged = true,
            chargingStatus = 0,
            batteryStatus = BatteryManager.BATTERY_STATUS_NOT_CHARGING,
            percent = null,
            currentNowMicroamps = 0,
        ) shouldBe false
    }

    @Test
    fun `long-life mode with unreported current is not a hold`() {
        // Without NOT_CHARGING or a current reading we can't tell a hold from active charging, so
        // stay conservative rather than latch a false positive.
        StatsLimitHitDetector.heldNow(
            plugged = true,
            chargingStatus = StatsLimitHitDetector.HARDWARE_HOLD_STATE,
            batteryStatus = BatteryManager.BATTERY_STATUS_CHARGING,
            percent = 80,
            currentNowMicroamps = null,
        ) shouldBe false
    }
}
