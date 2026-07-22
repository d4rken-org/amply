package eu.darken.amply.stats.core

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class StatsPowerCalculatorTest {

    @Test
    fun `computes battery power in milliwatts from mV and uA`() {
        // 4000 mV * 2_000_000 uA = 8 W = 8000 mW
        StatsPowerCalculator.milliwatts(4000, 2_000_000) shouldBe 8000
    }

    @Test
    fun `current magnitude is used so discharge sign does not matter`() {
        StatsPowerCalculator.milliwatts(4000, -2_000_000) shouldBe 8000
    }

    @Test
    fun `null inputs yield null`() {
        StatsPowerCalculator.milliwatts(null, 1_000_000) shouldBe null
        StatsPowerCalculator.milliwatts(4000, null) shouldBe null
    }

    @Test
    fun `non-positive voltage is rejected`() {
        StatsPowerCalculator.milliwatts(0, 1_000_000) shouldBe null
        StatsPowerCalculator.milliwatts(-10, 1_000_000) shouldBe null
    }

    @Test
    fun `implausible OEM current is rejected rather than poisoning the average`() {
        // A device reporting current in mA instead of uA would look like ~1000x too much power.
        StatsPowerCalculator.milliwatts(4000, 2_000_000_0 /* 20 A in uA-ish scale */).let {
            // 4000mV * 20_000_000uA = 80W = 80000mW, still under cap
            it shouldBe 80000
        }
        // 4000 mV * 100_000_000 (bad units) = 400 W > cap → null
        StatsPowerCalculator.milliwatts(4000, 100_000_000) shouldBe null
    }

    @Test
    fun `long math avoids int overflow`() {
        // 4300 * 3_000_000 = 12_900_000_000 which overflows Int; result 12_900 mW.
        StatsPowerCalculator.milliwatts(4300, 3_000_000) shouldBe 12_900
    }
}
