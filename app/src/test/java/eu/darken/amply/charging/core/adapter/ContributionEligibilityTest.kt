package eu.darken.amply.charging.core.adapter

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import eu.darken.amply.charging.core.DeviceInfo
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ContributionEligibilityTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val registry = AdapterRegistry(
        context = context,
        pixel = PixelChargingAdapter(),
        samsung = SamsungLabAdapter(),
        onePlus = OnePlusLabAdapter(),
    )

    private fun device(manufacturer: String, model: String = "X", sdk: Int = 35) = DeviceInfo(
        manufacturer = manufacturer,
        model = model,
        sdk = sdk,
        fingerprint = "fp",
        isPhone = true,
        hasChargingOptimization = true,
    )

    @Test
    fun `unknown OEM is a wanted contribution`() {
        val support = registry.select(device("Xiaomi")).support
        assertThat(support.matched).isFalse()
        assertThat(support.contributionWanted).isTrue()
    }

    @Test
    fun `samsung and oneplus diagnostics-only adapters want contributions`() {
        assertThat(registry.select(device("Samsung")).support.contributionWanted).isTrue()
        assertThat(registry.select(device("OnePlus")).support.contributionWanted).isTrue()
        assertThat(registry.select(device("Oppo")).support.contributionWanted).isTrue()
    }

    @Test
    fun `supported pixel does not solicit a contribution`() {
        val support = registry.select(device("Google", model = "Pixel 8")).support
        assertThat(support.matched).isTrue()
        assertThat(support.contributionWanted).isFalse()
    }

    @Test
    fun `gated pixel is still not a contribution target`() {
        // Old Pixel model: matched by the Pixel adapter but control-gated — a known limitation,
        // not a new device to add support for.
        val support = registry.select(device("Google", model = "Pixel 4", sdk = 33)).support
        assertThat(support.contributionWanted).isFalse()
    }
}
