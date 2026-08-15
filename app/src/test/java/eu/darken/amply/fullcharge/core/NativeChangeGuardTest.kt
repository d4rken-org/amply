package eu.darken.amply.fullcharge.core

import eu.darken.amply.charging.core.BackendKind
import eu.darken.amply.charging.core.ChargeObservation
import eu.darken.amply.charging.core.ChargePolicy
import eu.darken.amply.common.ca.toCaString
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class NativeChangeGuardTest {

    private val override = ChargePolicy.Unrestricted

    private fun verified(policy: ChargePolicy, backend: BackendKind = BackendKind.DIRECT_WSS) =
        ChargeObservation.Verified(policy, backend)

    @Test
    fun `readback matching the override is noise`() {
        NativeChangeGuard.shouldCancel(verified(ChargePolicy.Unrestricted), override) shouldBe false
        // The backend that produced the readback is irrelevant to the comparison.
        NativeChangeGuard.shouldCancel(
            verified(ChargePolicy.Unrestricted, BackendKind.SHIZUKU),
            override,
        ) shouldBe false
    }

    @Test
    fun `non-Unrestricted overrides compare by policy equality`() {
        // Samsung modern overrides with PauseAtFull instead of Unrestricted.
        NativeChangeGuard.shouldCancel(
            verified(ChargePolicy.PauseAtFull),
            ChargePolicy.PauseAtFull,
        ) shouldBe false
        NativeChangeGuard.shouldCancel(
            verified(ChargePolicy.FixedLimit(80)),
            ChargePolicy.FixedLimit(80),
        ) shouldBe false
        NativeChangeGuard.shouldCancel(
            verified(ChargePolicy.FixedLimit(85)),
            ChargePolicy.FixedLimit(80),
        ) shouldBe true
    }

    @Test
    fun `a different verified policy is a real native change`() {
        NativeChangeGuard.shouldCancel(verified(ChargePolicy.FixedLimit(80)), override) shouldBe true
        NativeChangeGuard.shouldCancel(verified(ChargePolicy.Adaptive), override) shouldBe true
    }

    @Test
    fun `unrecognized and unreadable values cancel`() {
        NativeChangeGuard.shouldCancel(
            ChargeObservation.Unknown("foreign value".toCaString(), unrecognizedValue = true),
            override,
        ) shouldBe true
        NativeChangeGuard.shouldCancel(
            ChargeObservation.Unknown("unreadable".toCaString()),
            override,
        ) shouldBe true
    }

    @Test
    fun `no sync readback cancels like before`() {
        // Async adapters (Pixel) return null — the pre-guard blanket behavior stays.
        NativeChangeGuard.shouldCancel(null, override) shouldBe true
    }

    @Test
    fun `only verified readback can rescue the session`() {
        // A last-requested claim is Amply's own journal, not an observation of the key.
        NativeChangeGuard.shouldCancel(
            ChargeObservation.LastRequested(ChargePolicy.Unrestricted),
            override,
        ) shouldBe true
    }
}
