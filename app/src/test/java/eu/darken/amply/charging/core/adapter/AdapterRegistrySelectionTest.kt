package eu.darken.amply.charging.core.adapter

import androidx.test.core.app.ApplicationProvider
import eu.darken.amply.charging.core.DeviceInfo
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

    private val registry = AdapterRegistry(
        context = ApplicationProvider.getApplicationContext(),
        pixel = PixelChargingAdapter(),
        samsungModern = SamsungModernChargingAdapter(),
        samsungLegacy = SamsungLegacyChargingAdapter(),
        samsungLab = SamsungLabAdapter(),
        xiaomi = XiaomiChargingAdapter(),
        xiaomiLab = XiaomiLabAdapter(),
        onePlus = OnePlusLabAdapter(),
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
    fun `oneplus falls through to its lab adapter`() {
        registry.select(
            DeviceInfo("OnePlus", "CPH2621", 34, "test"),
        ).adapter?.id shouldBe "oneplus-lab"
    }
}
