package eu.darken.amply.stats.ui

import eu.darken.amply.stats.core.ChargeSessionSummary
import eu.darken.amply.stats.core.ChargingType
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

class ChargeHistoryStatesTest {

    private val session = ChargeSessionSummary(
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

    @Test
    fun `the sessions provider is untouched until the flow is collected`() {
        // The whole point of taking a provider rather than a Flow: ChargeStatsRepository.recentSessions()
        // calls database.get() eagerly, so building the flow at ViewModel construction would create
        // stats.db for a user who never enabled capture and only opened the battery hub.
        var touched = false
        chargeHistoryStates(recentSessions = { touched = true; flowOf(emptyList()) })
        touched shouldBe false
    }

    @Test
    fun `loading is emitted before the first query answers`() = runTest {
        val states = chargeHistoryStates(recentSessions = { flowOf(listOf(session)) }).take(2).toList()
        states[0] shouldBe ChargeHistoryState.Loading
        states[1] shouldBe ChargeHistoryState.Ready(listOf(session))
    }

    @Test
    fun `an empty history is ready, not unavailable`() = runTest {
        chargeHistoryStates(recentSessions = { flowOf(emptyList()) }).take(2).toList()[1] shouldBe
            ChargeHistoryState.Ready(emptyList())
    }

    @Test
    fun `a synchronous construction failure is caught rather than thrown at the collector`() = runTest {
        // A broken stats.db throws when the DAO is resolved, i.e. inside the provider call itself.
        val states = chargeHistoryStates(recentSessions = { error("broken stats.db") }).take(2).toList()
        states[1] shouldBe ChargeHistoryState.Unavailable
    }

    @Test
    fun `a failure mid-collection reports unavailable, never an empty list`() = runTest {
        val states = chargeHistoryStates(
            recentSessions = { flow { throw IllegalStateException("query failed") } },
        ).take(2).toList()
        states[1] shouldBe ChargeHistoryState.Unavailable
    }

    @Test
    fun `the first emission is always loading`() = runTest {
        chargeHistoryStates(recentSessions = { flowOf(emptyList()) }).first() shouldBe ChargeHistoryState.Loading
    }
}
