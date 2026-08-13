package eu.darken.amply.main.ui.dashboard

import android.app.Application
import android.os.BatteryManager
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import eu.darken.amply.R
import eu.darken.amply.battery.core.BatteryReadout
import eu.darken.amply.stats.core.ChargeCurvePoint
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

    // A curve whose window does NOT reach back to the session start (60% .. 70% for a session that
    // began at 40%) — the case that separates the session range from the plotted window's span.
    private val windowedCurve = (0..6).map { i ->
        ChargeCurvePoint(
            elapsedFromStartMillis = 3_600_000L + i * 60_000L,
            percent = 60 + i,
            powerMilliwatts = 12_000 - i * 500,
            temperatureTenthsC = 300 + i,
        )
    }

    private fun render(
        presentation: ChargingCardPresentation,
        readout: BatteryReadout? = charging,
        onOpenHub: () -> Unit = {},
        onRetryCapture: () -> Unit = {},
        nowElapsedRealtimeMillis: Long = 4_320_000L,
    ) {
        compose.setContent {
            ChargingCard(
                presentation = presentation,
                readout = readout,
                onOpenHub = onOpenHub,
                onRetryCapture = onRetryCapture,
                nowElapsedRealtimeMillis = nowElapsedRealtimeMillis,
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
        // 8 W measured, so the headline is the speed-classified one even with capture off.
        render(ChargingCardPresentation.Promo, readout = charging)
        compose.onNodeWithText(string(R.string.dashboard_charging_title_fast)).assertExists()
    }

    // The headline classifies the measured draw, so the card says how the charge is going rather than
    // only that it is happening.

    @Test
    fun `a high-power charger is titled very fast`() {
        render(
            ChargingCardPresentation.Live(session = liveSession),
            readout = charging.copy(voltageMillivolts = 9_000, currentNowMicroamps = 2_200_000),
        )
        compose.onNodeWithText(string(R.string.dashboard_charging_title_very_fast)).assertExists()
    }

    @Test
    fun `a trickle is titled slowly`() {
        render(
            ChargingCardPresentation.Live(session = liveSession),
            readout = charging.copy(currentNowMicroamps = 150_000),
        )
        compose.onNodeWithText(string(R.string.dashboard_charging_title_slow)).assertExists()
    }

    @Test
    fun `an ordinary draw keeps the plain title`() {
        // 4000 mV * 1_500_000 uA = 6 W — between the slow and fast bars.
        render(
            ChargingCardPresentation.Live(session = liveSession),
            readout = charging.copy(currentNowMicroamps = 1_500_000),
        )
        compose.onNodeWithText(string(R.string.dashboard_charging_title_charging)).assertExists()
    }

    @Test
    fun `an unmeasurable draw never guesses a speed`() {
        render(
            ChargingCardPresentation.Live(session = liveSession),
            readout = charging.copy(currentNowMicroamps = null),
        )
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
        compose.onNodeWithTag(CHARGING_CARD_TEST_TAG).performClick()
        compose.runOnIdle { opened shouldBe true }
    }

    // Every value has exactly one home: elapsed in the header, current values in the reading line, the
    // session's level range in the chart legend. The row that used to repeat them is gone.

    @Test
    fun `the live session's elapsed time sits in the header, not a row of its own`() {
        render(ChargingCardPresentation.Live(session = liveSession))
        compose.onNodeWithText("1h 12m").assertExists()
        compose.onNodeWithText("40% → 78%").assertDoesNotExist()
    }

    @Test
    fun `the header elapsed is decorative — tapping it opens the hub like the rest of the card`() {
        var opened = false
        render(ChargingCardPresentation.Live(session = liveSession), onOpenHub = { opened = true })
        compose.onNodeWithText("1h 12m").performClick()
        compose.runOnIdle { opened shouldBe true }
    }

    @Test
    fun `a multi-day hold renders its full duration in the header`() {
        // 123h 59m: StatsFormat.duration never rolls over into days, so the header must survive the
        // longest string it can produce rather than assuming a two-digit hour.
        render(
            ChargingCardPresentation.Live(session = liveSession),
            readout = holding,
            nowElapsedRealtimeMillis = 446_340_000L,
        )
        compose.onNodeWithText("123h 59m").assertExists()
    }

    @Test
    fun `the curve legend states the session range, not the plotted window's span`() {
        render(ChargingCardPresentation.Live(session = liveSession.copy(curve = windowedCurve)))
        val level = string(R.string.stats_curve_series_percent)
        // Session start (40) → live reading (78), even though the plotted samples only span 60..66.
        compose.onNodeWithText("$level  40→78%").assertExists()
        compose.onNodeWithText("$level  60→66%").assertDoesNotExist()
    }

    @Test
    fun `the compact chart does not announce the curve's lagging end values`() {
        // The end labels were dropped because they trail the live reading by a recorder tick; leaving
        // them in the content description would just move that contradiction into the screen reader.
        render(ChargingCardPresentation.Live(session = liveSession.copy(curve = windowedCurve)))
        val level = string(R.string.stats_curve_series_percent)
        // 66% is the curve's last sample; the card's reading says 78%.
        compose.onNodeWithContentDescription("$level: 66%", substring = true).assertDoesNotExist()
    }

    @Test
    fun `the curve appears the moment the session crosses the reveal threshold`() {
        // Composed one second short of the threshold: a fixed minute-long tick would hold the curve
        // back until 3m59s, so the first delay is aligned to the next whole session-minute instead.
        val startElapsed = android.os.SystemClock.elapsedRealtime() - 179_000L
        compose.mainClock.autoAdvance = false
        compose.setContent {
            ChargingCard(
                presentation = ChargingCardPresentation.Live(
                    session = liveSession.copy(
                        startedElapsedRealtimeMillis = startElapsed,
                        curve = windowedCurve,
                    ),
                ),
                readout = charging,
                onOpenHub = {},
                onRetryCapture = {},
                nowElapsedRealtimeMillis = startElapsed + 179_000L,
            )
        }
        compose.mainClock.advanceTimeByFrame()
        val level = string(R.string.stats_curve_series_percent)
        compose.onNodeWithText(level, substring = true).assertDoesNotExist()

        org.robolectric.shadows.ShadowSystemClock.advanceBy(java.time.Duration.ofMillis(1_000))
        compose.mainClock.advanceTimeBy(1_000)

        compose.onNodeWithText("$level  40→78%").assertExists()
    }

    @Test
    fun `a flat curve draws nothing rather than a fake trend`() {
        // A device parked at its limit: samples keep arriving, nothing moves. Self-normalizing a series
        // with no range puts it at the canvas midpoint, so drawing this would fake a plotted trend.
        val flat = (0..6).map { i ->
            ChargeCurvePoint(
                elapsedFromStartMillis = i * 60_000L,
                percent = 80,
                powerMilliwatts = 12_000,
                temperatureTenthsC = 300,
            )
        }
        render(
            ChargingCardPresentation.Live(session = liveSession.copy(startPercent = 80, curve = flat)),
            readout = holding,
        )
        compose.onNodeWithText(string(R.string.stats_curve_series_percent), substring = true)
            .assertDoesNotExist()
        // And with no chart there is no level range anywhere on the card — the accepted cost of
        // reclaiming the row. The header's elapsed time and the reading line still carry the session.
        compose.onNodeWithText("80% → 80%").assertDoesNotExist()
        compose.onNodeWithText("1h 12m").assertExists()
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
