package eu.darken.amply.fullcharge.core

import android.os.BatteryManager
import io.kotest.assertions.throwables.shouldThrow
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
        step(
            now = 1_000,
            plugged = true,
            batteryStatus = BatteryManager.BATTERY_STATUS_NOT_CHARGING,
            chargingStatus = 1,
        ) shouldBe QuickFullChargeDecision.IDLE
    }

    @Test
    fun `policy state far above expected limit does not arm`() {
        step(
            now = 1_000,
            plugged = true,
            percent = 100,
            batteryStatus = BatteryManager.BATTERY_STATUS_NOT_CHARGING,
            chargingStatus = QuickFullChargeGesture.CHARGING_STATUS_POLICY,
        ) shouldBe QuickFullChargeDecision.IDLE
    }

    @Test
    fun `reconnect window boundaries`() {
        // Debounce floor: exactly the minimum triggers, one millisecond less does not.
        atLimit(0)
        disconnected(1_000)
        atLimit(1_000 + QuickFullChargeGesture.MIN_RECONNECT_MILLIS - 1) shouldBe QuickFullChargeDecision.ARMED

        gesture.reset()
        atLimit(0)
        disconnected(1_000)
        atLimit(1_000 + QuickFullChargeGesture.MIN_RECONNECT_MILLIS) shouldBe QuickFullChargeDecision.TRIGGER

        // Upper bound: exactly the maximum triggers, one millisecond more does not.
        gesture.reset()
        atLimit(0)
        disconnected(1_000)
        atLimit(1_000 + QuickFullChargeGesture.MAX_RECONNECT_MILLIS) shouldBe QuickFullChargeDecision.TRIGGER

        gesture.reset()
        atLimit(0)
        disconnected(1_000)
        charging(1_000 + QuickFullChargeGesture.MAX_RECONNECT_MILLIS + 1) shouldBe QuickFullChargeDecision.IDLE
    }

    @Test
    fun `rejected fast blip immediately re-arms and a second attempt can trigger`() {
        atLimit(0) shouldBe QuickFullChargeDecision.ARMED
        disconnected(1_000) shouldBe QuickFullChargeDecision.WAITING_FOR_RECONNECT
        // A 500ms blip (car ignition) is rejected, but the replug observation itself re-arms.
        atLimit(1_500) shouldBe QuickFullChargeDecision.ARMED
        disconnected(2_000) shouldBe QuickFullChargeDecision.WAITING_FOR_RECONNECT
        atLimit(5_000) shouldBe QuickFullChargeDecision.TRIGGER
    }

    @Test
    fun `window expires while staying unplugged`() {
        atLimit(0)
        disconnected(1_000)
        // Still unplugged past the window: state degrades without needing a reconnect event.
        disconnected(12_000) shouldBe QuickFullChargeDecision.IDLE
        charging(13_000) shouldBe QuickFullChargeDecision.IDLE
    }

    @Test
    fun `any level arms regardless of percent status and hardware state`() {
        step(
            now = 1_000,
            plugged = true,
            percent = 15,
            batteryStatus = BatteryManager.BATTERY_STATUS_CHARGING,
            chargingStatus = 1,
            anyLevel = true,
            protective = true,
        ) shouldBe QuickFullChargeDecision.ARMED
    }

    @Test
    fun `any level ignores unknown percent and status`() {
        step(
            now = 1_000,
            plugged = true,
            percent = -1,
            batteryStatus = BatteryManager.BATTERY_STATUS_UNKNOWN,
            chargingStatus = 0,
            anyLevel = true,
            protective = true,
        ) shouldBe QuickFullChargeDecision.ARMED
    }

    @Test
    fun `any level without protective policy stays idle`() {
        step(
            now = 1_000,
            plugged = true,
            percent = 15,
            batteryStatus = BatteryManager.BATTERY_STATUS_CHARGING,
            chargingStatus = 1,
            anyLevel = true,
            protective = false,
        ) shouldBe QuickFullChargeDecision.IDLE
    }

    @Test
    fun `any level requires external power`() {
        step(
            now = 1_000,
            plugged = false,
            percent = 15,
            batteryStatus = BatteryManager.BATTERY_STATUS_DISCHARGING,
            chargingStatus = 0,
            anyLevel = true,
            protective = true,
        ) shouldBe QuickFullChargeDecision.IDLE
    }

    @Test
    fun `any level gesture triggers at low battery and reports its basis`() {
        anyLevelCharging(1_000) shouldBe QuickFullChargeDecision.ARMED
        anyLevelUnplugged(2_000) shouldBe QuickFullChargeDecision.WAITING_FOR_RECONNECT
        val output = gesture.update(
            input(now = 7_000, plugged = true, percent = 16, anyLevel = true, protective = true),
        )
        output.decision shouldBe QuickFullChargeDecision.TRIGGER
        output.anyLevelBasis shouldBe true
    }

    @Test
    fun `limit hold trigger reports a non any-level basis`() {
        atLimit(1_000)
        disconnected(2_000)
        val output = gesture.update(
            input(
                now = 5_000,
                plugged = true,
                batteryStatus = BatteryManager.BATTERY_STATUS_NOT_CHARGING,
                chargingStatus = QuickFullChargeGesture.CHARGING_STATUS_POLICY,
            ),
        )
        output.decision shouldBe QuickFullChargeDecision.TRIGGER
        output.anyLevelBasis shouldBe false
    }

    @Test
    fun `disabling any level while steadily plugged disarms`() {
        anyLevelCharging(1_000) shouldBe QuickFullChargeDecision.ARMED
        step(
            now = 2_000,
            plugged = true,
            percent = 15,
            batteryStatus = BatteryManager.BATTERY_STATUS_CHARGING,
            chargingStatus = 1,
            anyLevel = false,
            protective = true,
        ) shouldBe QuickFullChargeDecision.IDLE
    }

    @Test
    fun `losing the protective policy while steadily plugged disarms`() {
        anyLevelCharging(1_000) shouldBe QuickFullChargeDecision.ARMED
        step(
            now = 2_000,
            plugged = true,
            percent = 15,
            batteryStatus = BatteryManager.BATTERY_STATUS_CHARGING,
            chargingStatus = 1,
            anyLevel = true,
            protective = false,
        ) shouldBe QuickFullChargeDecision.IDLE
    }

    @Test
    fun `disabling any level cancels an open any-level window`() {
        anyLevelCharging(1_000)
        anyLevelUnplugged(2_000) shouldBe QuickFullChargeDecision.WAITING_FOR_RECONNECT
        // Opt-out mid-window: the window is cancelled and a timely replug must not trigger.
        step(
            now = 3_000,
            plugged = false,
            percent = 15,
            batteryStatus = BatteryManager.BATTERY_STATUS_DISCHARGING,
            chargingStatus = 0,
            anyLevel = false,
            protective = true,
        ) shouldBe QuickFullChargeDecision.IDLE
        step(
            now = 5_000,
            plugged = true,
            percent = 15,
            batteryStatus = BatteryManager.BATTERY_STATUS_CHARGING,
            chargingStatus = 1,
            anyLevel = false,
            protective = true,
        ) shouldBe QuickFullChargeDecision.IDLE
    }

    @Test
    fun `losing the protective policy cancels an any-level window at the replug tick`() {
        anyLevelCharging(1_000)
        anyLevelUnplugged(2_000) shouldBe QuickFullChargeDecision.WAITING_FOR_RECONNECT
        // The revocation is only observed on the replug tick itself and must still win.
        step(
            now = 5_000,
            plugged = true,
            percent = 15,
            batteryStatus = BatteryManager.BATTERY_STATUS_CHARGING,
            chargingStatus = 1,
            anyLevel = true,
            protective = false,
        ) shouldBe QuickFullChargeDecision.IDLE
    }

    @Test
    fun `limit hold window survives an any-level option flip`() {
        atLimit(1_000)
        disconnected(2_000) shouldBe QuickFullChargeDecision.WAITING_FOR_RECONNECT
        // Enabling the option mid-window must not invalidate hardware-hold evidence.
        step(
            now = 5_000,
            plugged = true,
            batteryStatus = BatteryManager.BATTERY_STATUS_NOT_CHARGING,
            chargingStatus = QuickFullChargeGesture.CHARGING_STATUS_POLICY,
            anyLevel = true,
            protective = false,
        ) shouldBe QuickFullChargeDecision.TRIGGER
    }

    @Test
    fun `enabling any level while steadily plugged arms without a plug transition`() {
        charging(1_000) shouldBe QuickFullChargeDecision.IDLE
        step(
            now = 2_000,
            plugged = true,
            batteryStatus = BatteryManager.BATTERY_STATUS_CHARGING,
            chargingStatus = 1,
            anyLevel = true,
            protective = true,
        ) shouldBe QuickFullChargeDecision.ARMED
    }

    @Test
    fun `reset clears any-level state like a fresh start`() {
        anyLevelCharging(1_000)
        anyLevelUnplugged(2_000) shouldBe QuickFullChargeDecision.WAITING_FOR_RECONNECT
        gesture.reset()
        // A replug right after reset has no window to consume.
        step(
            now = 3_000,
            plugged = true,
            percent = 15,
            batteryStatus = BatteryManager.BATTERY_STATUS_CHARGING,
            chargingStatus = 1,
            anyLevel = true,
            protective = true,
        ) shouldBe QuickFullChargeDecision.ARMED
    }

    @Test
    fun `window bounds are validated`() {
        shouldThrow<IllegalArgumentException> { QuickFullChargeGesture(minReconnectMillis = -1) }
        shouldThrow<IllegalArgumentException> {
            QuickFullChargeGesture(minReconnectMillis = 5_000, maxReconnectMillis = 4_999)
        }
    }

    private fun input(
        now: Long,
        plugged: Boolean,
        percent: Int = 80,
        batteryStatus: Int = BatteryManager.BATTERY_STATUS_UNKNOWN,
        chargingStatus: Int = 0,
        anyLevel: Boolean = false,
        protective: Boolean = false,
    ) = QuickFullChargeGesture.Input(
        nowMillis = now,
        plugged = plugged,
        percent = percent,
        batteryStatus = batteryStatus,
        chargingStatus = chargingStatus,
        anyLevelEnabled = anyLevel,
        policyProtective = protective,
    )

    private fun step(
        now: Long,
        plugged: Boolean,
        percent: Int = 80,
        batteryStatus: Int = BatteryManager.BATTERY_STATUS_UNKNOWN,
        chargingStatus: Int = 0,
        anyLevel: Boolean = false,
        protective: Boolean = false,
    ) = gesture.update(
        input(now, plugged, percent, batteryStatus, chargingStatus, anyLevel, protective),
    ).decision

    private fun atLimit(now: Long) = step(
        now = now,
        plugged = true,
        batteryStatus = BatteryManager.BATTERY_STATUS_NOT_CHARGING,
        chargingStatus = QuickFullChargeGesture.CHARGING_STATUS_POLICY,
    )

    private fun charging(now: Long) = step(
        now = now,
        plugged = true,
        batteryStatus = BatteryManager.BATTERY_STATUS_CHARGING,
        chargingStatus = 1,
    )

    private fun disconnected(now: Long) = step(
        now = now,
        plugged = false,
        batteryStatus = BatteryManager.BATTERY_STATUS_DISCHARGING,
        chargingStatus = 0,
    )

    private fun anyLevelCharging(now: Long) = step(
        now = now,
        plugged = true,
        percent = 15,
        batteryStatus = BatteryManager.BATTERY_STATUS_CHARGING,
        chargingStatus = 1,
        anyLevel = true,
        protective = true,
    )

    private fun anyLevelUnplugged(now: Long) = step(
        now = now,
        plugged = false,
        percent = 15,
        batteryStatus = BatteryManager.BATTERY_STATUS_DISCHARGING,
        chargingStatus = 0,
        anyLevel = true,
        protective = true,
    )
}
