package eu.darken.amply.stats.ui

import android.app.Application
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import eu.darken.amply.R
import eu.darken.amply.battery.core.BatteryReadout
import eu.darken.amply.stats.core.ChargeSessionSummary
import eu.darken.amply.stats.core.ChargingType
import eu.darken.amply.stats.core.StatsLiveSession
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class StatsDashboardCardTest {
    @get:Rule
    val compose = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<Application>()

    private fun string(res: Int, vararg args: Any): String = context.getString(res, *args)

    private val liveSession = StatsLiveSession(
        id = 42,
        startedAtWallMillis = 0L,
        startedElapsedRealtimeMillis = 0L,
        startPercent = 40,
        partial = false,
        curve = emptyList(),
    )

    private val pluggedBattery = BatteryReadout(levelPercent = 78, plugged = 1)

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

    private fun render(
        presentation: StatsCardPresentation,
        onOpenStats: () -> Unit = {},
        onOpenLiveSession: (Long) -> Unit = {},
        onRetryCapture: () -> Unit = {},
    ) {
        compose.setContent {
            StatsDashboardCard(
                presentation = presentation,
                onOpenStats = onOpenStats,
                onOpenLiveSession = onOpenLiveSession,
                onRetryCapture = onRetryCapture,
                nowElapsedRealtimeMillis = 4_320_000L,
            )
        }
    }

    @Test
    fun `promo body renders the pitch`() {
        render(StatsCardPresentation.Promo)
        compose.onNodeWithText(string(R.string.dashboard_stats_title)).assertExists()
        compose.onNodeWithText(string(R.string.dashboard_stats_promo)).assertExists()
    }

    @Test
    fun `loading body renders the placeholder`() {
        render(StatsCardPresentation.Loading)
        compose.onNodeWithText(string(R.string.dashboard_stats_loading)).assertExists()
    }

    @Test
    fun `unavailable body renders the outage note`() {
        render(StatsCardPresentation.Unavailable)
        compose.onNodeWithText(string(R.string.dashboard_stats_unavailable)).assertExists()
    }

    @Test
    fun `live body renders under the on-charger title and opens the session detail`() {
        var openedId: Long? = null
        render(
            StatsCardPresentation.Live(session = liveSession, battery = pluggedBattery),
            onOpenLiveSession = { openedId = it },
        )
        compose.onNodeWithText(string(R.string.dashboard_stats_live_title)).assertExists()
        compose.onNodeWithText("40% → 78%").assertExists()
        compose.onNodeWithTag(STATS_CARD_TEST_TAG).performClick()
        compose.runOnIdle { openedId shouldBe 42L }
    }

    @Test
    fun `live body offers history without triggering the session deep link`() {
        var opened = false
        var openedId: Long? = null
        render(
            StatsCardPresentation.Live(session = liveSession, battery = pluggedBattery),
            onOpenStats = { opened = true },
            onOpenLiveSession = { openedId = it },
        )
        // The card's own tap goes to this session, so the past-sessions list needs its own action —
        // which must not bubble to that navigation.
        compose.onNodeWithText(string(R.string.dashboard_stats_history_action)).performClick()
        compose.runOnIdle {
            opened shouldBe true
            openedId shouldBe null
        }
    }

    // Everywhere but Live the card's own tap already opens the list, so a second affordance would be a
    // duplicate — asserted per state because setContent can only run once per test.
    @Test
    fun `idle carries no history action`() {
        render(StatsCardPresentation.Idle(lastSession = lastSession, sessionCount = 3))
        compose.onNodeWithText(string(R.string.dashboard_stats_history_action)).assertDoesNotExist()
    }

    @Test
    fun `connected-without-session carries no history action`() {
        render(StatsCardPresentation.ConnectedWithoutSession(battery = pluggedBattery, startFailed = false))
        compose.onNodeWithText(string(R.string.dashboard_stats_history_action)).assertDoesNotExist()
    }

    @Test
    fun `connected-without-session shows the starting note and opens the stats list`() {
        var opened = false
        render(
            StatsCardPresentation.ConnectedWithoutSession(battery = pluggedBattery, startFailed = false),
            onOpenStats = { opened = true },
        )
        compose.onNodeWithText(string(R.string.dashboard_stats_live_title)).assertExists()
        compose.onNodeWithText(string(R.string.dashboard_stats_live_starting)).assertExists()
        compose.onNodeWithText(string(R.string.dashboard_stats_retry_action)).assertDoesNotExist()
        compose.onNodeWithTag(STATS_CARD_TEST_TAG).performClick()
        compose.runOnIdle { opened shouldBe true }
    }

    @Test
    fun `failed start shows the retry affordance`() {
        var retried = false
        var navigated = false
        render(
            StatsCardPresentation.ConnectedWithoutSession(battery = pluggedBattery, startFailed = true),
            onOpenStats = { navigated = true },
            onRetryCapture = { retried = true },
        )
        compose.onNodeWithText(string(R.string.dashboard_stats_live_start_failed)).assertExists()
        compose.onNodeWithText(string(R.string.dashboard_stats_retry_action)).performClick()
        compose.runOnIdle {
            retried shouldBe true
            // Retry must not also trigger the card's own navigation.
            navigated shouldBe false
        }
    }

    @Test
    fun `live elapsed keeps ticking without new battery data`() {
        val startElapsed = android.os.SystemClock.elapsedRealtime()
        compose.mainClock.autoAdvance = false
        compose.setContent {
            StatsDashboardCard(
                presentation = StatsCardPresentation.Live(
                    session = liveSession.copy(startedElapsedRealtimeMillis = startElapsed),
                    battery = pluggedBattery,
                ),
                onOpenStats = {},
                onOpenLiveSession = {},
                onRetryCapture = {},
                nowElapsedRealtimeMillis = startElapsed,
            )
        }
        compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithText("0m").assertExists()

        // No new battery readout arrives — only the shadow clock and the card's own minute tick.
        org.robolectric.shadows.ShadowSystemClock.advanceBy(java.time.Duration.ofMillis(61_000))
        compose.mainClock.advanceTimeBy(61_000)

        compose.onNodeWithText("1m").assertExists()
    }

    @Test
    fun `idle body renders the last-session teaser`() {
        render(StatsCardPresentation.Idle(lastSession = lastSession, sessionCount = 3))
        compose.onNodeWithText(string(R.string.dashboard_stats_title)).assertExists()
        compose.onNodeWithText(string(R.string.dashboard_stats_last, "40% → 80%  ·  1h 0m")).assertExists()
    }

    @Test
    fun `idle body without sessions renders the empty note`() {
        render(StatsCardPresentation.Idle(lastSession = null, sessionCount = 0))
        compose.onNodeWithText(string(R.string.dashboard_stats_recording_empty)).assertExists()
    }
}
