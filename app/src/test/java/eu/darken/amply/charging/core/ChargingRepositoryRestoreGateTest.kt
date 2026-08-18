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
import eu.darken.amply.charging.core.enforcement.EnforcementEvidence
import eu.darken.amply.charging.core.enforcement.EnforcementEvidenceStore
import eu.darken.amply.charging.core.enforcement.EnforcementVerdict
import eu.darken.amply.charging.core.enforcement.EnforcementVerdictEngine
import eu.darken.amply.charging.core.qualification.QualificationEvidenceStore
import eu.darken.amply.charging.core.qualification.QualificationRunStore
import eu.darken.amply.common.AppDataStore
import eu.darken.amply.common.serialization.SerializationModule
import eu.darken.amply.fullcharge.core.RecoveryOrigin
import eu.darken.amply.fullcharge.core.writeRecoveryTarget
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
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
import java.io.File

/**
 * The enforcement gate withholds NEW control on a build whose hardware was never observed honouring
 * a cap. It must never withhold the repayment of a protective policy the user already had: a
 * confirmed LineageOS device with an open full-charge session that installs a nightly comes back
 * with a changed build identity — a candidate again — while still owing an 80% restore. Refusing
 * that write leaves the device configured Unrestricted, which is what the gate exists to prevent.
 *
 * Both paths fail here (WRITE_SECURE_SETTINGS cannot write the Lineage provider), so the assertion
 * is on *where* they fail: the gated apply never reaches the adapter (Unsupported, the gate's
 * reason), while the restore reaches the write and fails there (Unknown).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ChargingRepositoryRestoreGateTest {

    @get:Rule val tempFolder = TemporaryFolder()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val storeScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val buildIdentity = object : BuildIdentitySource {
        override fun current() = "test-build"
    }

    private lateinit var evidenceStore: EnforcementEvidenceStore
    private lateinit var repository: ChargingRepository

    @Before
    fun setup() {
        // A LineageOS build as the app can actually see it: the system feature (the ro.lineage.*
        // properties are SELinux-denied) plus the private settings provider. QUALIFIED_CODENAMES
        // ships empty, so this device is a CANDIDATE with control gated off.
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
        evidenceStore = EnforcementEvidenceStore(appDataStore, buildIdentity, json)
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
            evidenceStore = evidenceStore,
            qualificationStore = QualificationEvidenceStore(appDataStore, buildIdentity, json),
            runStore = QualificationRunStore(appDataStore, json),
            buildIdentity = buildIdentity,
        )
    }

    @After
    fun teardown() {
        storeScope.cancel()
    }

    private suspend fun refute() = evidenceStore.record(
        EnforcementEvidence(
            adapterId = "lineageos-chargingcontrol-v1",
            buildIdentity = buildIdentity.current(),
            algorithmVersion = EnforcementVerdictEngine.ALGORITHM_VERSION,
            verdict = EnforcementVerdict.REFUTED,
            capPercent = 80,
            observedPercent = 90,
            observedAtWallMillis = 1_000L,
        ),
    ) shouldBe true

    @Test
    fun `a candidate build refuses a fresh user apply but still repays a restore`() = runTest {
        val fresh = repository.applyPersistent(ChargePolicy.FixedLimit(80))
        fresh.success shouldBe false
        fresh.observation.shouldBeInstanceOf<ChargeObservation.Unsupported>()

        // The persisted restore obligation gets past the tier and is attempted against the adapter.
        repository.restorePersistent(ChargePolicy.FixedLimit(80))
            .observation.shouldBeInstanceOf<ChargeObservation.Unknown>()
    }

    @Test
    fun `a refuted build refuses a fresh user apply but still repays a restore`() = runTest {
        refute()

        repository.applyPersistent(ChargePolicy.FixedLimit(80))
            .observation.shouldBeInstanceOf<ChargeObservation.Unsupported>()

        repository.restorePersistent(ChargePolicy.FixedLimit(80), forceNotify = true)
            .observation.shouldBeInstanceOf<ChargeObservation.Unknown>()
    }

    /**
     * The recovery dispatch, which is where the bypass could leak: `setPersistentPolicy` persists its
     * target BEFORE the risky write, so a process death or a failed write leaves a *fresh user
     * choice* as pending recovery work. Boot recovery must write that one through the gate — the
     * build can have become a candidate (an OTA changes the composite build identity) or been refuted
     * meanwhile — while an owed session restore keeps its bypass.
     */
    @Test
    fun `a candidate build gates a pending user request but not an owed restore`() = runTest {
        repository.writeRecoveryTarget(ChargePolicy.Unrestricted, RecoveryOrigin.USER_REQUEST).let {
            it.success shouldBe false
            it.observation.shouldBeInstanceOf<ChargeObservation.Unsupported>()
        }

        repository.writeRecoveryTarget(ChargePolicy.FixedLimit(80), RecoveryOrigin.SESSION_RESTORE)
            .observation.shouldBeInstanceOf<ChargeObservation.Unknown>()
    }

    @Test
    fun `a refuted build gates a pending user request but not an owed restore`() = runTest {
        refute()

        repository.writeRecoveryTarget(ChargePolicy.Unrestricted, RecoveryOrigin.USER_REQUEST).let {
            it.success shouldBe false
            it.observation.shouldBeInstanceOf<ChargeObservation.Unsupported>()
        }

        repository.writeRecoveryTarget(ChargePolicy.FixedLimit(80), RecoveryOrigin.SESSION_RESTORE)
            .observation.shouldBeInstanceOf<ChargeObservation.Unknown>()
    }

    @Test
    fun `the restore path still enforces the adapter's own preconditions`() = runTest {
        // Not an evidence question: Adaptive is not in the Lineage adapter's supported policies, so
        // the restore must refuse it exactly like an ordinary apply would.
        repository.restorePersistent(ChargePolicy.Adaptive).let {
            it.success shouldBe false
            it.observation.shouldBeInstanceOf<ChargeObservation.Unsupported>()
        }
    }
}
