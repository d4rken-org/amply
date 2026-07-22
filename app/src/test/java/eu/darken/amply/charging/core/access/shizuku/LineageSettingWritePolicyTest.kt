package eu.darken.amply.charging.core.access.shizuku

import eu.darken.amply.charging.core.access.LineageChargeReadout
import eu.darken.amply.charging.core.access.LineageChargeReader
import eu.darken.amply.charging.core.adapter.LineageChargingAdapter
import eu.darken.amply.common.ca.toCaString
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test

class LineageSettingWritePolicyTest {

    @Test
    fun `admits every canonical in-domain value`() {
        LineageSettingWritePolicy.validate(LineageSettingWritePolicy.KEY_ENABLED, "0")
        LineageSettingWritePolicy.validate(LineageSettingWritePolicy.KEY_ENABLED, "1")
        LineageSettingWritePolicy.validate(LineageSettingWritePolicy.KEY_MODE, "3")
        listOf("70", "75", "80", "85", "90", "95").forEach {
            LineageSettingWritePolicy.validate(LineageSettingWritePolicy.KEY_LIMIT, it)
        }
    }

    @Test
    fun `rejects a non-allowlisted key`() {
        shouldThrow<IllegalArgumentException> {
            LineageSettingWritePolicy.validate("charging_control_start_time", "0")
        }.message shouldContain "allowlisted"
    }

    @Test
    fun `rejects out-of-domain and non-canonical values`() {
        // Schedule modes / the invalid mode 0 are refused — only the hard cap (mode 3) is writable.
        shouldThrow<IllegalArgumentException> { LineageSettingWritePolicy.validate(LineageSettingWritePolicy.KEY_MODE, "1") }
        shouldThrow<IllegalArgumentException> { LineageSettingWritePolicy.validate(LineageSettingWritePolicy.KEY_MODE, "0") }
        // Out-of-range and off-tick limits.
        shouldThrow<IllegalArgumentException> { LineageSettingWritePolicy.validate(LineageSettingWritePolicy.KEY_LIMIT, "101") }
        shouldThrow<IllegalArgumentException> { LineageSettingWritePolicy.validate(LineageSettingWritePolicy.KEY_LIMIT, "72") }
        // Non-canonical numeric forms (exact-string domains reject these for free).
        shouldThrow<IllegalArgumentException> { LineageSettingWritePolicy.validate(LineageSettingWritePolicy.KEY_LIMIT, "080") }
        shouldThrow<IllegalArgumentException> { LineageSettingWritePolicy.validate(LineageSettingWritePolicy.KEY_ENABLED, "2") }
    }

    @Test
    fun `every adapter-emitted mutation is inside the write domain`() {
        val stubReader = object : LineageChargeReader {
            override suspend fun readChargeControl() = LineageChargeReadout.Unreadable("unused".toCaString())
        }
        val adapter = LineageChargingAdapter(stubReader)
        adapter.supportedPolicies.forEach { policy ->
            adapter.mutationsFor(policy)?.forEach { mutation ->
                // Throws (failing the test) if the adapter could emit a write the boundary rejects.
                LineageSettingWritePolicy.validate(mutation.key, mutation.value)
            }
        }
    }
}
