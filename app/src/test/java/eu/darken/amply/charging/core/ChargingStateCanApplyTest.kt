package eu.darken.amply.charging.core

import eu.darken.amply.charging.core.access.AccessSnapshot
import eu.darken.amply.charging.core.access.BackendStatus
import eu.darken.amply.common.ca.toCaString
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class ChargingStateCanApplyTest {

    private fun status(ready: Boolean) = BackendStatus(available = ready, granted = ready, detail = "".toCaString())

    private fun state(
        controlEnabled: Boolean = true,
        writeRequiresShizuku: Boolean = false,
        directReady: Boolean = false,
        shizukuReady: Boolean = false,
    ) = ChargingState(
        controlEnabled = controlEnabled,
        writeRequiresShizuku = writeRequiresShizuku,
        access = AccessSnapshot(direct = status(directReady), shizuku = status(shizukuReady)),
    )

    @Test
    fun `WSS adapters can apply with either backend`() {
        state(directReady = true).canApply shouldBe true
        state(shizukuReady = true).canApply shouldBe true
        state().canApply shouldBe false
    }

    @Test
    fun `Shizuku-only adapters require Shizuku regardless of WSS`() {
        // WSS granted but Shizuku not connected → cannot apply (system-namespace write).
        state(writeRequiresShizuku = true, directReady = true).canApply shouldBe false
        state(writeRequiresShizuku = true, shizukuReady = true).canApply shouldBe true
        state(writeRequiresShizuku = true, directReady = true, shizukuReady = true).canApply shouldBe true
    }

    @Test
    fun `an uncontrollable device never applies`() {
        state(controlEnabled = false, shizukuReady = true).canApply shouldBe false
        state(controlEnabled = false, writeRequiresShizuku = true, shizukuReady = true).canApply shouldBe false
    }
}
