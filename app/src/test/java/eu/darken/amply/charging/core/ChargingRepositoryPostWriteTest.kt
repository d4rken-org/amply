package eu.darken.amply.charging.core

import android.Manifest
import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.os.Build
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
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeSameInstanceAs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
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
 * What [ChargingRepository.applyLocked] owes a caller *after* the physical settings write has already
 * committed. The write is the irreversible part; every step after it (hardware decode, access snapshot,
 * surface re-push scheduling) is metadata that may fail on its own, and none of those failures may
 * strand `busy`, lose the fact that the write landed, or replace a cancellation with its own exception.
 *
 * The fault is injected where the real one lives: the Pixel adapter is ASYNC_HARDWARE and not
 * plug-latched, so the post-write block calls `adapter.readHardware(context)`, which registers a
 * battery receiver on the repository's context unguarded. Only the *repository* gets the faulting
 * context — every other dependency keeps the ordinary one, so nothing before the write is disturbed.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ChargingRepositoryPostWriteTest {

    @get:Rule val tempFolder = TemporaryFolder()

    private val appContext: Context = ApplicationProvider.getApplicationContext()
    private val storeScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private lateinit var faultingContext: FaultingContext
    private lateinit var scheduler: RecordingScheduler
    private lateinit var preferences: ChargingPreferences
    private lateinit var repository: ChargingRepository

    private val buildIdentity = object : BuildIdentitySource {
        override fun current() = "test-build"
    }

    @Before
    fun setup() {
        // Satisfy the Pixel capability gate: Google manufacturer, a supported model, telephony, and a
        // resolvable Settings Intelligence charging-optimization activity (API level comes from @Config).
        ReflectionHelpers.setStaticField(Build::class.java, "MANUFACTURER", "Google")
        ReflectionHelpers.setStaticField(Build::class.java, "MODEL", "Pixel 9 Pro")
        val shadowPackageManager = shadowOf(appContext.packageManager)
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

        faultingContext = FaultingContext(appContext)
        scheduler = RecordingScheduler()
        val shizukuController = ShizukuController(appContext, ShizukuInstallationDetector(appContext))
        repository = ChargingRepository(
            context = faultingContext,
            registry = AdapterRegistry(
                context = appContext,
                lineage = LineageChargingAdapter(LineageSettingsClient(appContext)),
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
                direct = DirectSettingsBackend(appContext),
                shizuku = ShizukuSettingsBackend(shizukuController),
            ),
            preferences = preferences,
            shizukuController = shizukuController,
            settleScheduler = scheduler,
            batteryReader = BatteryReader(appContext, BatteryUnitCalibration(appContext)),
            evidenceStore = EnforcementEvidenceStore(appDataStore, buildIdentity, SerializationModule.json()),
            qualificationStore = QualificationEvidenceStore(appDataStore, buildIdentity, SerializationModule.json()),
            runStore = QualificationRunStore(appDataStore, SerializationModule.json()),
            fullChargeStore = FullChargeStore(appDataStore, SerializationModule.json()),
            buildIdentity = buildIdentity,
        )
        grantWriteSecureSettings()
    }

    @After
    fun teardown() {
        storeScope.cancel()
    }

    /**
     * A backend bind timing out after the write is a failed post-write step, not this coroutine being
     * cancelled: the apply still reports the truthful degraded success.
     */
    @Test
    fun `a post-write timeout reports a degraded success and keeps the pending cue`() = runTest {
        val timeout = captureTimeout()
        faultingContext.fault = { throw timeout }

        val result = repository.applyPersistent(ChargePolicy.Adaptive)

        result.success shouldBe true
        faultingContext.hits shouldBe 1
        repository.state.value.busy shouldBe false
        repository.state.value.observation shouldBe ChargeObservation.LastRequested(ChargePolicy.Adaptive)
        repository.state.value.pending?.target shouldBe ChargePolicy.Adaptive
        assertWriteLanded(ChargePolicy.Adaptive)
    }

    /** A scheduler failure must never replace or suppress the degraded success just published. */
    @Test
    fun `an ordinary post-write failure survives a throwing scheduler`() = runTest {
        faultingContext.fault = { throw IllegalStateException("battery read blew up") }
        scheduler.fault = { throw IllegalStateException("scheduler unavailable") }

        val result = repository.applyPersistent(ChargePolicy.Adaptive)

        result.success shouldBe true
        faultingContext.hits shouldBe 1
        scheduler.calls shouldBe 1
        repository.state.value.busy shouldBe false
        repository.state.value.observation shouldBe ChargeObservation.LastRequested(ChargePolicy.Adaptive)
        repository.state.value.pending?.target shouldBe ChargePolicy.Adaptive
        assertWriteLanded(ChargePolicy.Adaptive)
    }

    /**
     * Genuine cancellation after the write: the *original* exception is what must reach the caller, so a
     * throwing scheduler cannot turn a cancellation into an ordinary failure.
     */
    @Test
    fun `post-write cancellation propagates the original instance past a throwing scheduler`() = runTest {
        val cancellation = CancellationException("backend gone")
        faultingContext.fault = { throw cancellation }
        scheduler.fault = { throw IllegalStateException("scheduler unavailable") }

        val thrown = shouldThrow<Throwable> { repository.applyPersistent(ChargePolicy.Adaptive) }

        thrown shouldBeSameInstanceAs cancellation
        faultingContext.hits shouldBe 1
        scheduler.calls shouldBe 1
        repository.state.value.busy shouldBe false
        assertWriteLanded(ChargePolicy.Adaptive)
    }

    /** Same, with a scheduler whose own failure is itself a cancellation — type alone cannot tell them apart. */
    @Test
    fun `post-write cancellation propagates the original instance past a cancelling scheduler`() = runTest {
        val cancellation = CancellationException("backend gone")
        faultingContext.fault = { throw cancellation }
        scheduler.fault = { throw CancellationException("scheduler cancelled") }

        val thrown = shouldThrow<Throwable> { repository.applyPersistent(ChargePolicy.Adaptive) }

        thrown shouldBeSameInstanceAs cancellation
        faultingContext.hits shouldBe 1
        scheduler.calls shouldBe 1
        repository.state.value.busy shouldBe false
        assertWriteLanded(ChargePolicy.Adaptive)
    }

    /**
     * The whole file is about what happens *after* the write, so every case proves the write itself
     * landed — a fixture that failed earlier (gate, backend, adapter) would otherwise pass vacuously.
     */
    private suspend fun assertWriteLanded(policy: ChargePolicy) {
        preferences.lastRequestedNow() shouldBe policy
        preferences.lastPersistentPolicyNow() shouldBe policy
        preferences.lastRequestedAtNow() shouldNotBe 0L
    }

    private suspend fun captureTimeout(): TimeoutCancellationException = try {
        withTimeout(1) { awaitCancellation() }
    } catch (e: TimeoutCancellationException) {
        e
    }

    private fun grantWriteSecureSettings() = shadowOf(appContext as Application)
        .grantPermissions(Manifest.permission.WRITE_SECURE_SETTINGS)

    /** Fails `PixelChargingAdapter.readHardware`'s unguarded battery-receiver registration on demand. */
    private class FaultingContext(base: Context) : ContextWrapper(base) {
        var fault: (() -> Unit)? = null
        var hits = 0

        override fun registerReceiver(receiver: BroadcastReceiver?, filter: IntentFilter?): Intent? {
            if (filter?.hasAction(Intent.ACTION_BATTERY_CHANGED) == true) {
                hits++
                fault?.invoke()
            }
            return super.registerReceiver(receiver, filter)
        }
    }

    private class RecordingScheduler : SettleScheduler {
        var calls = 0
        var fault: (() -> Unit)? = null

        override fun schedule(requestedAtMillis: Long) {
            calls++
            fault?.invoke()
        }
    }
}
