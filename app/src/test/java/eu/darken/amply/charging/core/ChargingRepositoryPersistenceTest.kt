package eu.darken.amply.charging.core

import android.Manifest
import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.os.Build
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ApplicationProvider
import eu.darken.amply.battery.core.BatteryReader
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
import eu.darken.amply.charging.core.adapter.XiaomiLabAdapter
import eu.darken.amply.common.AppDataStore
import eu.darken.amply.common.serialization.SerializationModule
import io.kotest.matchers.shouldBe
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
import org.robolectric.util.ReflectionHelpers
import java.io.File

/**
 * Repository-level persistence of the two policy signals, over the real object graph (adapter registry,
 * access resolver, direct WSS backend, DataStore-backed preferences) rather than the preference facade
 * alone. What the facade tests cannot catch is the repository handing the wrong `persistent` flag down:
 * a temporary session write recorded as persistent silently rewrites the user's protective baseline and
 * disables the any-level reconnect-gesture basis.
 *
 * The baseline is deliberately [ChargePolicy.Adaptive], never FixedLimit(80): `protectivePolicyNow()`
 * defaults to FixedLimit(80) when nothing was ever persisted, so applying 80 and asserting 80 would pass
 * even if the repository recorded nothing at all.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ChargingRepositoryPersistenceTest {

    @get:Rule val tempFolder = TemporaryFolder()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val storeScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private lateinit var preferences: ChargingPreferences
    private lateinit var repository: ChargingRepository

    @Before
    fun setup() {
        // Satisfy the Pixel capability gate: Google manufacturer, a supported model, telephony, and a
        // resolvable Settings Intelligence charging-optimization activity (API level comes from @Config).
        ReflectionHelpers.setStaticField(Build::class.java, "MANUFACTURER", "Google")
        ReflectionHelpers.setStaticField(Build::class.java, "MODEL", "Pixel 9 Pro")
        val shadowPackageManager = shadowOf(context.packageManager)
        shadowPackageManager.setSystemFeature(PackageManager.FEATURE_TELEPHONY, true)
        shadowPackageManager.addResolveInfoForIntent(
            Intent(DeviceInfo.ACTION_CHARGING_OPTIMIZATION),
            ResolveInfo().apply {
                activityInfo = ActivityInfo().apply {
                    packageName = "com.google.android.settings.intelligence"
                    name = "ChargingOptimizationActivity"
                }
            },
        )

        val appDataStore = AppDataStore(
            PreferenceDataStoreFactory.create(scope = storeScope) {
                File(tempFolder.root, "test.preferences_pb")
            },
        )
        preferences = ChargingPreferences(appDataStore, SerializationModule.json())

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
                xiaomiLab = XiaomiLabAdapter(),
                onePlus = OnePlusChargingAdapter(),
                onePlusLab = OnePlusLabAdapter(),
            ),
            accessResolver = AccessResolver(
                direct = DirectSettingsBackend(context),
                shizuku = ShizukuSettingsBackend(shizukuController),
            ),
            preferences = preferences,
            shizukuController = shizukuController,
            // A real WorkManager scheduler adds nothing here; the surface re-push is out of scope.
            settleScheduler = object : SettleScheduler {
                override fun schedule(requestedAtMillis: Long) = Unit
            },
            batteryReader = BatteryReader(context),
        )
    }

    @After
    fun teardown() {
        storeScope.cancel()
    }

    @Test
    fun `a persistent apply records both the persistent policy and the protective baseline`() = runTest {
        grantWriteSecureSettings()

        repository.applyPersistent(ChargePolicy.Adaptive).success shouldBe true

        preferences.lastPersistentPolicyNow() shouldBe ChargePolicy.Adaptive
        preferences.protectivePolicyNow() shouldBe ChargePolicy.Adaptive
    }

    /** The regression that would silently disable the any-level gesture basis. */
    @Test
    fun `a temporary apply leaves the persistent policy and the protective baseline untouched`() = runTest {
        grantWriteSecureSettings()
        repository.applyPersistent(ChargePolicy.Adaptive).success shouldBe true

        repository.applyTemporary(ChargePolicy.Unrestricted).success shouldBe true

        preferences.lastRequestedNow() shouldBe ChargePolicy.Unrestricted
        preferences.lastPersistentPolicyNow() shouldBe ChargePolicy.Adaptive
        preferences.protectivePolicyNow() shouldBe ChargePolicy.Adaptive
    }

    @Test
    fun `a forced persistent re-apply moves both signals`() = runTest {
        grantWriteSecureSettings()
        repository.applyPersistent(ChargePolicy.Adaptive).success shouldBe true

        repository.reapplyPersistent(ChargePolicy.FixedLimit(80)).success shouldBe true

        preferences.lastPersistentPolicyNow() shouldBe ChargePolicy.FixedLimit(80)
        preferences.protectivePolicyNow() shouldBe ChargePolicy.FixedLimit(80)
    }

    /** A write that never landed must leave no journal at all — neither persistent nor temporary. */
    @Test
    fun `a failed apply records nothing`() = runTest {
        revokeWriteSecureSettings()

        repository.applyPersistent(ChargePolicy.Adaptive).success shouldBe false

        preferences.lastPersistentPolicyNow() shouldBe null
        preferences.lastRequestedNow() shouldBe null
        preferences.lastRequestedAtNow() shouldBe 0L
    }

    private fun grantWriteSecureSettings() = shadowOf(context as Application)
        .grantPermissions(Manifest.permission.WRITE_SECURE_SETTINGS)

    private fun revokeWriteSecureSettings() = shadowOf(context as Application)
        .denyPermissions(Manifest.permission.WRITE_SECURE_SETTINGS)
}
