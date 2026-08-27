package eu.darken.amply.charging.core

import android.Manifest
import android.app.Application
import android.content.Context
import android.content.pm.ProviderInfo
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
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import java.io.File

/**
 * The three failure publications in the apply path republish with `state.value.copy(...)`, so every
 * standing claim they do not name survives them. [ChargingState.capAwaitsHardwareConfirmation] is
 * such a claim, and the dashboard renders its note off that field alone — a stale `true` would go on
 * saying "set, waiting for the hardware" about a policy that was never written.
 *
 * The fixture is a LineageOS candidate build because one device reaches all three sites: the gate
 * refuses a fresh apply (Unsupported), the ungated restore finds no usable backend once WSS is
 * revoked (NeedsSetup), and with WSS granted it reaches the adapter and fails there — WSS cannot
 * write the Lineage provider (Unknown). The observation assertions are what pin each case to its
 * intended site.
 *
 * The flag is seeded reflectively because nothing here can produce it honestly: it is set only for a
 * plug-latched adapter (GrapheneOS today) whose configured cap was read back over Shizuku, which
 * cannot be stood up on the JVM. The publications under test are adapter-independent.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ChargingRepositoryFailurePublicationTest {

    @get:Rule val tempFolder = TemporaryFolder()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val storeScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val buildIdentity = object : BuildIdentitySource {
        override fun current() = "test-build"
    }

    private lateinit var repository: ChargingRepository

    @Before
    fun setup() {
        val shadowPackageManager = shadowOf(context.packageManager)
        shadowPackageManager.setSystemFeature(DeviceInfo.FEATURE_LINEAGE_OS, true)
        shadowPackageManager.addOrUpdateProvider(
            ProviderInfo().apply {
                authority = DeviceInfo.LINEAGE_SETTINGS_AUTHORITY
                packageName = "org.lineageos.lineagesettings"
                name = "org.lineageos.lineagesettings.LineageSettingsProvider"
            },
        )
        shadowOf(context as Application).grantPermissions(Manifest.permission.WRITE_SECURE_SETTINGS)

        val appDataStore = AppDataStore(
            PreferenceDataStoreFactory.create(scope = storeScope) {
                File(tempFolder.root, "test.preferences_pb")
            },
        )
        val json = SerializationModule.json()
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
            preferences = ChargingPreferences(appDataStore, json),
            shizukuController = shizukuController,
            settleScheduler = object : SettleScheduler {
                override fun schedule(requestedAtMillis: Long) = Unit
            },
            batteryReader = BatteryReader(context, BatteryUnitCalibration(context)),
            evidenceStore = EnforcementEvidenceStore(appDataStore, buildIdentity, json),
            qualificationStore = QualificationEvidenceStore(appDataStore, buildIdentity, json),
            runStore = QualificationRunStore(appDataStore, json),
            fullChargeStore = FullChargeStore(appDataStore, json),
            buildIdentity = buildIdentity,
        )
    }

    @After
    fun teardown() {
        storeScope.cancel()
    }

    private fun seedUnconfirmedCap() {
        val published = ReflectionHelpers.getField<MutableStateFlow<ChargingState>>(repository, "mutableState")
        published.value = published.value.copy(capAwaitsHardwareConfirmation = true)
        repository.state.value.capAwaitsHardwareConfirmation shouldBe true
    }

    @Test
    fun `a gate refusal drops a standing unconfirmed-cap claim`() = runTest {
        seedUnconfirmedCap()

        repository.applyPersistent(ChargePolicy.FixedLimit(80))
            .observation.shouldBeInstanceOf<ChargeObservation.Unsupported>()

        repository.state.value.capAwaitsHardwareConfirmation shouldBe false
    }

    @Test
    fun `a missing write backend drops a standing unconfirmed-cap claim`() = runTest {
        shadowOf(context as Application).denyPermissions(Manifest.permission.WRITE_SECURE_SETTINGS)
        seedUnconfirmedCap()

        repository.restorePersistent(ChargePolicy.FixedLimit(80))
            .observation.shouldBeInstanceOf<ChargeObservation.NeedsSetup>()

        repository.state.value.capAwaitsHardwareConfirmation shouldBe false
    }

    @Test
    fun `a failed write drops a standing unconfirmed-cap claim`() = runTest {
        seedUnconfirmedCap()

        repository.restorePersistent(ChargePolicy.FixedLimit(80))
            .observation.shouldBeInstanceOf<ChargeObservation.Unknown>()

        repository.state.value.capAwaitsHardwareConfirmation shouldBe false
    }
}
