package eu.darken.amply.charging.core

import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ResolveInfo
import android.os.Build
import android.provider.Settings
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ApplicationProvider
import eu.darken.amply.battery.core.BatteryReader
import eu.darken.amply.battery.core.BatteryUnitCalibration
import eu.darken.amply.charging.core.access.AccessResolver
import eu.darken.amply.charging.core.access.DirectSettingsBackend
import eu.darken.amply.charging.core.access.LineageSettingsClient
import eu.darken.amply.charging.core.access.ShizukuSettingsBackend
import eu.darken.amply.charging.core.access.shizuku.ShizukuController
import eu.darken.amply.charging.core.access.shizuku.ShizukuInstallationDetector
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
import eu.darken.amply.charging.core.adapter.XiaomiHyperOs3ChargingAdapter
import eu.darken.amply.charging.core.adapter.XiaomiLabAdapter
import eu.darken.amply.charging.core.enforcement.BuildIdentitySource
import eu.darken.amply.charging.core.enforcement.EnforcementEvidenceStore
import eu.darken.amply.charging.core.qualification.QualificationEvidenceStore
import eu.darken.amply.charging.core.qualification.QualificationRunStore
import eu.darken.amply.common.AppDataStore
import eu.darken.amply.common.serialization.SerializationModule
import eu.darken.amply.fullcharge.core.FullChargeStore
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import java.io.File
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.util.ReflectionHelpers

/**
 * A device Amply carries no adapter for (HONOR/MagicOS here, but equally Motorola, Nothing, Sony, Fairphone,
 * Vivo, Tecno) must still be pointed at the OEM's battery screen. It used to land on Battery Saver: the
 * repository returned null for a null adapter and the only caller substituted Battery Saver outright, so
 * `POWER_USAGE_SUMMARY` was never tried even though every lab adapter prefers it.
 */
@RunWith(RobolectricTestRunner::class)
class UnmappedDeviceSettingsIntentTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val storeScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private lateinit var repository: ChargingRepository

    // The enforcement gate is irrelevant to this test — an unmapped device has no adapter at all, so no
    // tier is ever resolved. These exist only to satisfy the repository's constructor.
    private val buildIdentity = object : BuildIdentitySource {
        override fun current() = "test-build"
    }

    @Before
    fun setup() {
        // An unmapped OEM: no adapter's probe matches, so AdapterRegistry.select() yields adapter = null.
        ReflectionHelpers.setStaticField(Build::class.java, "MANUFACTURER", "HONOR")
        ReflectionHelpers.setStaticField(Build::class.java, "MODEL", "BKQ-N49")
        ReflectionHelpers.setStaticField(Build::class.java, "DEVICE", "HNBKQ")

        val appDataStore = AppDataStore(
            PreferenceDataStoreFactory.create(scope = storeScope) {
                File(tempFolder.root, "test.preferences_pb")
            },
        )
        val shizukuController = ShizukuController(context, ShizukuInstallationDetector(context))
        repository = ChargingRepository(
            context = context,
            registry = AdapterRegistry(
                context = context,
                lineage = LineageChargingAdapter(LineageSettingsClient(context)),
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
            ),
            accessResolver = AccessResolver(
                direct = DirectSettingsBackend(context),
                shizuku = ShizukuSettingsBackend(shizukuController),
            ),
            preferences = ChargingPreferences(appDataStore, SerializationModule.json()),
            shizukuController = shizukuController,
            settleScheduler = object : SettleScheduler {
                override fun schedule(requestedAtMillis: Long) = Unit
            },
            batteryReader = BatteryReader(context, BatteryUnitCalibration(context)),
            evidenceStore = EnforcementEvidenceStore(appDataStore, buildIdentity, SerializationModule.json()),
            qualificationStore = QualificationEvidenceStore(appDataStore, buildIdentity, SerializationModule.json()),
            runStore = QualificationRunStore(appDataStore, SerializationModule.json()),
            fullChargeStore = FullChargeStore(appDataStore, SerializationModule.json()),
            buildIdentity = buildIdentity,
        )
    }

    @After
    fun teardown() {
        storeScope.cancel()
    }

    @Test
    fun `an unmapped device really has no adapter`() {
        repository.currentAdapter() shouldBe null
    }

    @Test
    fun `an unmapped device is pointed at the battery-usage screen, not battery saver`() {
        registerActivityFor(Intent.ACTION_POWER_USAGE_SUMMARY)

        repository.nativeSettingsIntent().action shouldBe Intent.ACTION_POWER_USAGE_SUMMARY
    }

    @Test
    fun `battery saver stays the fallback when the battery-usage screen is absent`() {
        // Nothing registered for POWER_USAGE_SUMMARY, e.g. a ROM that ships no battery-usage activity.
        repository.nativeSettingsIntent().action shouldBe Settings.ACTION_BATTERY_SAVER_SETTINGS
    }

    private fun registerActivityFor(action: String) {
        val resolveInfo = ResolveInfo().apply {
            activityInfo = ActivityInfo().apply {
                packageName = "com.example.settings"
                name = "BatteryActivity"
            }
        }
        shadowOf(context.packageManager).addResolveInfoForIntent(Intent(action), resolveInfo)
    }
}
