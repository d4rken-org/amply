package eu.darken.amply.alarm.core

import eu.darken.amply.monitor.core.ChargeMonitorTick
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class ChargeAlarmEngineTest {

    private fun tick(
        plugged: Boolean,
        percent: Int,
        sessionActive: Boolean = false,
    ) = ChargeMonitorTick(plugged = plugged, percent = percent, batteryStatus = 0, sessionActive = sessionActive)

    private fun decide(
        plugged: Boolean,
        percent: Int,
        target: Int = 80,
        alreadyFired: Boolean = false,
        sessionActive: Boolean = false,
    ) = ChargeAlarmEngine.decide(tick(plugged, percent, sessionActive), target, alreadyFired)

    @Test
    fun `below target while charging stays idle`() {
        decide(plugged = true, percent = 79) shouldBe ChargeAlarmDecision.IDLE
    }

    @Test
    fun `reaching target fires`() {
        decide(plugged = true, percent = 80) shouldBe ChargeAlarmDecision.FIRE
    }

    @Test
    fun `above target fires when not yet fired`() {
        decide(plugged = true, percent = 95) shouldBe ChargeAlarmDecision.FIRE
    }

    @Test
    fun `does not fire twice in the same cycle`() {
        // Simulates a process-death recreation: the latch is durable, so a fresh engine re-derives
        // IDLE from alreadyFired instead of re-alerting.
        decide(plugged = true, percent = 95, alreadyFired = true) shouldBe ChargeAlarmDecision.IDLE
    }

    @Test
    fun `unplug after firing re-arms`() {
        decide(plugged = false, percent = 95, alreadyFired = true) shouldBe ChargeAlarmDecision.REARM
    }

    @Test
    fun `unplug when not fired is idle`() {
        decide(plugged = false, percent = 60) shouldBe ChargeAlarmDecision.IDLE
    }

    @Test
    fun `replug above target after re-arm fires again`() {
        // After REARM the caller clears the latch, so the next plugged tick fires.
        decide(plugged = true, percent = 90, alreadyFired = false) shouldBe ChargeAlarmDecision.FIRE
    }

    @Test
    fun `active session below target suppresses the cycle`() {
        decide(plugged = true, percent = 50, sessionActive = true) shouldBe ChargeAlarmDecision.SUPPRESS
    }

    @Test
    fun `session that reached full does not fire after suppression`() {
        // Session marked the cycle fired; restoring at 100% (session cleared, still plugged) is IDLE.
        decide(plugged = true, percent = 100, alreadyFired = true) shouldBe ChargeAlarmDecision.IDLE
    }

    @Test
    fun `unknown percent never fires`() {
        decide(plugged = true, percent = -1) shouldBe ChargeAlarmDecision.IDLE
    }

    @Test
    fun `enabling above target fires on the next tick`() {
        decide(plugged = true, percent = 88, target = 85) shouldBe ChargeAlarmDecision.FIRE
    }

    @Test
    fun `raising target above current level stops firing`() {
        // User was at 82 with target 80 (would fire), then raised target to 90.
        decide(plugged = true, percent = 82, target = 90) shouldBe ChargeAlarmDecision.IDLE
    }
}
