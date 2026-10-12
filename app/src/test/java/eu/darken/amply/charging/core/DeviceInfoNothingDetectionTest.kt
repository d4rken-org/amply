package eu.darken.amply.charging.core

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import eu.darken.amply.charging.core.access.LineageChargeReadout
import eu.darken.amply.charging.core.access.LineageChargeReader
import eu.darken.amply.charging.core.adapter.AdapterRegistry
import eu.darken.amply.charging.core.adapter.GrapheneOsChargingAdapter
import eu.darken.amply.charging.core.adapter.LineageChargingAdapter
import eu.darken.amply.charging.core.adapter.LineageLabAdapter
import eu.darken.amply.charging.core.adapter.NothingChargingAdapter
import eu.darken.amply.charging.core.adapter.OnePlusChargingAdapter
import eu.darken.amply.charging.core.adapter.OnePlusLabAdapter
import eu.darken.amply.charging.core.adapter.PixelChargingAdapter
import eu.darken.amply.charging.core.adapter.SamsungLabAdapter
import eu.darken.amply.charging.core.adapter.SamsungLegacyChargingAdapter
import eu.darken.amply.charging.core.adapter.SamsungModernChargingAdapter
import eu.darken.amply.charging.core.adapter.XiaomiChargingAdapter
import eu.darken.amply.charging.core.adapter.XiaomiHyperOs3ChargingAdapter
import eu.darken.amply.charging.core.adapter.XiaomiLabAdapter
import eu.darken.amply.charging.core.enforcement.EnforcementEvidenceState
import eu.darken.amply.charging.core.qualification.QualificationEvidenceState
import eu.darken.amply.common.ca.toCaString
import io.kotest.matchers.shouldBe
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowBuild

/** Pins that [DeviceInfo.current] reads the Nothing OS feature marker from PackageManager. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class DeviceInfoNothingDetectionTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private val registry by lazy {
        val stubReader = object : LineageChargeReader {
            override suspend fun readChargeControl() = LineageChargeReadout.Unreadable("unused".toCaString())
        }
        AdapterRegistry(
            context = context,
            lineage = LineageChargingAdapter(stubReader),
            lineageLab = LineageLabAdapter(),
            grapheneOs = GrapheneOsChargingAdapter(),
            pixel = PixelChargingAdapter(),
            samsungModern = SamsungModernChargingAdapter(),
            samsungLegacy = SamsungLegacyChargingAdapter(),
            samsungLab = SamsungLabAdapter(),
            xiaomi = XiaomiChargingAdapter(),
            xiaomiHyperOs3 = XiaomiHyperOs3ChargingAdapter(),
            xiaomiLab = XiaomiLabAdapter(),
            onePlus = OnePlusChargingAdapter(),
            onePlusLab = OnePlusLabAdapter(),
            nothing = NothingChargingAdapter(),
        )
    }

    @Test
    fun `the nothing os system feature is read from PackageManager`() {
        shadowOf(context.packageManager).setSystemFeature(DeviceInfo.FEATURE_NOTHING_OS, true)

        DeviceInfo.current(context).hasNothingFeature shouldBe true
    }

    @Test
    fun `a device without the feature is not nothing os`() {
        shadowOf(context.packageManager).setSystemFeature(DeviceInfo.FEATURE_NOTHING_OS, false)

        DeviceInfo.current(context).hasNothingFeature shouldBe false
    }

    @Test
    fun `the context-less placeholder has no nothing os feature`() {
        DeviceInfo.current().hasNothingFeature shouldBe false
    }

    @Test
    fun `the qualified nothing phone reaches the nothing adapter end to end`() {
        ShadowBuild.setManufacturer("Nothing")
        ShadowBuild.setModel("A059P")
        ShadowBuild.setDevice("Asteroids")
        shadowOf(context.packageManager).setSystemFeature(DeviceInfo.FEATURE_NOTHING_OS, true)

        val selection = registry.select(
            DeviceInfo.current(context),
            EnforcementEvidenceState.Absent,
            QualificationEvidenceState.Absent,
        )

        selection.adapter?.id shouldBe "nothing-os4-v1"
        selection.support.controlEnabled shouldBe true
    }
}
