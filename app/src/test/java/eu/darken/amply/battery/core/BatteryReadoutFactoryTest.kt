package eu.darken.amply.battery.core

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
}
