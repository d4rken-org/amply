package eu.darken.amply.charging.core

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class ChargePolicyTest {
    @Test
    fun `stable ids round trip`() {
        listOf(
            ChargePolicy.FixedLimit(80),
            ChargePolicy.Adaptive,
            ChargePolicy.Unrestricted,
            ChargePolicy.PauseAtFull,
        ).forEach { policy ->
            ChargePolicy.fromStableId(policy.stableId) shouldBe policy
        }
    }

    @Test
    fun `only full-reaching policies allow a full charge`() {
        ChargePolicy.Unrestricted.allowsFullCharge shouldBe true
        ChargePolicy.PauseAtFull.allowsFullCharge shouldBe true
        ChargePolicy.Adaptive.allowsFullCharge shouldBe false
        ChargePolicy.FixedLimit(95).allowsFullCharge shouldBe false
        // A 100% "limit" is no cap at all.
        ChargePolicy.FixedLimit(100).allowsFullCharge shouldBe true
    }

    @Test
    fun `unknown stable id is rejected`() {
        ChargePolicy.fromStableId("fixed:nope") shouldBe null
        ChargePolicy.fromStableId("vendor-mode") shouldBe null
    }
}
