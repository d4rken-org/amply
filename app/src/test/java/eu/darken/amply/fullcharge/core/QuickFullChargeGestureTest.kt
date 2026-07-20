package eu.darken.amply.fullcharge.core

import android.os.BatteryManager
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class QuickFullChargeGestureTest {
    private val gesture = QuickFullChargeGesture()

    @Test
    fun `reconnect after policy stops charging triggers one full charge`() {
        atLimit(1_000) shouldBe QuickFullChargeDecision.ARMED
        disconnected(2_000) shouldBe QuickFullChargeDecision.WAITING_FOR_RECONNECT
        charging(5_000) shouldBe QuickFullChargeDecision.TRIGGER
        charging(5_100) shouldBe QuickFullChargeDecision.IDLE
    }

    @Test
    fun `slow reconnect does not trigger`() {
        atLimit(1_000)
        disconnected(2_000)

        charging(12_001) shouldBe QuickFullChargeDecision.IDLE
    }

    @Test
    fun `ordinary unplug while charging does not trigger`() {
        charging(1_000)
        disconnected(2_000)

        charging(3_000) shouldBe QuickFullChargeDecision.IDLE
    }

    @Test
    fun `cached limit is insufficient without hardware policy state`() {
        val normalNotCharging = gesture.update(
            nowMillis = 1_000,
            plugged = true,
            percent = 80,
            batteryStatus = BatteryManager.BATTERY_STATUS_NOT_CHARGING,
            chargingStatus = 1,
        )

        normalNotCharging shouldBe QuickFullChargeDecision.IDLE
    }

    @Test
    fun `policy state far above expected limit does not arm`() {
        gesture.update(
            nowMillis = 1_000,
            plugged = true,
            percent = 100,
            batteryStatus = BatteryManager.BATTERY_STATUS_NOT_CHARGING,
            chargingStatus = QuickFullChargeGesture.CHARGING_STATUS_POLICY,
        ) shouldBe QuickFullChargeDecision.IDLE
    }

    private fun atLimit(now: Long) = gesture.update(
        nowMillis = now,
        plugged = true,
        percent = 80,
        batteryStatus = BatteryManager.BATTERY_STATUS_NOT_CHARGING,
        chargingStatus = QuickFullChargeGesture.CHARGING_STATUS_POLICY,
    )

    private fun charging(now: Long) = gesture.update(
        nowMillis = now,
        plugged = true,
        percent = 80,
        batteryStatus = BatteryManager.BATTERY_STATUS_CHARGING,
        chargingStatus = 1,
    )

    private fun disconnected(now: Long) = gesture.update(
        nowMillis = now,
        plugged = false,
        percent = 80,
        batteryStatus = BatteryManager.BATTERY_STATUS_DISCHARGING,
        chargingStatus = 0,
    )
}
