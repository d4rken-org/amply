package eu.darken.amply.charging.core

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class DeviceInfoTest {

    private fun device(version: String? = null, feature: Boolean = false) = DeviceInfo(
        manufacturer = "Google",
        model = "Pixel 6",
        sdk = 36,
        fingerprint = "test",
        codename = "oriole",
        lineageOsVersion = version,
        hasLineageFeature = feature,
    )

    @Test
    fun `the system feature alone identifies lineageos`() {
        // The only signal available on a real build: ro.lineage.* is SELinux-denied to untrusted_app,
        // so the version is null even on LineageOS.
        device(version = null, feature = true).isLineageOs shouldBe true
    }

    @Test
    fun `the version property alone still identifies lineageos`() {
        // Derivatives that relabel the property but omit the feature must keep matching.
        device(version = "23.2", feature = false).isLineageOs shouldBe true
    }

    @Test
    fun `both signals present identifies lineageos`() {
        device(version = "23.2", feature = true).isLineageOs shouldBe true
    }

    @Test
    fun `neither signal means stock android`() {
        device(version = null, feature = false).isLineageOs shouldBe false
    }
}
