package eu.darken.amply.main.ui.qualification

import android.os.BatteryManager
import eu.darken.amply.battery.core.BatteryReadout
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class QualificationPrecheckStatusTest {

    /**
     * 50% with 2 000 mAh counted implies a 4 000 mAh pack, so 20 points is 800 mAh; at 1 600 mA that
     * is half an hour.
     */
    private fun charging(
        level: Int? = 50,
        counter: Int? = 2_000_000,
        current: Int? = 1_600_000,
        status: Int? = BatteryManager.BATTERY_STATUS_CHARGING,
        plugged: Int? = BatteryManager.BATTERY_PLUGGED_AC,
    ) = BatteryReadout(
        levelPercent = level,
        status = status,
        plugged = plugged,
        currentNowMicroamps = current,
        chargeCounterMicroampHours = counter,
    )

    @Test
    fun `a plain charging case extrapolates from the reported counter and current`() {
        estimateMinutesToPercent(charging(), targetPercent = 70) shouldBe 30
    }

    @Test
    fun `an unplugged battery yields no estimate`() {
        estimateMinutesToPercent(charging(plugged = 0), targetPercent = 70) shouldBe null
        estimateMinutesToPercent(charging(plugged = null), targetPercent = 70) shouldBe null
    }

    /**
     * The status is the only trustworthy direction signal — the current's sign is OEM-defined — so a
     * device sitting at a protection hold reports plugged but must produce no charge-rate estimate.
     */
    @Test
    fun `a plugged but not-charging battery yields no estimate`() {
        estimateMinutesToPercent(
            charging(status = BatteryManager.BATTERY_STATUS_NOT_CHARGING),
            targetPercent = 70,
        ) shouldBe null
        estimateMinutesToPercent(charging(status = null), targetPercent = 70) shouldBe null
    }

    @Test
    fun `a missing input yields no estimate`() {
        estimateMinutesToPercent(charging(level = null), targetPercent = 70) shouldBe null
        estimateMinutesToPercent(charging(counter = null), targetPercent = 70) shouldBe null
        estimateMinutesToPercent(charging(current = null), targetPercent = 70) shouldBe null
    }

    @Test
    fun `a level outside the usable range yields no estimate`() {
        estimateMinutesToPercent(charging(level = 0), targetPercent = 70) shouldBe null
        estimateMinutesToPercent(charging(level = 100), targetPercent = 101) shouldBe null
    }

    @Test
    fun `a target at or below the current level yields no estimate`() {
        estimateMinutesToPercent(charging(), targetPercent = 50) shouldBe null
        estimateMinutesToPercent(charging(), targetPercent = 40) shouldBe null
    }

    @Test
    fun `an implausible result yields no estimate`() {
        // Zero current divides to infinity rather than to a very long wait.
        estimateMinutesToPercent(charging(current = 0), targetPercent = 70) shouldBe null
        // A zero counter implies a zero pack, so nothing is needed and nothing can be said.
        estimateMinutesToPercent(charging(counter = 0), targetPercent = 70) shouldBe null
        // 20 points of a 4 000 mAh pack at 1 mA is ~800 hours.
        estimateMinutesToPercent(charging(current = 1_000), targetPercent = 70) shouldBe null
    }

    /**
     * A counter reported in milli- rather than micro-units, with a correctly scaled current: 50% of
     * 2 000 µAh implies a 4 mAh pack, so the 20 remaining points come out as 0.03 minutes and round
     * to "About 10 minutes" for what is really a long wait. The 24-hour ceiling only catches a
     * mis-scaling in the other direction, so the implied capacity has to be judged on its own.
     */
    @Test
    fun `a counter too small to be microamp-hours yields no estimate`() {
        estimateMinutesToPercent(charging(counter = 2_000), targetPercent = 70) shouldBe null
    }

    @Test
    fun `the sign of the reported current is not read as a direction`() {
        estimateMinutesToPercent(charging(current = -1_600_000), targetPercent = 70) shouldBe 30
    }

    @Test
    fun `estimates are spoken in round buckets rather than to the minute`() {
        etaBucket(1) shouldBe EtaBucket.Minutes(10)
        etaBucket(43) shouldBe EtaBucket.Minutes(40)
        etaBucket(44) shouldBe EtaBucket.Minutes(40)
        etaBucket(45) shouldBe EtaBucket.AboutAnHour
        etaBucket(75) shouldBe EtaBucket.AboutAnHour
        etaBucket(76) shouldBe EtaBucket.OverAnHour
    }

    @Test
    fun `the status block reports charging only when the battery is actually taking charge`() {
        precheckStatus(charging(), requiredPercent = 70).charging shouldBe true
        precheckStatus(
            charging(status = BatteryManager.BATTERY_STATUS_NOT_CHARGING),
            requiredPercent = 70,
        ).charging shouldBe false
        // Nothing observed at all is not the same as "not charging".
        precheckStatus(BatteryReadout.UNKNOWN, requiredPercent = 70).charging shouldBe null
    }

    /**
     * The three shapes where charging is genuinely unknown. Each one used to print "Not charging
     * right now" as a fact — the line a waiting user is most likely to act on.
     */
    @Test
    fun `an unknown charging state is reported as unknown rather than as not charging`() {
        // A valid platform status: plugged in, and the platform declining to say what that means.
        precheckStatus(
            charging(status = BatteryManager.BATTERY_STATUS_UNKNOWN),
            requiredPercent = 70,
        ).charging shouldBe null
        // Only the positive half of the pair is present, so nothing about charging was observed.
        precheckStatus(charging(status = null), requiredPercent = 70).charging shouldBe null
        precheckStatus(charging(plugged = null), requiredPercent = 70).charging shouldBe null
    }

    /** The negative side only needs one half of the pair: either one settles it. */
    @Test
    fun `an observed negative on either half reports not charging`() {
        precheckStatus(charging(plugged = 0), requiredPercent = 70).charging shouldBe false
        precheckStatus(
            charging(plugged = null, status = BatteryManager.BATTERY_STATUS_DISCHARGING),
            requiredPercent = 70,
        ).charging shouldBe false
        precheckStatus(
            charging(status = BatteryManager.BATTERY_STATUS_FULL),
            requiredPercent = 70,
        ).charging shouldBe false
    }

    @Test
    fun `the status block carries the figures the screen renders`() {
        val status = precheckStatus(charging(), requiredPercent = 70)

        status.currentPercent shouldBe 50
        status.requiredPercent shouldBe 70
        status.estimatedMinutes shouldBe 30
    }

    /** No target, no estimate: the screen would have nothing to count towards. */
    @Test
    fun `without a required level the block carries no estimate`() {
        val status = precheckStatus(charging(), requiredPercent = null)

        status.requiredPercent shouldBe null
        status.estimatedMinutes shouldBe null
    }
}
