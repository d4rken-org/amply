package eu.darken.amply.stats.ui

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import eu.darken.amply.battery.core.BatteryReader
import eu.darken.amply.battery.core.BatteryUnitCalibration
import eu.darken.amply.common.AppDataStore
import eu.darken.amply.main.ui.battery.BatteryMetric
import eu.darken.amply.stats.core.BootIdSource
import eu.darken.amply.stats.core.CaptureServiceHealth
import eu.darken.amply.stats.core.ChargeStatsRecorder
import eu.darken.amply.stats.core.ChargeStatsRepository
import eu.darken.amply.stats.core.StatsPreferences
import eu.darken.amply.stats.core.db.StatsDatabase
import eu.darken.amply.upgrade.core.UpgradeRepo
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
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
 * The metric-detail selection is the pair (session, metric), and it has to survive a restore without
 * ever resolving half of itself: a metric restored beside whatever session the hub happens to point
 * at would silently retitle another charge's data.
 *
 * The injected database throws on access, so any resolution attempt that reached Room would fail the
 * test rather than quietly opening stats.db.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class StatsViewModelMetricSelectionTest {

    @get:Rule val tempFolder = TemporaryFolder()

    private val storeScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private class FakeInfo : UpgradeRepo.Info {
        override val type: UpgradeRepo.Type = UpgradeRepo.Type.FOSS
        override val isPro: Boolean = true
        override val isSettled: Boolean = true
        override val error: Throwable? = null
        override val upgradedAt: Instant? = null
    }

    private class FakeUpgradeRepo : UpgradeRepo {
        override val storeSite: String = ""
        override val upgradeSite: String = ""
        override val betaSite: String = ""
        override val upgradeInfo: Flow<UpgradeRepo.Info> = MutableStateFlow(FakeInfo())
        override suspend fun refresh() = Unit
    }

    @Before fun setup() {
        Dispatchers.setMain(Dispatchers.Unconfined)
    }

    @After fun teardown() {
        Dispatchers.resetMain()
        storeScope.cancel()
    }

    private fun viewModel(savedState: SavedStateHandle): StatsViewModel {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val store = PreferenceDataStoreFactory.create(scope = storeScope) {
            File(tempFolder.newFolder(), "test.preferences_pb")
        }
        val preferences = StatsPreferences(AppDataStore(store))
        val database = object : dagger.Lazy<StatsDatabase> {
            override fun get(): StatsDatabase = error("stats database must not be opened here")
        }
        val bootIdSource = BootIdSource(context)
        val recorder = ChargeStatsRecorder(
            context = context,
            database = database,
            preferences = preferences,
            bootIdSource = bootIdSource,
            batteryReader = BatteryReader(context, BatteryUnitCalibration(context)),
            dispatcher = Dispatchers.IO,
        )
        return StatsViewModel(
            context = context,
            preferences = preferences,
            repository = ChargeStatsRepository(database, recorder, bootIdSource),
            recorder = recorder,
            serviceHealth = CaptureServiceHealth(),
            upgradeRepo = FakeUpgradeRepo(),
            savedStateHandle = savedState,
        )
    }

    @Test fun `an unknown saved metric name clears the selection instead of throwing`(): Unit = runBlocking {
        // A record written by a build that knew a metric this one doesn't.
        val savedState = SavedStateHandle(
            mapOf(
                StatsViewModel.KEY_METRIC_SESSION to 42L,
                StatsViewModel.KEY_METRIC_NAME to "CHARGE_COUNTER",
            ),
        )
        val vm = viewModel(savedState)

        val collector = launch { vm.metricDetailState.collect { } }
        try {
            withTimeout(TIMEOUT_MS) {
                while (savedState.get<String>(StatsViewModel.KEY_METRIC_NAME) != null) delay(10)
            }
        } finally {
            collector.cancelAndJoin()
        }
        // Both halves go, so nothing is left to resolve against a session it can't label.
        savedState.get<Long>(StatsViewModel.KEY_METRIC_SESSION).shouldBeNull()
        vm.metricDetailState.value.shouldBeNull()
    }

    @Test fun `opening and closing writes and clears both keys together`(): Unit = runBlocking {
        val savedState = SavedStateHandle()
        val vm = viewModel(savedState)

        vm.openMetric(sessionId = 7L, metric = BatteryMetric.VOLTAGE)
        savedState.get<Long>(StatsViewModel.KEY_METRIC_SESSION) shouldBe 7L
        savedState.get<String>(StatsViewModel.KEY_METRIC_NAME) shouldBe "VOLTAGE"

        vm.closeMetric()
        savedState.get<Long>(StatsViewModel.KEY_METRIC_SESSION).shouldBeNull()
        savedState.get<String>(StatsViewModel.KEY_METRIC_NAME).shouldBeNull()
    }

    @Test fun `a session id without a metric name resolves to nothing`(): Unit = runBlocking {
        // Half a selection is no selection: it must not resolve against the session alone, and it
        // must not reach the database to find that out.
        val savedState = SavedStateHandle(mapOf(StatsViewModel.KEY_METRIC_SESSION to 42L))
        val vm = viewModel(savedState)

        val collector = launch { vm.metricDetailState.collect { } }
        try {
            withTimeoutOrNull(QUIET_MS) { vm.metricDetailState.first { it != null } }.shouldBeNull()
        } finally {
            collector.cancelAndJoin()
        }
    }

    private companion object {
        const val TIMEOUT_MS = 5_000L
        const val QUIET_MS = 300L
    }
}
