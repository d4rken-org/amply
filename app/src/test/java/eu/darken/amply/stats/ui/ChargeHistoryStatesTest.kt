package eu.darken.amply.stats.ui

import eu.darken.amply.stats.core.ChargeSessionSummary
import eu.darken.amply.stats.core.ChargingType
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
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

    private fun sessions(count: Int) = List(count) { session.copy(id = it.toLong()) }

    private fun historyOf(
        recentSessions: (limit: Int) -> Flow<List<ChargeSessionSummary>>,
    ) = chargeHistoryStates(pages = flowOf(1), recentSessions = recentSessions)

    @Test
    fun `the sessions provider is untouched until the flow is collected`() {
        // The whole point of taking a provider rather than a Flow: ChargeStatsRepository.recentSessions()
        // calls database.get() eagerly, so building the flow at ViewModel construction would create
        // stats.db for a user who never enabled capture and only opened the battery hub.
        var touched = false
        chargeHistoryStates(pages = MutableStateFlow(1), recentSessions = { touched = true; flowOf(emptyList()) })
        touched shouldBe false
    }

    @Test
    fun `loading is emitted before the first query answers`() = runTest {
        val states = historyOf(recentSessions = { flowOf(listOf(session)) }).take(2).toList()
        states[0] shouldBe ChargeHistoryState.Loading
        states[1] shouldBe ChargeHistoryState.Ready(listOf(session))
    }

    @Test
    fun `an empty history is ready, not unavailable`() = runTest {
        historyOf(recentSessions = { flowOf(emptyList()) }).take(2).toList()[1] shouldBe
            ChargeHistoryState.Ready(emptyList())
    }

    @Test
    fun `a synchronous construction failure is caught rather than thrown at the collector`() = runTest {
        // A broken stats.db throws when the DAO is resolved, i.e. inside the provider call itself.
        val states = historyOf(recentSessions = { error("broken stats.db") }).take(2).toList()
        states[1] shouldBe ChargeHistoryState.Unavailable
    }

    @Test
    fun `a failure mid-collection reports unavailable, never an empty list`() = runTest {
        val states = historyOf(
            recentSessions = { flow { throw IllegalStateException("query failed") } },
        ).take(2).toList()
        states[1] shouldBe ChargeHistoryState.Unavailable
    }

    @Test
    fun `the first emission is always loading`() = runTest {
        historyOf(recentSessions = { flowOf(emptyList()) }).first() shouldBe ChargeHistoryState.Loading
    }

    @Test
    fun `the first window asks for one page`() = runTest {
        val limits = mutableListOf<Int>()
        historyOf(recentSessions = { limit -> limits += limit; flowOf(emptyList()) }).take(2).toList()
        limits shouldBe listOf(50)
    }

    @Test
    fun `a full window has more, a short one does not`() = runTest {
        historyOf(recentSessions = { limit -> flowOf(sessions(limit)) }).take(2).toList()[1] shouldBe
            ChargeHistoryState.Ready(sessions(50), hasMore = true)
        historyOf(recentSessions = { flowOf(sessions(49)) }).take(2).toList()[1] shouldBe
            ChargeHistoryState.Ready(sessions(49), hasMore = false)
    }

    @Test
    fun `a page increase queries the bigger window without going back to loading`() = runTest {
        val pages = MutableStateFlow(1)
        val limits = mutableListOf<Int>()
        val emitted = mutableListOf<ChargeHistoryState>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            chargeHistoryStates(
                pages = pages,
                recentSessions = { limit -> limits += limit; flowOf(sessions(limit)) },
            ).toList(emitted)
        }
        runCurrent()

        pages.value = 2
        runCurrent()

        limits shouldBe listOf(50, 100)
        emitted shouldBe listOf(
            ChargeHistoryState.Loading,
            ChargeHistoryState.Ready(sessions(50), hasMore = true),
            ChargeHistoryState.Ready(sessions(100), hasMore = true),
        )
    }

    @Test
    fun `load more advances a page once the current window arrived full`() {
        nextHistoryPages(1, ChargeHistoryState.Ready(sessions(50), hasMore = true)) shouldBe 2
        nextHistoryPages(2, ChargeHistoryState.Ready(sessions(100), hasMore = true)) shouldBe 3
    }

    @Test
    fun `load more does nothing when the history is exhausted`() {
        nextHistoryPages(1, ChargeHistoryState.Ready(sessions(12), hasMore = false)) shouldBe 1
        nextHistoryPages(1, ChargeHistoryState.Ready(emptyList())) shouldBe 1
    }

    @Test
    fun `load more does not skip a page while the bigger window is still loading`() {
        // Page 2 requested, but the screen still shows page 1's rows: repeated scroll triggers must
        // not push the count to 3 before those 100 rows have arrived.
        nextHistoryPages(2, ChargeHistoryState.Ready(sessions(50), hasMore = true)) shouldBe 2
    }

    @Test
    fun `load more does nothing outside a ready state`() {
        nextHistoryPages(1, ChargeHistoryState.Loading) shouldBe 1
        nextHistoryPages(1, ChargeHistoryState.Unavailable) shouldBe 1
    }
}
