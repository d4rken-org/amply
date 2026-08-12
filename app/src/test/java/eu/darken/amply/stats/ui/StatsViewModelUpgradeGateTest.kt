package eu.darken.amply.stats.ui

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import eu.darken.amply.battery.core.BatteryReader
import eu.darken.amply.common.AppDataStore
import eu.darken.amply.common.datastore.value
import eu.darken.amply.stats.core.BootIdSource
import eu.darken.amply.stats.core.CaptureServiceHealth
import eu.darken.amply.stats.core.ChargeStatsRecorder
import eu.darken.amply.stats.core.ChargeStatsRepository
import eu.darken.amply.stats.core.StatsPreferences
import eu.darken.amply.stats.core.db.StatsDatabase
import eu.darken.amply.upgrade.core.UpgradeRepo
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.time.Instant

/**
 * Charge recording is the one gated feature that also *writes* something, so the gate has to hold in
 * two places and let go in a third: enabling is refused before anything is written or asked for,
 * disabling is never refused, and the notification prompt only follows a passed check.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class StatsViewModelUpgradeGateTest {

    @get:Rule val tempFolder = TemporaryFolder()

    private val storeScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private class FakeInfo(override val isPro: Boolean) : UpgradeRepo.Info {
        override val type: UpgradeRepo.Type = UpgradeRepo.Type.FOSS
        override val isSettled: Boolean = true
        override val upgradedAt: Instant? = null
        override val error: Throwable? = null
    }

    private class FakeUpgradeRepo(isPro: Boolean) : UpgradeRepo {
        override val storeSite: String = ""
        override val upgradeSite: String = ""
        override val betaSite: String = ""
        override val upgradeInfo: Flow<UpgradeRepo.Info> = MutableStateFlow(FakeInfo(isPro))
        override suspend fun refresh() = Unit
    }

    @Before fun setup() {
        Dispatchers.setMain(Dispatchers.Unconfined)
    }

    @After fun teardown() {
        Dispatchers.resetMain()
        storeScope.cancel()
    }

    private fun viewModel(isPro: Boolean): Pair<StatsViewModel, StatsPreferences> {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val store = PreferenceDataStoreFactory.create(scope = storeScope) {
            File(tempFolder.newFolder(), "test.preferences_pb")
        }
        val preferences = StatsPreferences(AppDataStore(store))
        // The stats database is never opened on these paths, and it must stay that way: a user who
        // is refused the feature has no business getting a stats.db created for them.
        val database = object : dagger.Lazy<StatsDatabase> {
            override fun get(): StatsDatabase = error("stats database must not be opened here")
        }
        val bootIdSource = BootIdSource(context)
        val recorder = ChargeStatsRecorder(
            context = context,
            database = database,
            preferences = preferences,
            bootIdSource = bootIdSource,
            batteryReader = BatteryReader(context),
            dispatcher = Dispatchers.IO,
        )
        val vm = StatsViewModel(
            context = context,
            preferences = preferences,
            repository = ChargeStatsRepository(database, recorder, bootIdSource),
            recorder = recorder,
            serviceHealth = CaptureServiceHealth(),
            upgradeRepo = FakeUpgradeRepo(isPro),
            savedStateHandle = SavedStateHandle(),
        )
        return vm to preferences
    }

    @Test fun `a free user is routed to the upgrade screen and nothing is enabled`(): Unit = runBlocking {
        val (vm, preferences) = viewModel(isPro = false)

        vm.requestEnableCapture()

        withTimeout(TIMEOUT_MS) { vm.upgradeRequiredEvents.first() }
        // The permission prompt is downstream of the check: asking for notification access and only
        // then refusing the feature would be the wrong order to put a user through.
        withTimeoutOrNull(QUIET_MS) { vm.proceedWithEnableEvents.first() } shouldBe null
        preferences.captureEnabled.value() shouldBe false
    }

    @Test fun `an upgraded user is handed on to the permission flow`(): Unit = runBlocking {
        val (vm, preferences) = viewModel(isPro = true)

        vm.requestEnableCapture()

        withTimeout(TIMEOUT_MS) { vm.proceedWithEnableEvents.first() }
        // Still not enabled: the request only clears the gate, the activity's permission flow is what
        // actually turns it on.
        preferences.captureEnabled.value() shouldBe false
    }

    @Test fun `the write itself re-checks, so no other caller can slip past the gate`(): Unit = runBlocking {
        val (vm, preferences) = viewModel(isPro = false)

        vm.setCaptureEnabled(true)

        withTimeout(TIMEOUT_MS) { vm.upgradeRequiredEvents.first() }
        preferences.captureEnabled.value() shouldBe false
    }

    @Test fun `turning recording off is never gated`(): Unit = runBlocking {
        val (vm, preferences) = viewModel(isPro = false)
        preferences.setCaptureEnabled(true)

        vm.setCaptureEnabled(false)

        // A lapsed entitlement must not trap a user with a service they want stopped.
        withTimeout(TIMEOUT_MS) {
            while (preferences.captureEnabled.value()) { /* awaiting the write */ }
        }
        withTimeoutOrNull(QUIET_MS) { vm.upgradeRequiredEvents.first() } shouldBe null
    }

    private companion object {
        const val TIMEOUT_MS = 10_000L
        const val QUIET_MS = 300L
    }
}
