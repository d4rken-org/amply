package eu.darken.amply.main.ui.dashboard

import eu.darken.amply.stats.core.CaptureServiceHealth.NudgeOutcome
import eu.darken.amply.stats.core.ChargeSessionSummary
import eu.darken.amply.stats.core.ChargingType
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

class StatsDashboardStatesTest {

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
}
