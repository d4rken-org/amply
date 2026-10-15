package eu.darken.amply.charging.core

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import eu.darken.amply.charging.core.access.LineageChargeReadout
import eu.darken.amply.charging.core.access.LineageChargeReader
import eu.darken.amply.charging.core.adapter.AdapterRegistry
import eu.darken.amply.charging.core.adapter.GrapheneOsChargingAdapter
import eu.darken.amply.charging.core.adapter.LenovoChargingAdapter
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
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowBuild

/** Pins that the Build fields [DeviceInfo.current] reads are the ones the Lenovo gate's (codename, model) pair names. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class DeviceInfoLenovoSelectionTest {

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
            lenovo = LenovoChargingAdapter(),
        )
    }

    @Test
    fun `the qualified lenovo tablet reaches the lenovo adapter end to end`() {
        // Build values as the contributor's PB-6505M reports them.
        ShadowBuild.setManufacturer("LENOVO")
        ShadowBuild.setModel("Lenovo PB-6505M")
        ShadowBuild.setDevice("PB-6505M")

        val selection = registry.select(
            DeviceInfo.current(context),
            EnforcementEvidenceState.Absent,
            QualificationEvidenceState.Absent,
        )

        selection.adapter?.id shouldBe "lenovo-batteryprotection-v1"
        selection.support.controlEnabled shouldBe true
    }
}
