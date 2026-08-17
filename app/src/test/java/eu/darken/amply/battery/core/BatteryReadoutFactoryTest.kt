package eu.darken.amply.battery.core

import android.os.BatteryManager
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class BatteryReadoutFactoryTest {

    @Test
    fun `absent values map to null`() {
        val readout = BatteryReadoutFactory.build()
        readout shouldBe BatteryReadout.UNKNOWN
    }

    @Test
    fun `MIN_VALUE property sentinels map to null`() {
        val readout = BatteryReadoutFactory.build(
            currentNowMicroamps = Int.MIN_VALUE,
            chargeCounterMicroampHours = Int.MIN_VALUE,
            cycleCount = Int.MIN_VALUE,
        )
        readout.currentNowMicroamps shouldBe null
        readout.chargeCounterMicroampHours shouldBe null
        readout.cycleCount shouldBe null
    }

    @Test
    fun `percent is derived from level and scale`() {
        BatteryReadoutFactory.build(level = 41, scale = 100).levelPercent shouldBe 41
        BatteryReadoutFactory.build(level = 50, scale = 200).levelPercent shouldBe 25
    }

    @Test
    fun `invalid level or scale yields null percent`() {
        BatteryReadoutFactory.build(level = 50, scale = 0).levelPercent shouldBe null
        BatteryReadoutFactory.build(level = -1, scale = 100).levelPercent shouldBe null
        BatteryReadoutFactory.build(level = 150, scale = 100).levelPercent shouldBe null
    }

    @Test
    fun `blank technology becomes null`() {
        BatteryReadoutFactory.build(technology = "   ").technology shouldBe null
        BatteryReadoutFactory.build(technology = "Li-ion").technology shouldBe "Li-ion"
    }

    @Test
    fun `negative current is preserved, not dropped`() {
        BatteryReadoutFactory.build(currentNowMicroamps = -450_000).currentNowMicroamps shouldBe -450_000
    }

    @Test
    fun `unknown raw constants pass through untouched`() {
        // A future status/health constant Amply doesn't map must still reach the UI as its raw int.
        BatteryReadoutFactory.build(status = 99).status shouldBe 99
        BatteryReadoutFactory.build(health = 42).health shouldBe 42
    }

    @Test
    fun `a milli-reporting ROM has its charge and current corrected`() {
        // HONOR MagicOS 10 shape (issue #66): a 7100 mAh cell at 100% reported 6978 where the API
        // documents microamp-hours, so the UI rendered "7 mAh", and a real ~300 mA arrived as 300.
        val readout = BatteryReadoutFactory.build(
            level = 100,
            scale = 100,
            chargeCounterMicroampHours = 6_978,
            currentNowMicroamps = -300,
            romMisreportsUnits = true,
        )
        readout.chargeCounterMicroampHours shouldBe 6_978_000
        readout.currentNowMicroamps shouldBe -300_000
    }

    @Test
    fun `an anomalous reading on an unlisted ROM is never corrected`() {
        // The safety property: only a ROM known to misreport may be rescaled. Otherwise a device with a
        // broken or freshly-reset counter would have its healthy current multiplied by a thousand, which
        // at ~50 mA computes to ~200 W, under the plausibility ceiling and so recorded as a credible lie.
        val readout = BatteryReadoutFactory.build(
            level = 100,
            scale = 100,
            chargeCounterMicroampHours = 1,
            currentNowMicroamps = 50_000,
        )
        readout.chargeCounterMicroampHours shouldBe 1
        readout.currentNowMicroamps shouldBe 50_000
    }

    @Test
    fun `a correctly reporting build of a listed ROM is left alone`() {
        // The ROM gate is necessary, not sufficient: without the anomaly there is nothing to correct.
        val readout = BatteryReadoutFactory.build(
            level = 50,
            scale = 100,
            chargeCounterMicroampHours = 3_500_000,
            currentNowMicroamps = 1_500_000,
            romMisreportsUnits = true,
        )
        readout.chargeCounterMicroampHours shouldBe 3_500_000
        readout.currentNowMicroamps shouldBe 1_500_000
    }

    @Test
    fun `a charge-limit hold on a listed ROM is not mistaken for the defect`() {
        // Amply deliberately creates holds: CHARGING with current near zero. The counter is what decides,
        // and a healthy counter means no correction even though the current looks tiny.
        val readout = BatteryReadoutFactory.build(
            level = 80,
            scale = 100,
            status = BatteryManager.BATTERY_STATUS_CHARGING,
            chargeCounterMicroampHours = 5_600_000,
            currentNowMicroamps = 3_000,
            romMisreportsUnits = true,
        )
        readout.currentNowMicroamps shouldBe 3_000
    }

    @Test
    fun `a nearly empty healthy battery is not mistaken for a milli-reporting one`() {
        // The closest the two populations come: 1% of a 7100 mAh cell is 71_000 uAh, a small absolute
        // number. Normalizing by level is what keeps it separable — implied capacity is still ~7.1 Ah.
        BatteryReadoutFactory.chargeCounterLooksMilliScaled(71_000, levelPercent = 1) shouldBe false
        // The same device milli-scaled reports 71, implying ~7.1 mAh.
        BatteryReadoutFactory.chargeCounterLooksMilliScaled(71, levelPercent = 1) shouldBe true
    }

    @Test
    fun `counter detection needs a positive counter and a usable level`() {
        BatteryReadoutFactory.chargeCounterLooksMilliScaled(null, levelPercent = 100) shouldBe false
        BatteryReadoutFactory.chargeCounterLooksMilliScaled(0, levelPercent = 100) shouldBe false
        BatteryReadoutFactory.chargeCounterLooksMilliScaled(6_978, levelPercent = null) shouldBe false
        BatteryReadoutFactory.chargeCounterLooksMilliScaled(6_978, levelPercent = 0) shouldBe false
    }
}
