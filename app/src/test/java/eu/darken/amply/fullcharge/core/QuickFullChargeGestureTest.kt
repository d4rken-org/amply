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
        charging(1_000 + QuickFullChargeGesture.MIN_RECONNECT_MILLIS - 1) shouldBe QuickFullChargeDecision.ARMED

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
        // A 500ms blip (car ignition) is rejected. The replug reading is the realistic one — a phone
        // that just got power back reports CHARGING, never a settled hold — so re-arming can only
        // come from the basis the window carried.
        charging(1_500) shouldBe QuickFullChargeDecision.ARMED
        disconnected(2_000) shouldBe QuickFullChargeDecision.WAITING_FOR_RECONNECT
        charging(5_000) shouldBe QuickFullChargeDecision.TRIGGER
    }

    @Test
    fun `a too-late replug does not carry the basis`() {
        atLimit(1_000) shouldBe QuickFullChargeDecision.ARMED
        disconnected(2_000) shouldBe QuickFullChargeDecision.WAITING_FOR_RECONNECT
        // 15s later: the window is spent and the replug reading cannot re-derive a hold.
        charging(17_000) shouldBe QuickFullChargeDecision.IDLE
        // Without a basis there is nothing to open a window with, so a well-timed retry stays inert.
        disconnected(18_000) shouldBe QuickFullChargeDecision.IDLE
        charging(21_000) shouldBe QuickFullChargeDecision.IDLE
    }

    @Test
    fun `the carried basis survives repeated rejected attempts`() {
        atLimit(0) shouldBe QuickFullChargeDecision.ARMED
        disconnected(1_000) shouldBe QuickFullChargeDecision.WAITING_FOR_RECONNECT
        charging(1_500) shouldBe QuickFullChargeDecision.ARMED
        disconnected(2_000) shouldBe QuickFullChargeDecision.WAITING_FOR_RECONNECT
        charging(2_500) shouldBe QuickFullChargeDecision.ARMED
        disconnected(3_000) shouldBe QuickFullChargeDecision.WAITING_FOR_RECONNECT
        charging(3_500) shouldBe QuickFullChargeDecision.ARMED
        disconnected(4_000) shouldBe QuickFullChargeDecision.WAITING_FOR_RECONNECT
        charging(8_000) shouldBe QuickFullChargeDecision.TRIGGER
    }

    // Characterization test: a carried basis is not bounded by the window it came from. It exists to
    // stop a future "carry deadline" anchored to the original unplug from silently re-breaking the
    // reported bug — such a deadline was proposed, and it would have made exactly this retry inert.
    @Test
    fun `a retry long after the original unplug still triggers`() {
        atLimit(0) shouldBe QuickFullChargeDecision.ARMED
        disconnected(1_000) shouldBe QuickFullChargeDecision.WAITING_FOR_RECONNECT
        // A 500ms blip is rejected and hands the basis back to the plugged-period latch.
        charging(1_500) shouldBe QuickFullChargeDecision.ARMED
        // The deliberate retry comes 19s after the original unplug — far past the 10s ceiling.
        disconnected(20_000) shouldBe QuickFullChargeDecision.WAITING_FOR_RECONNECT
        charging(25_000) shouldBe QuickFullChargeDecision.TRIGGER
    }

    @Test
    fun `a carried limit-hold basis reports a non any-level basis`() {
        atLimit(0) shouldBe QuickFullChargeDecision.ARMED
        disconnected(1_000) shouldBe QuickFullChargeDecision.WAITING_FOR_RECONNECT
        charging(1_500) shouldBe QuickFullChargeDecision.ARMED
        disconnected(2_000) shouldBe QuickFullChargeDecision.WAITING_FOR_RECONNECT
        val output = gesture.update(
            input(
                now = 5_000,
                plugged = true,
                batteryStatus = BatteryManager.BATTERY_STATUS_CHARGING,
                chargingStatus = 1,
            ),
        )
        output.decision shouldBe QuickFullChargeDecision.TRIGGER
        output.anyLevelBasis shouldBe false
    }

    @Test
    fun `a rejected blip does not resurrect a revoked any-level basis`() {
        anyLevelCharging(1_000) shouldBe QuickFullChargeDecision.ARMED
        anyLevelUnplugged(2_000) shouldBe QuickFullChargeDecision.WAITING_FOR_RECONNECT
        // Opt-out mid-window revokes the basis; the carry-over must not hand it back.
        step(
            now = 3_000,
            plugged = false,
            percent = 15,
            batteryStatus = BatteryManager.BATTERY_STATUS_DISCHARGING,
            chargingStatus = 0,
            anyLevel = false,
            evidence = PolicyEvidence.PROTECTIVE,
        ) shouldBe QuickFullChargeDecision.IDLE
        val output = gesture.update(
            input(
                now = 3_500,
                plugged = true,
                percent = 15,
                batteryStatus = BatteryManager.BATTERY_STATUS_CHARGING,
                chargingStatus = 1,
                anyLevel = false,
                evidence = PolicyEvidence.PROTECTIVE,
            ),
        )
        output.decision shouldBe QuickFullChargeDecision.IDLE
        output.anyLevelBasis shouldBe false
    }

    @Test
    fun `reset clears a carried basis`() {
        atLimit(0) shouldBe QuickFullChargeDecision.ARMED
        disconnected(1_000) shouldBe QuickFullChargeDecision.WAITING_FOR_RECONNECT
        charging(1_500) shouldBe QuickFullChargeDecision.ARMED
        gesture.reset()
        // Fresh start: no basis to open a window with, so the next attempt cannot trigger.
        disconnected(2_000) shouldBe QuickFullChargeDecision.IDLE
        charging(5_000) shouldBe QuickFullChargeDecision.IDLE
    }

    @Test
    fun `a rejected blip keeps the limit-hold basis while any level also qualifies`() {
        // Both bases qualify at once: the hardware hold wins the latch and must survive the blip,
        // even though the replug tick alone would only support the any-level basis.
        step(
            now = 0,
            plugged = true,
            batteryStatus = BatteryManager.BATTERY_STATUS_NOT_CHARGING,
            chargingStatus = QuickFullChargeGesture.CHARGING_STATUS_POLICY,
            anyLevel = true,
            evidence = PolicyEvidence.PROTECTIVE,
        ) shouldBe QuickFullChargeDecision.ARMED
        step(
            now = 1_000,
            plugged = false,
            batteryStatus = BatteryManager.BATTERY_STATUS_DISCHARGING,
            chargingStatus = 0,
            anyLevel = true,
            evidence = PolicyEvidence.UNKNOWN,
        ) shouldBe QuickFullChargeDecision.WAITING_FOR_RECONNECT
        step(
            now = 1_500,
            plugged = true,
            batteryStatus = BatteryManager.BATTERY_STATUS_CHARGING,
            chargingStatus = 1,
            anyLevel = true,
            evidence = PolicyEvidence.PROTECTIVE,
        ) shouldBe QuickFullChargeDecision.ARMED
        step(
            now = 2_000,
            plugged = false,
            batteryStatus = BatteryManager.BATTERY_STATUS_DISCHARGING,
            chargingStatus = 0,
            anyLevel = true,
            evidence = PolicyEvidence.UNKNOWN,
        ) shouldBe QuickFullChargeDecision.WAITING_FOR_RECONNECT
        val output = gesture.update(
            input(
                now = 5_000,
                plugged = true,
                batteryStatus = BatteryManager.BATTERY_STATUS_CHARGING,
                chargingStatus = 1,
                anyLevel = true,
                evidence = PolicyEvidence.PROTECTIVE,
            ),
        )
        output.decision shouldBe QuickFullChargeDecision.TRIGGER
        output.anyLevelBasis shouldBe false
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
            evidence = PolicyEvidence.PROTECTIVE,
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
            evidence = PolicyEvidence.PROTECTIVE,
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
            evidence = PolicyEvidence.UNRESTRICTED,
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
            evidence = PolicyEvidence.PROTECTIVE,
        ) shouldBe QuickFullChargeDecision.IDLE
    }

    @Test
    fun `any level gesture triggers at low battery and reports its basis`() {
        anyLevelCharging(1_000) shouldBe QuickFullChargeDecision.ARMED
        anyLevelUnplugged(2_000) shouldBe QuickFullChargeDecision.WAITING_FOR_RECONNECT
        val output = gesture.update(
            input(
                now = 7_000,
                plugged = true,
                percent = 16,
                anyLevel = true,
                evidence = PolicyEvidence.PROTECTIVE,
            ),
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
            evidence = PolicyEvidence.PROTECTIVE,
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
            evidence = PolicyEvidence.UNRESTRICTED,
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
            evidence = PolicyEvidence.PROTECTIVE,
        ) shouldBe QuickFullChargeDecision.IDLE
        step(
            now = 5_000,
            plugged = true,
            percent = 15,
            batteryStatus = BatteryManager.BATTERY_STATUS_CHARGING,
            chargingStatus = 1,
            anyLevel = false,
            evidence = PolicyEvidence.PROTECTIVE,
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
            evidence = PolicyEvidence.UNRESTRICTED,
        ) shouldBe QuickFullChargeDecision.IDLE
    }

    // The regression this whole tri-state exists for: the hardware evidence is only reported while
    // powered, so the very unplug tick that opens the window reports UNKNOWN. Treating that as a
    // withdrawal revoked the basis before the plug edge was recorded, and the gesture never fired
    // on a device whose limit was set natively (empty journal).
    @Test
    fun `an inconclusive unplug tick keeps the any-level window open`() {
        step(
            now = 1_000,
            plugged = true,
            percent = 45,
            batteryStatus = BatteryManager.BATTERY_STATUS_CHARGING,
            chargingStatus = QuickFullChargeGesture.CHARGING_STATUS_POLICY,
            anyLevel = true,
            evidence = PolicyEvidence.PROTECTIVE,
        ) shouldBe QuickFullChargeDecision.ARMED
        step(
            now = 2_000,
            plugged = false,
            percent = 45,
            batteryStatus = BatteryManager.BATTERY_STATUS_DISCHARGING,
            chargingStatus = 0,
            anyLevel = true,
            evidence = PolicyEvidence.UNKNOWN,
        ) shouldBe QuickFullChargeDecision.WAITING_FOR_RECONNECT
        val output = gesture.update(
            input(
                now = 6_000,
                plugged = true,
                percent = 45,
                batteryStatus = BatteryManager.BATTERY_STATUS_CHARGING,
                chargingStatus = 1,
                anyLevel = true,
                evidence = PolicyEvidence.PROTECTIVE,
            ),
        )
        output.decision shouldBe QuickFullChargeDecision.TRIGGER
        output.anyLevelBasis shouldBe true
    }

    @Test
    fun `a conclusively unrestricted unplug tick cancels the any-level window`() {
        step(
            now = 1_000,
            plugged = true,
            percent = 45,
            batteryStatus = BatteryManager.BATTERY_STATUS_CHARGING,
            chargingStatus = QuickFullChargeGesture.CHARGING_STATUS_POLICY,
            anyLevel = true,
            evidence = PolicyEvidence.PROTECTIVE,
        ) shouldBe QuickFullChargeDecision.ARMED
        step(
            now = 2_000,
            plugged = false,
            percent = 45,
            batteryStatus = BatteryManager.BATTERY_STATUS_DISCHARGING,
            chargingStatus = 0,
            anyLevel = true,
            evidence = PolicyEvidence.UNRESTRICTED,
        ) shouldBe QuickFullChargeDecision.IDLE
        step(
            now = 6_000,
            plugged = true,
            percent = 45,
            batteryStatus = BatteryManager.BATTERY_STATUS_CHARGING,
            chargingStatus = 1,
            anyLevel = true,
            evidence = PolicyEvidence.PROTECTIVE,
        ) shouldBe QuickFullChargeDecision.ARMED
    }

    // A natively-removed limit reads UNKNOWN (not UNRESTRICTED) on a journal-less device. While
    // plugged with no window open the evidence is available, so an inconclusive reading must drop
    // the basis — otherwise a later replug would start a session and "restore" a limit the user
    // had deliberately removed.
    @Test
    fun `an inconclusive tick while steadily plugged disarms`() {
        anyLevelCharging(1_000) shouldBe QuickFullChargeDecision.ARMED
        step(
            now = 2_000,
            plugged = true,
            percent = 15,
            batteryStatus = BatteryManager.BATTERY_STATUS_CHARGING,
            chargingStatus = 1,
            anyLevel = true,
            evidence = PolicyEvidence.UNKNOWN,
        ) shouldBe QuickFullChargeDecision.IDLE
        step(
            now = 3_000,
            plugged = false,
            percent = 15,
            batteryStatus = BatteryManager.BATTERY_STATUS_DISCHARGING,
            chargingStatus = 0,
            anyLevel = true,
            evidence = PolicyEvidence.UNKNOWN,
        ) shouldBe QuickFullChargeDecision.IDLE
        step(
            now = 7_000,
            plugged = true,
            percent = 15,
            batteryStatus = BatteryManager.BATTERY_STATUS_CHARGING,
            chargingStatus = 1,
            anyLevel = true,
            evidence = PolicyEvidence.UNKNOWN,
        ) shouldBe QuickFullChargeDecision.IDLE
    }

    // The open-window guard: the replug tick may still report UNKNOWN because the hardware has not
    // re-reported its hold yet. Revoking there would destroy the very trigger the gesture exists for.
    @Test
    fun `an inconclusive replug inside the window still triggers`() {
        anyLevelCharging(1_000) shouldBe QuickFullChargeDecision.ARMED
        step(
            now = 2_000,
            plugged = false,
            percent = 15,
            batteryStatus = BatteryManager.BATTERY_STATUS_DISCHARGING,
            chargingStatus = 0,
            anyLevel = true,
            evidence = PolicyEvidence.UNKNOWN,
        ) shouldBe QuickFullChargeDecision.WAITING_FOR_RECONNECT
        val output = gesture.update(
            input(
                now = 6_000,
                plugged = true,
                percent = 15,
                batteryStatus = BatteryManager.BATTERY_STATUS_CHARGING,
                chargingStatus = 1,
                anyLevel = true,
                evidence = PolicyEvidence.UNKNOWN,
            ),
        )
        output.decision shouldBe QuickFullChargeDecision.TRIGGER
        output.anyLevelBasis shouldBe true
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
            evidence = PolicyEvidence.UNRESTRICTED,
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
            evidence = PolicyEvidence.PROTECTIVE,
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
            evidence = PolicyEvidence.PROTECTIVE,
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
        evidence: PolicyEvidence = PolicyEvidence.UNKNOWN,
    ) = QuickFullChargeGesture.Input(
        nowMillis = now,
        plugged = plugged,
        percent = percent,
        batteryStatus = batteryStatus,
        chargingStatus = chargingStatus,
        anyLevelEnabled = anyLevel,
        policyEvidence = evidence,
    )

    private fun step(
        now: Long,
        plugged: Boolean,
        percent: Int = 80,
        batteryStatus: Int = BatteryManager.BATTERY_STATUS_UNKNOWN,
        chargingStatus: Int = 0,
        anyLevel: Boolean = false,
        evidence: PolicyEvidence = PolicyEvidence.UNKNOWN,
    ) = gesture.update(
        input(now, plugged, percent, batteryStatus, chargingStatus, anyLevel, evidence),
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
        evidence = PolicyEvidence.PROTECTIVE,
    )

    private fun anyLevelUnplugged(now: Long) = step(
        now = now,
        plugged = false,
        percent = 15,
        batteryStatus = BatteryManager.BATTERY_STATUS_DISCHARGING,
        chargingStatus = 0,
        anyLevel = true,
        evidence = PolicyEvidence.PROTECTIVE,
    )
}
