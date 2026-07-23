package eu.darken.amply.charging.core.adapter

import androidx.test.core.app.ApplicationProvider
import eu.darken.amply.charging.core.DeviceInfo
import eu.darken.amply.charging.core.access.LineageChargeReadout
import eu.darken.amply.charging.core.access.LineageChargeReader
import eu.darken.amply.common.ca.toCaString
import io.kotest.matchers.shouldBe
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Registry-level selection: the first matched adapter wins, so the Samsung adapters' matched
 * flags must be version-family-specific or they would swallow the lab fallthrough.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AdapterRegistrySelectionTest {

    // Reads aren't exercised at the registry level (only probe); a stub reader suffices, and the test seam
    // supplies a qualified codename so the live-selection/ordering paths stay covered.
    private val stubReader = object : LineageChargeReader {
        override suspend fun readChargeControl() = LineageChargeReadout.Unreadable("unused".toCaString())
    }

    private val registry = AdapterRegistry(
        context = ApplicationProvider.getApplicationContext(),
        lineage = LineageChargingAdapter(stubReader, setOf("oriole")),
        lineageLab = LineageLabAdapter(),
        pixel = PixelChargingAdapter(),
        samsungModern = SamsungModernChargingAdapter(),
        samsungLegacy = SamsungLegacyChargingAdapter(),
        samsungLab = SamsungLabAdapter(),
        xiaomi = XiaomiChargingAdapter(),
        xiaomiLab = XiaomiLabAdapter(),
        onePlus = OnePlusChargingAdapter(),
        onePlusLab = OnePlusLabAdapter(),
    )

    private fun samsung(oneUi: Int?) = DeviceInfo(
        manufacturer = "samsung",
        model = "SM-TEST",
        sdk = 36,
        fingerprint = "test",
        oneUiVersion = oneUi,
        hasProtectBattery = true,
        isSystemUser = true,
    )

    @Test
    fun `one ui 8 selects the modern adapter with control`() {
        val selection = registry.select(samsung(80000))
        selection.adapter?.id shouldBe "samsung-oneui8-v1"
        selection.support.controlEnabled shouldBe true
    }

    @Test
    fun `one ui 4 and 5 select the legacy adapter`() {
        registry.select(samsung(40100)).adapter?.id shouldBe "samsung-legacy-v1"
        registry.select(samsung(50100)).adapter?.id shouldBe "samsung-legacy-v1"
    }

    @Test
    fun `unverified one ui versions fall through to the diagnostics lab adapter`() {
        listOf(30000, 60000, 61000, 70000, 90000, null).forEach { oneUi ->
            val selection = registry.select(samsung(oneUi))
            selection.adapter?.id shouldBe "samsung-lab"
            selection.support.controlEnabled shouldBe false
        }
    }

    @Test
    fun `pixel still selects the pixel adapter`() {
        val selection = registry.select(
            DeviceInfo("Google", "Pixel 8", 36, "test", hasChargingOptimization = true),
        )
        selection.adapter?.id shouldBe "google-pixel-lab-v1"
    }

    @Test
    fun `any HyperOS 2 xiaomi selects the live adapter`() {
        val selection = registry.select(
            DeviceInfo("Xiaomi", "2306EPN60G", 35, "test", hyperOsVersion = 2, isSystemUser = true),
        )
        selection.adapter?.id shouldBe "xiaomi-hyperos2-v1"
        selection.support.controlEnabled shouldBe true

        // A different HyperOS 2 model selects the live adapter too (ROM-version gate, not model).
        registry.select(
            DeviceInfo("Xiaomi", "23078PND5G", 35, "test", hyperOsVersion = 2, isSystemUser = true),
        ).adapter?.id shouldBe "xiaomi-hyperos2-v1"
    }

    @Test
    fun `non-HyperOS-2 xiaomi devices fall through to the xiaomi lab adapter`() {
        registry.select(
            DeviceInfo("Xiaomi", "2306EPN60G", 35, "test", hyperOsVersion = 1),
        ).adapter?.id shouldBe "xiaomi-lab"
        registry.select(
            DeviceInfo("Xiaomi", "2306EPN60G", 35, "test", hyperOsVersion = 3),
        ).adapter?.id shouldBe "xiaomi-lab"
        registry.select(
            DeviceInfo("Xiaomi", "M2101K6G", 33, "test"),
        ).adapter?.id shouldBe "xiaomi-lab"
    }

    @Test
    fun `ColorOS 15 oplus devices select the live adapter across the family`() {
        listOf("OnePlus", "OPPO", "realme").forEach { manufacturer ->
            val selection = registry.select(
                DeviceInfo(manufacturer, "CPH2621", 35, "test", oplusRomVersion = 15, isSystemUser = true),
            )
            selection.adapter?.id shouldBe "oplus-coloros15-v1"
            selection.support.controlEnabled shouldBe true
        }
    }

    @Test
    fun `unqualified oplus devices fall through to the oneplus lab adapter`() {
        registry.select(
            DeviceInfo("OnePlus", "CPH2621", 35, "test", oplusRomVersion = 14),
        ).adapter?.id shouldBe "oneplus-lab"
        // No oplus ROM property (older device / non-Oplus that still reports the brand) → lab.
        registry.select(
            DeviceInfo("OnePlus", "CPH2621", 34, "test"),
        ).adapter?.id shouldBe "oneplus-lab"
        registry.select(
            DeviceInfo("realme", "RMX3999", 34, "test"),
        ).adapter?.id shouldBe "oneplus-lab"
    }

    private fun lineage(
        codename: String = "oriole",
        manufacturer: String = "Google",
        provider: Boolean = true,
        systemUser: Boolean = true,
        version: String? = "23.2",
    ) = DeviceInfo(
        manufacturer = manufacturer,
        model = "TEST",
        sdk = 36,
        fingerprint = "test",
        codename = codename,
        lineageOsVersion = version,
        hasLineageSettingsProvider = provider,
        isSystemUser = systemUser,
    )

    @Test
    fun `a qualified lineageos codename selects the live adapter with control`() {
        val selection = registry.select(lineage(codename = "oriole"))
        selection.adapter?.id shouldBe "lineageos-chargingcontrol-v1"
        selection.support.controlEnabled shouldBe true
    }

    @Test
    fun `a qualified lineageos device without the provider matches but disables control`() {
        val selection = registry.select(lineage(codename = "oriole", provider = false))
        selection.adapter?.id shouldBe "lineageos-chargingcontrol-v1"
        selection.support.controlEnabled shouldBe false
    }

    @Test
    fun `a secondary user on a qualified lineageos device disables control`() {
        val selection = registry.select(lineage(codename = "oriole", systemUser = false))
        selection.adapter?.id shouldBe "lineageos-chargingcontrol-v1"
        selection.support.controlEnabled shouldBe false
    }

    @Test
    fun `an unqualified lineageos codename falls through to the lineage lab adapter`() {
        val selection = registry.select(lineage(codename = "raven")) // Pixel 6 Pro, not yet qualified
        selection.adapter?.id shouldBe "lineageos-lab"
        selection.support.controlEnabled shouldBe false
    }

    @Test
    fun `a lineageos build on OEM hardware is handled by lineage, never the OEM lab adapter`() {
        // The Lineage adapters precede all OEM adapters, so a custom-ROM build on Samsung/Xiaomi/OnePlus
        // hardware is never swallowed by a manufacturer-based lab adapter.
        registry.select(lineage(codename = "gts9", manufacturer = "samsung")).adapter?.id shouldBe "lineageos-lab"
        registry.select(lineage(codename = "munch", manufacturer = "Xiaomi")).adapter?.id shouldBe "lineageos-lab"
        registry.select(lineage(codename = "salami", manufacturer = "OnePlus")).adapter?.id shouldBe "lineageos-lab"
    }

    @Test
    fun `a stock device is unaffected by the lineage adapters`() {
        // lineageOsVersion == null → both Lineage adapters skip, OEM matching proceeds as before.
        registry.select(samsung(80000)).adapter?.id shouldBe "samsung-oneui8-v1"
    }
}
