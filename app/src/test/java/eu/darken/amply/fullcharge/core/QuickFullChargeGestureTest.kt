package eu.darken.amply.fullcharge.core

import android.os.BatteryManager
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class QuickFullChargeGestureTest {
    private val gesture = QuickFullChargeGesture()

    @Test
    fun `reconnect after policy stops charging triggers one full charge`() {
        assertThat(atLimit(1_000)).isEqualTo(QuickFullChargeDecision.ARMED)
        assertThat(disconnected(2_000)).isEqualTo(QuickFullChargeDecision.WAITING_FOR_RECONNECT)
        assertThat(charging(5_000)).isEqualTo(QuickFullChargeDecision.TRIGGER)
        assertThat(charging(5_100)).isEqualTo(QuickFullChargeDecision.IDLE)
    }

    @Test
    fun `slow reconnect does not trigger`() {
        atLimit(1_000)
        disconnected(2_000)

        assertThat(charging(12_001)).isEqualTo(QuickFullChargeDecision.IDLE)
    }

    @Test
    fun `ordinary unplug while charging does not trigger`() {
        charging(1_000)
        disconnected(2_000)

        assertThat(charging(3_000)).isEqualTo(QuickFullChargeDecision.IDLE)
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

        assertThat(normalNotCharging).isEqualTo(QuickFullChargeDecision.IDLE)
    }

    @Test
    fun `policy state far above expected limit does not arm`() {
        assertThat(
            gesture.update(
                nowMillis = 1_000,
                plugged = true,
                percent = 100,
                batteryStatus = BatteryManager.BATTERY_STATUS_NOT_CHARGING,
                chargingStatus = QuickFullChargeGesture.CHARGING_STATUS_POLICY,
            ),
        ).isEqualTo(QuickFullChargeDecision.IDLE)
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
