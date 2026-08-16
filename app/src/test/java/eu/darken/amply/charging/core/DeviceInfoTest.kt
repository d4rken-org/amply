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

    @Test
    fun `grapheneos identity comes from its packages alone`() {
        device().copy(hasGrapheneOsPackages = true).isGrapheneOs shouldBe true
        device().isGrapheneOs shouldBe false
    }

    @Test
    fun `the charge-limit key alone is never grapheneos identity`() {
        // The adapter is registered ahead of the live Pixel adapter; a future stock Pixel shipping
        // a same-named key must not be swallowed as GrapheneOS.
        device().copy(batteryChargeLimitProbe = SettingProbe.PRESENT).isGrapheneOs shouldBe false
    }

    @Test
    fun `detection probes fail closed without a context`() {
        val info = DeviceInfo.current(context = null)
        info.hasGrapheneOsPackages shouldBe false
        info.hasBatteryChargeLimit shouldBe false
        info.batteryChargeLimitProbe shouldBe SettingProbe.ABSENT
        info.protectBatteryProbe shouldBe SettingProbe.ABSENT
    }

    @Test
    fun `a refused read is classified as denied, not as absence`() {
        probeSetting { throw SecurityException("not @Readable") } shouldBe SettingProbe.READ_DENIED
    }

    @Test
    fun `a read returning nothing is absence, and any other failure fails closed the same way`() {
        probeSetting { null } shouldBe SettingProbe.ABSENT
        probeSetting { "1" } shouldBe SettingProbe.PRESENT
        // Matches the previous runCatching{…}.getOrDefault(false) behaviour for non-security failures.
        probeSetting { throw IllegalStateException("provider died") } shouldBe SettingProbe.ABSENT
    }

    @Test
    fun `a denied probe never opens a gate`() {
        // The whole point of the tri-state is that it stays visible in reports WITHOUT relaxing any gate:
        // hasProtectBattery gates device-wide Samsung writes and must read false unless the key was read back.
        device().copy(protectBatteryProbe = SettingProbe.READ_DENIED).hasProtectBattery shouldBe false
        device().copy(protectBatteryProbe = SettingProbe.ABSENT).hasProtectBattery shouldBe false
        device().copy(protectBatteryProbe = SettingProbe.PRESENT).hasProtectBattery shouldBe true
    }
}
