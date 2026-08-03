package eu.darken.amply.charging.core

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * The predicate deciding whether unprivileged device metadata is worth a public device-support issue. It must stay
 * wider than "an adapter matched": the family matchers are manufacturer lists and property checks, so a device can
 * carry a real lead and still land in the registry's catch-all.
 */
class ChargingStateSupportLeadTest {

    private fun device(
        manufacturer: String = "BLU",
        hasChargingOptimization: Boolean = false,
        oneUiVersion: Int? = null,
        hyperOsVersion: Int? = null,
        oplusRomVersion: Int? = null,
        lineageOsVersion: String? = null,
        hasProtectBattery: Boolean = false,
        hasLineageSettingsProvider: Boolean = false,
    ) = DeviceInfo(
        manufacturer = manufacturer,
        model = "B1660V",
        sdk = 35,
        fingerprint = "test",
        hasChargingOptimization = hasChargingOptimization,
        oneUiVersion = oneUiVersion,
        hyperOsVersion = hyperOsVersion,
        oplusRomVersion = oplusRomVersion,
        lineageOsVersion = lineageOsVersion,
        hasProtectBattery = hasProtectBattery,
        hasLineageSettingsProvider = hasLineageSettingsProvider,
    )

    @Test
    fun `a device whose every probe came back empty has no lead`() {
        // The BLU B1660V of issue #42: stock Android, no marker, no key, no adapter.
        ChargingState(device = device(), adapterId = null).hasSupportLead shouldBe false
    }

    @Test
    fun `a matched adapter is a lead even when no marker was readable`() {
        // Samsung whose One UI version won't parse: the lab adapter still names the skin to check.
        ChargingState(device = device(manufacturer = "samsung"), adapterId = "samsung-lab")
            .hasSupportLead shouldBe true
    }

    @Test
    fun `adapterMatched follows the selected adapter`() {
        ChargingState(adapterId = "xiaomi-lab").adapterMatched shouldBe true
        ChargingState(adapterId = null).adapterMatched shouldBe false
    }

    @Test
    fun `a ROM marker is a lead even when no adapter matched`() {
        // Rebranded Oplus-family hardware: the ROM property is read globally, the matcher is a
        // manufacturer list, so this combination reaches the registry's catch-all.
        ChargingState(device = device(oplusRomVersion = 16), adapterId = null).hasSupportLead shouldBe true
        ChargingState(device = device(oneUiVersion = 9), adapterId = null).hasSupportLead shouldBe true
        ChargingState(device = device(hyperOsVersion = 3), adapterId = null).hasSupportLead shouldBe true
        ChargingState(device = device(lineageOsVersion = "23.0"), adapterId = null).hasSupportLead shouldBe true
    }

    @Test
    fun `a present protection key or provider is a lead even when no adapter matched`() {
        // A LineageOS derivative that ships the settings provider without the Lineage build property.
        ChargingState(device = device(hasLineageSettingsProvider = true), adapterId = null)
            .hasSupportLead shouldBe true
        ChargingState(device = device(hasProtectBattery = true), adapterId = null).hasSupportLead shouldBe true
        ChargingState(device = device(hasChargingOptimization = true), adapterId = null).hasSupportLead shouldBe true
    }
}
