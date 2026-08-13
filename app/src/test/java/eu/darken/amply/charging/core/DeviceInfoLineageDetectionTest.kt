package eu.darken.amply.charging.core

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import eu.darken.amply.charging.core.access.LineageChargeReadout
import eu.darken.amply.charging.core.access.LineageChargeReader
import eu.darken.amply.charging.core.adapter.AdapterRegistry
import eu.darken.amply.charging.core.adapter.GrapheneOsChargingAdapter
import eu.darken.amply.charging.core.adapter.LineageChargingAdapter
import eu.darken.amply.charging.core.adapter.LineageLabAdapter
import eu.darken.amply.charging.core.adapter.OnePlusChargingAdapter
import eu.darken.amply.charging.core.adapter.OnePlusLabAdapter
import eu.darken.amply.charging.core.adapter.PixelChargingAdapter
import eu.darken.amply.charging.core.adapter.SamsungLabAdapter
import eu.darken.amply.charging.core.adapter.SamsungLegacyChargingAdapter
import eu.darken.amply.charging.core.adapter.SamsungModernChargingAdapter
import eu.darken.amply.charging.core.adapter.XiaomiChargingAdapter
import eu.darken.amply.charging.core.adapter.XiaomiLabAdapter
import eu.darken.amply.common.ca.toCaString
import io.kotest.matchers.shouldBe
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Pins the seam the pure tests cannot reach: that [DeviceInfo.current] actually asks PackageManager for the right
 * feature string. The shipped bug was invisible to unit tests precisely because they injected `lineageOsVersion`
 * straight into [DeviceInfo] — every gate looked correct while the real device read nothing.
 *
 * `lineageOsVersion` is left unset here on purpose: that is the real-device shape, since `ro.lineage.*` is
 * SELinux-denied to `untrusted_app` and reads back empty.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class DeviceInfoLineageDetectionTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private val registry by lazy {
        val stubReader = object : LineageChargeReader {
            override suspend fun readChargeControl() = LineageChargeReadout.Unreadable("unused".toCaString())
        }
        AdapterRegistry(
            context = context,
            lineage = LineageChargingAdapter(stubReader, setOf("some-qualified-codename")),
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
    }

    @Test
    fun `the lineage system feature is read from PackageManager`() {
        shadowOf(context.packageManager).setSystemFeature(DeviceInfo.FEATURE_LINEAGE_OS, true)

        val device = DeviceInfo.current(context)

        device.hasLineageFeature shouldBe true
        device.lineageOsVersion shouldBe null
        device.isLineageOs shouldBe true
    }

    @Test
    fun `a device without the feature is not lineageos`() {
        shadowOf(context.packageManager).setSystemFeature(DeviceInfo.FEATURE_LINEAGE_OS, false)

        val device = DeviceInfo.current(context)

        device.hasLineageFeature shouldBe false
        device.isLineageOs shouldBe false
    }

    @Test
    fun `an unqualified lineageos device reaches the lineage lab adapter end to end`() {
        // The whole chain the bug broke: PackageManager → DeviceInfo.current → AdapterRegistry.
        shadowOf(context.packageManager).setSystemFeature(DeviceInfo.FEATURE_LINEAGE_OS, true)

        val selection = registry.select(DeviceInfo.current(context))

        selection.adapter?.id shouldBe "lineageos-lab"
        selection.support.contributionWanted shouldBe true
    }

    @Test
    fun `without the feature the same device is not routed to a lineage adapter`() {
        shadowOf(context.packageManager).setSystemFeature(DeviceInfo.FEATURE_LINEAGE_OS, false)

        val selection = registry.select(DeviceInfo.current(context))

        selection.adapter?.id shouldBe null
    }
}
