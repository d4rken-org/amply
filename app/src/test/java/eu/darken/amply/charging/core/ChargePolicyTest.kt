package eu.darken.amply.charging.core

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ChargePolicyTest {
    @Test
    fun `stable ids round trip`() {
        listOf(
            ChargePolicy.FixedLimit(80),
            ChargePolicy.Adaptive,
            ChargePolicy.Unrestricted,
        ).forEach { policy ->
            assertThat(ChargePolicy.fromStableId(policy.stableId)).isEqualTo(policy)
        }
    }

    @Test
    fun `unknown stable id is rejected`() {
        assertThat(ChargePolicy.fromStableId("fixed:nope")).isNull()
        assertThat(ChargePolicy.fromStableId("vendor-mode")).isNull()
    }
}
