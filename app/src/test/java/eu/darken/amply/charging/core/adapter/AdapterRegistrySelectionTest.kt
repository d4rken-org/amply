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
        grapheneOs = GrapheneOsChargingAdapter(),
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

    private fun graphene(
        packages: Boolean = true,
        key: Boolean = true,
        systemUser: Boolean = true,
    ) = DeviceInfo(
        manufacturer = "Google",
        model = "Pixel 9 Pro XL",
        sdk = 37,
        fingerprint = "test",
        codename = "komodo",
        hasChargingOptimization = false,
        hasGrapheneOsPackages = packages,
        hasBatteryChargeLimit = key,
        isSystemUser = systemUser,
    )

    @Test
    fun `grapheneos selects its live adapter ahead of the pixel adapter`() {
        // Without the ordering, the Pixel probe (any Google/Pixel*) would swallow the device as a
        // matched-but-diagnostics-only stock Pixel.
        val selection = registry.select(graphene())
        selection.adapter?.id shouldBe "grapheneos-chargelimit-v1"
        selection.support.controlEnabled shouldBe true
    }

    @Test
    fun `grapheneos without the key stays on its adapter as diagnostics-only`() {
        val selection = registry.select(graphene(key = false))
        selection.adapter?.id shouldBe "grapheneos-chargelimit-v1"
        selection.support.controlEnabled shouldBe false
        selection.support.contributionWanted shouldBe true
    }

    @Test
    fun `a stock pixel with the key present is not treated as grapheneos`() {
        // Identity is packages-only: the key alone must fall through to the Pixel adapter.
        val selection = registry.select(
            graphene(packages = false).copy(hasChargingOptimization = true),
        )
        selection.adapter?.id shouldBe "google-pixel-lab-v1"
    }

    @Test
    fun `lineageos wins over the graphene adapter for lineage builds on pixel hardware`() {
        // Both are ROM-identity adapters; a LineageOS Pixel carries the Lineage feature and no
        // graphene packages, so ordering only matters for hypothetical both-signal devices — the
        // Lineage pair stays first.
        registry.select(lineageWithDeniedProperty("komodo", "Google")).adapter?.id shouldBe "lineageos-lab"
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
        feature: Boolean = false,
    ) = DeviceInfo(
        manufacturer = manufacturer,
        model = "TEST",
        sdk = 36,
        fingerprint = "test",
        codename = codename,
        lineageOsVersion = version,
        hasLineageFeature = feature,
        hasLineageSettingsProvider = provider,
        isSystemUser = systemUser,
    )

    /**
     * A real LineageOS device as the app actually sees it: `ro.lineage.*` is labelled
     * `custom_version_prop` and denied to `untrusted_app`, so the version reads back null and only the
     * `org.lineageos.android` system feature is observable. Verified on LineageOS 23.2 / Android 16
     * (oriole). Gating on the version alone silently routed these devices to the OEM adapters.
     */
    private fun lineageWithDeniedProperty(
        codename: String = "oriole",
        manufacturer: String = "Google",
    ) = lineage(codename = codename, manufacturer = manufacturer, version = null, feature = true)

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
        // Neither signal set → both Lineage adapters skip, OEM matching proceeds as before.
        registry.select(samsung(80000)).adapter?.id shouldBe "samsung-oneui8-v1"
    }

    @Test
    fun `a qualified lineageos device is selected when only the system feature is readable`() {
        val selection = registry.select(lineageWithDeniedProperty(codename = "oriole"))
        selection.adapter?.id shouldBe "lineageos-chargingcontrol-v1"
        selection.support.controlEnabled shouldBe true
    }

    @Test
    fun `an unqualified lineageos device falls to the lineage lab adapter without the version property`() {
        // The Pixel 6 / LineageOS 23.2 case: previously selected google-pixel-lab-v1, which hid the
        // contribution wizard (contributionWanted defaults false on the Pixel adapter) and pointed
        // "open battery settings" at Battery Saver instead of Battery.
        val selection = registry.select(lineageWithDeniedProperty(codename = "raven"))
        selection.adapter?.id shouldBe "lineageos-lab"
        selection.support.contributionWanted shouldBe true
    }

    @Test
    fun `the guided capture wizard is withheld on lineageos but still offered to OEM lab devices`() {
        // On LineageOS the keys are already mapped and live outside the wizard's capture set, so a guided run
        // always diffs to empty and cannot be delivered — the contribution goes through the direct report.
        val lineage = registry.select(lineageWithDeniedProperty(codename = "raven")).support
        lineage.contributionWanted shouldBe true
        lineage.guidedCaptureUseful shouldBe false

        // An unmapped OEM is the case the wizard exists for; it must be unaffected.
        val samsung = registry.select(samsung(oneUi = null)).support
        samsung.contributionWanted shouldBe true
        samsung.guidedCaptureUseful shouldBe true
    }

    @Test
    fun `lineageos on OEM hardware is never swallowed by an OEM adapter without the version property`() {
        registry.select(lineageWithDeniedProperty("gts9", "samsung")).adapter?.id shouldBe "lineageos-lab"
        registry.select(lineageWithDeniedProperty("munch", "Xiaomi")).adapter?.id shouldBe "lineageos-lab"
        registry.select(lineageWithDeniedProperty("salami", "OnePlus")).adapter?.id shouldBe "lineageos-lab"
        registry.select(lineageWithDeniedProperty("bluejay", "Google")).adapter?.id shouldBe "lineageos-lab"
    }
}
