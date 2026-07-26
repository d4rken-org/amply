package eu.darken.amply.main.ui.dashboard

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import eu.darken.amply.common.AppDataStore
import eu.darken.amply.stats.core.CaptureServiceHealth.NudgeOutcome
import eu.darken.amply.stats.core.ChargeSessionSummary
import eu.darken.amply.stats.core.ChargingType
import eu.darken.amply.stats.core.StatsPreferences
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

class StatsDashboardStatesTest {

    @TempDir
    lateinit var tempDir: File

    private val storeScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    @AfterEach
    fun teardown() {
        storeScope.cancel()
    }

    private val lastSession = ChargeSessionSummary(
        id = 7,
        startedAtWallMillis = 0L,
        endedAtWallMillis = 3_600_000L,
        durationMillis = 3_600_000L,
        startPercent = 40,
        endPercent = 80,
        chargingType = ChargingType.AC,
        avgPowerMilliwatts = 9_000,
        peakPowerMilliwatts = 18_000,
        minTemperatureTenthsC = 300,
        avgTemperatureTenthsC = 310,
        maxTemperatureTenthsC = 330,
        limitHit = false,
        partial = false,
        fullReachedAtWallMillis = null,
        sealReason = null,
    )

    private fun states(
        enabled: Boolean = true,
        health: NudgeOutcome = NudgeOutcome.IDLE,
        recentSessions: () -> Flow<List<ChargeSessionSummary>> = { flowOf(emptyList()) },
        sessionCount: () -> Flow<Int> = { flowOf(0) },
    ) = statsDashboardStates(
        captureEnabled = flowOf(enabled),
        health = flowOf(health),
        recentSessions = recentSessions,
        sessionCount = sessionCount,
        currentSession = { flowOf(null) },
    )

    @Test
    fun `disabled emits the promo default without touching the providers`() = runTest {
        var touched = false
        val result = statsDashboardStates(
            captureEnabled = flowOf(false),
            health = flowOf(NudgeOutcome.IDLE),
            recentSessions = { touched = true; flowOf(emptyList()) },
            sessionCount = { touched = true; flowOf(0) },
            currentSession = { touched = true; flowOf(null) },
        ).first()

        result shouldBe StatsDashboardState()
        touched shouldBe false
    }

    @Test
    fun `enabling emits a loading marker before the first data`() = runTest {
        val emissions = states(
            recentSessions = { flowOf(listOf(lastSession)) },
            sessionCount = { flowOf(3) },
        ).take(2).toList()

        emissions[0] shouldBe StatsDashboardState(enabled = true, loading = true)
        emissions[1] shouldBe StatsDashboardState(enabled = true, lastSession = lastSession, sessionCount = 3)
    }

    @Test
    fun `failed service start flags startFailed`() = runTest {
        val result = states(health = NudgeOutcome.FAILED).take(2).toList().last()
        result shouldBe StatsDashboardState(enabled = true, startFailed = true)
    }

    @Test
    fun `a provider that throws synchronously downgrades to unavailable`() = runTest {
        val emissions = states(
            recentSessions = { error("stats.db could not be opened") },
        ).take(2).toList()

        emissions[0] shouldBe StatsDashboardState(enabled = true, loading = true)
        emissions[1] shouldBe StatsDashboardState(enabled = true, unavailable = true)
    }

    @Test
    fun `a flow that fails mid-collection downgrades to unavailable`() = runTest {
        val emissions = states(
            sessionCount = { flow { throw IllegalStateException("query failed") } },
        ).take(2).toList()

        emissions.last() shouldBe StatsDashboardState(enabled = true, unavailable = true)
    }

    /**
     * Regression for the charging card's ~20s "blip".
     *
     * Driven through the **real** [StatsPreferences] on a real store, because the bug lived in the
     * wiring rather than in this function: the recorder stamps `lastCaptureWallMillis` on every
     * sample, that write handed the whole Preferences snapshot to every collector, and an
     * undeduplicated `captureEnabled` re-emitted `true` — restarting the `flatMapLatest` below and
     * replaying its loading marker, which blanked the card's chart for a frame.
     *
     * Note the stimulus has to be an **unrelated key carrying a new value**. Re-writing
     * `captureEnabled = true` would prove nothing: an equal snapshot is suppressed by DataStore
     * itself, so that version of this test passes even against the broken code.
     */
    @Test
    fun `a recorder timestamp write does not restart the stats flow`() = runBlocking {
        val preferences = StatsPreferences(
            AppDataStore(
                PreferenceDataStoreFactory.create(scope = storeScope) { File(tempDir, "test.preferences_pb") },
            ),
        )
        preferences.setCaptureEnabled(true)

        var providersCreated = 0
        val states = statsDashboardStates(
            captureEnabled = preferences.captureEnabled.flow,
            health = flowOf(NudgeOutcome.IDLE),
            recentSessions = { providersCreated++; flowOf(emptyList()) },
            sessionCount = { flowOf(0) },
            currentSession = { flowOf(null) },
        )

        val seen = CopyOnWriteArrayList<StatsDashboardState>()
        val collector = launch(Dispatchers.IO) { states.toList(seen) }
        // Loading marker, then the first real data.
        withTimeout(TIMEOUT) { while (seen.size < 2) delay(5) }

        // Exactly what the recorder does on every recorded sample while charging.
        repeat(3) { i -> preferences.setLastCaptureWallMillis(1_000L + i) }
        delay(SETTLE)

        seen.count { it.loading } shouldBe 1
        providersCreated shouldBe 1
        seen.size shouldBe 2

        collector.cancel()
    }

    private companion object {
        const val TIMEOUT = 5_000L
        const val SETTLE = 200L
    }
}
