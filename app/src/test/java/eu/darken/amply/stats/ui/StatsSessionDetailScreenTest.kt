package eu.darken.amply.stats.ui

import android.app.Application
import android.os.BatteryManager
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import eu.darken.amply.R
import eu.darken.amply.battery.core.BatteryReadout
import eu.darken.amply.stats.core.ChargeSessionSummary
import eu.darken.amply.stats.core.ChargingType
import eu.darken.amply.stats.core.StatsSealReason
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The live "Power now" row. Everything else on this screen is history; this one value is the present,
 * so it exists only while the viewed session is the open one the caller vouched for.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "+h2400dp")
class StatsSessionDetailScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<Application>()

    private fun string(res: Int): String = context.getString(res)

    private val closed = ChargeSessionSummary(
        id = 1,
        startedAtWallMillis = 0L,
        endedAtWallMillis = 3_600_000L,
        durationMillis = 3_600_000L,
        startPercent = 42,
        endPercent = 100,
        chargingType = ChargingType.AC,
        avgPowerMilliwatts = 12_000,
        peakPowerMilliwatts = 25_000,
        minTemperatureTenthsC = 300,
        avgTemperatureTenthsC = 320,
        maxTemperatureTenthsC = 340,
        limitHit = false,
        partial = false,
        fullReachedAtWallMillis = null,
        sealReason = StatsSealReason.UNPLUGGED,
    )

    private val open = closed.copy(
        endedAtWallMillis = null,
        endPercent = 78,
        sealReason = null,
    )

    private val charging = BatteryReadout(
        levelPercent = 78,
        status = BatteryManager.BATTERY_STATUS_CHARGING,
        plugged = BatteryManager.BATTERY_PLUGGED_AC,
        voltageMillivolts = 4_000,
        currentNowMicroamps = 2_000_000,
    )

    private fun render(summary: ChargeSessionSummary, readout: BatteryReadout?) {
        compose.setContent {
            StatsSessionDetailScreen(
                state = StatsDetailState(summary = summary, curve = emptyList()),
                readout = readout,
                onBack = {},
            )
        }
    }

    @Test
    fun `an open session shows the live draw beside its recorded averages`() {
        render(open, charging)
        compose.onNodeWithText(string(R.string.stats_detail_power_now)).assertExists()
        // 8.0 W now, against the session's own 12.0 W average and 25.0 W peak.
        compose.onNodeWithText("8.0 W").assertExists()
    }

    @Test
    fun `a limit hold says the battery is not charging rather than nothing was reported`() {
        render(open, charging.copy(status = BatteryManager.BATTERY_STATUS_NOT_CHARGING))
        compose.onNodeWithText(string(R.string.stats_detail_power_now)).assertExists()
        compose.onNodeWithText(string(R.string.battery_value_not_charging)).assertExists()
    }

    @Test
    fun `a finished session has no present to report`() {
        render(closed, charging)
        compose.onNodeWithText(string(R.string.stats_detail_power_now)).assertDoesNotExist()
    }

    @Test
    fun `an open session the caller did not vouch for shows no live value`() {
        // Null readout is how the caller says "this is not the session that is charging right now".
        render(open, null)
        compose.onNodeWithText(string(R.string.stats_detail_power_now)).assertDoesNotExist()
    }
}
