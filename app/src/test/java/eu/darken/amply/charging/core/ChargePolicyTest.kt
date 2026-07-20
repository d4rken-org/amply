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
        ).forEach { policy ->
            ChargePolicy.fromStableId(policy.stableId) shouldBe policy
        }
    }

    @Test
    fun `unknown stable id is rejected`() {
        ChargePolicy.fromStableId("fixed:nope") shouldBe null
        ChargePolicy.fromStableId("vendor-mode") shouldBe null
    }
}
