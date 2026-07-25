package eu.darken.amply.main.ui.dashboard

import android.app.Application
import android.os.BatteryManager
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
class ChargingCardTest {
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

    private val charging = BatteryReadout(
        levelPercent = 78,
        status = BatteryManager.BATTERY_STATUS_CHARGING,
        plugged = BatteryManager.BATTERY_PLUGGED_AC,
        temperatureTenthsC = 312,
        voltageMillivolts = 4_000,
        currentNowMicroamps = 2_000_000,
    )

    private val holding = charging.copy(
        levelPercent = 80,
        status = BatteryManager.BATTERY_STATUS_NOT_CHARGING,
    )

    private val unplugged = BatteryReadout(
        levelPercent = 64,
        status = BatteryManager.BATTERY_STATUS_DISCHARGING,
        plugged = 0,
        temperatureTenthsC = 298,
        voltageMillivolts = 3_900,
        currentNowMicroamps = -500_000,
    )

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
        presentation: ChargingCardPresentation,
        readout: BatteryReadout? = charging,
        onOpenHub: () -> Unit = {},
        onRetryCapture: () -> Unit = {},
    ) {
        compose.setContent {
            ChargingCard(
                presentation = presentation,
                readout = readout,
                onOpenHub = onOpenHub,
                onRetryCapture = onRetryCapture,
                nowElapsedRealtimeMillis = 4_320_000L,
            )
        }
    }

    // The reading is the card's whole point, so it must survive every session state — including the
    // ones where capture is off or broken and there is no session data at all.

    @Test
    fun `the reading renders while capture is off`() {
        render(ChargingCardPresentation.Promo, readout = unplugged)
        compose.onNodeWithText("64% · Discharging · 29.8 °C").assertExists()
        compose.onNodeWithText(string(R.string.dashboard_charging_history_hint)).assertExists()
    }

    @Test
    fun `the reading renders while the stats pipeline is loading`() {
        render(ChargingCardPresentation.Loading)
        compose.onNodeWithText("78% · Charging · 8.0 W · 31.2 °C").assertExists()
        compose.onNodeWithText(string(R.string.dashboard_stats_loading)).assertExists()
    }

    @Test
    fun `the reading renders through a stats outage`() {
        render(ChargingCardPresentation.Unavailable)
        compose.onNodeWithText("78% · Charging · 8.0 W · 31.2 °C").assertExists()
        compose.onNodeWithText(string(R.string.dashboard_stats_unavailable)).assertExists()
    }

    @Test
    fun `power is withheld at a limit hold so a held battery never shows charge power`() {
        render(ChargingCardPresentation.Live(session = liveSession), readout = holding)
        compose.onNodeWithText("80% · Plugged in, not charging · 31.2 °C").assertExists()
    }

    @Test
    fun `power is withheld while discharging so draw is never shown as charge power`() {
        render(ChargingCardPresentation.Idle(lastSession = null, sessionCount = 0), readout = unplugged)
        compose.onNodeWithText("64% · Discharging · 29.8 °C").assertExists()
    }

    @Test
    fun `an absent readout says so rather than hiding the line`() {
        render(ChargingCardPresentation.Idle(lastSession = null, sessionCount = 0), readout = null)
        val notReported = string(R.string.battery_value_not_reported)
        // The status has its own "Unknown" label; each value says what it individually knows.
        val unknown = string(R.string.battery_value_unknown)
        compose.onNodeWithText("$notReported · $unknown · $notReported").assertExists()
    }

    // The title follows the readout, not the session state — one truth rule for "on the charger".

    @Test
    fun `the title follows the charger, not the capture state`() {
        render(ChargingCardPresentation.Promo, readout = charging)
        compose.onNodeWithText(string(R.string.dashboard_charging_title_charging)).assertExists()
    }

    @Test
    fun `unplugged the card titles itself Battery`() {
        render(ChargingCardPresentation.Idle(lastSession = lastSession, sessionCount = 3), readout = unplugged)
        compose.onNodeWithText(string(R.string.dashboard_charging_title_idle)).assertExists()
    }

    // On a charger is not the same as charging: a device parked at its limit is connected and idle, and
    // a headline claiming otherwise would contradict the card's own reading one line below it.
    @Test
    fun `a limit hold is titled Connected, not Charging`() {
        render(ChargingCardPresentation.Live(session = liveSession), readout = holding)
        compose.onNodeWithText(string(R.string.dashboard_charging_title_connected)).assertExists()
        compose.onNodeWithText(string(R.string.dashboard_charging_title_charging)).assertDoesNotExist()
    }

    @Test
    fun `a plugged device with an unreported status is titled Connected`() {
        render(
            ChargingCardPresentation.ConnectedWithoutSession(startFailed = false),
            readout = charging.copy(status = null),
        )
        compose.onNodeWithText(string(R.string.dashboard_charging_title_connected)).assertExists()
    }

    // One card, one destination — in every state, including the live one that used to deep-link.

    @Test
    fun `a live card opens the hub, not the session`() {
        var opened = false
        render(ChargingCardPresentation.Live(session = liveSession), onOpenHub = { opened = true })
        compose.onNodeWithText("40% → 78%").assertExists()
        compose.onNodeWithTag(CHARGING_CARD_TEST_TAG).performClick()
        compose.runOnIdle { opened shouldBe true }
    }

    @Test
    fun `an idle card opens the hub`() {
        var opened = false
        render(
            ChargingCardPresentation.Idle(lastSession = lastSession, sessionCount = 3),
            readout = unplugged,
            onOpenHub = { opened = true },
        )
        compose.onNodeWithText(string(R.string.dashboard_stats_last, "40% → 80%  ·  1h 0m")).assertExists()
        compose.onNodeWithTag(CHARGING_CARD_TEST_TAG).performClick()
        compose.runOnIdle { opened shouldBe true }
    }

    @Test
    fun `a promo card opens the hub, where the capture toggle lives`() {
        var opened = false
        render(ChargingCardPresentation.Promo, onOpenHub = { opened = true })
        compose.onNodeWithTag(CHARGING_CARD_TEST_TAG).performClick()
        compose.runOnIdle { opened shouldBe true }
    }

    @Test
    fun `connected without a session shows the starting note and opens the hub`() {
        var opened = false
        render(
            ChargingCardPresentation.ConnectedWithoutSession(startFailed = false),
            onOpenHub = { opened = true },
        )
        compose.onNodeWithText(string(R.string.dashboard_stats_live_starting)).assertExists()
        compose.onNodeWithText(string(R.string.dashboard_stats_retry_action)).assertDoesNotExist()
        compose.onNodeWithTag(CHARGING_CARD_TEST_TAG).performClick()
        compose.runOnIdle { opened shouldBe true }
    }

    @Test
    fun `retry repairs capture without also navigating`() {
        var retried = false
        var navigated = false
        render(
            ChargingCardPresentation.ConnectedWithoutSession(startFailed = true),
            onOpenHub = { navigated = true },
            onRetryCapture = { retried = true },
        )
        compose.onNodeWithText(string(R.string.dashboard_stats_live_start_failed)).assertExists()
        compose.onNodeWithText(string(R.string.dashboard_stats_retry_action)).performClick()
        compose.runOnIdle {
            retried shouldBe true
            navigated shouldBe false
        }
    }

    @Test
    fun `live elapsed keeps ticking without new battery data`() {
        val startElapsed = android.os.SystemClock.elapsedRealtime()
        compose.mainClock.autoAdvance = false
        compose.setContent {
            ChargingCard(
                presentation = ChargingCardPresentation.Live(
                    session = liveSession.copy(startedElapsedRealtimeMillis = startElapsed),
                ),
                readout = charging,
                onOpenHub = {},
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
    fun `idle without any session renders the empty note`() {
        render(
            ChargingCardPresentation.Idle(lastSession = null, sessionCount = 0),
            readout = unplugged,
        )
        compose.onNodeWithText(string(R.string.dashboard_stats_recording_empty)).assertExists()
    }
}
