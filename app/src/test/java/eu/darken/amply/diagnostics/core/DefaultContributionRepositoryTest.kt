package eu.darken.amply.diagnostics.core

import androidx.test.core.app.ApplicationProvider
import eu.darken.amply.charging.core.access.BackendStatus
import eu.darken.amply.charging.core.access.LineageChargeReadout
import eu.darken.amply.charging.core.access.LineageChargeReader
import eu.darken.amply.charging.core.access.NamespaceSnapshot
import eu.darken.amply.charging.core.access.SettingNamespace
import eu.darken.amply.charging.core.access.SettingsSnapshotSource
import eu.darken.amply.charging.core.adapter.AdapterRegistry
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
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DefaultContributionRepositoryTest {

    private class RecordingSource : SettingsSnapshotSource {
        val requested = mutableListOf<SettingNamespace>()
        override suspend fun status() = BackendStatus(available = true, granted = true, detail = "test".toCaString())
        override suspend fun snapshot(namespace: SettingNamespace): NamespaceSnapshot {
            requested += namespace
            return NamespaceSnapshot.Success(emptyMap())
        }
    }

    private val stubReader = object : LineageChargeReader {
        override suspend fun readChargeControl() = LineageChargeReadout.Unreadable("unused".toCaString())
    }

    private val registry = AdapterRegistry(
        context = ApplicationProvider.getApplicationContext(),
        lineage = LineageChargingAdapter(stubReader),
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

    @Test
    fun `capture requests exactly the three AOSP namespaces, never the lineage provider`() = runTest {
        val source = RecordingSource()
        val repo = DefaultContributionRepository(ApplicationProvider.getApplicationContext(), source, registry)

        val result = repo.captureSnapshot()

        result.shouldBeInstanceOf<CaptureResult.Success>()
        source.requested shouldContainExactly listOf(
            SettingNamespace.SECURE,
            SettingNamespace.GLOBAL,
            SettingNamespace.SYSTEM,
        )
        source.requested shouldNotContain SettingNamespace.LINEAGE_SYSTEM
    }
}
