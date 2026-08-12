package eu.darken.amply.main.ui.battery

import android.app.Application
import android.os.BatteryManager
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import eu.darken.amply.R
import eu.darken.amply.battery.core.BatteryReadout
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The electrical section's two wattage rows. The tall qualifier renders the whole list, so a missing
 * row is a missing row rather than one scrolled out of the viewport.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "+h2400dp")
class BatteryHubScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<Application>()

    private fun string(res: Int): String = context.getString(res)

    // Every field reported, so a "Not reported" anywhere on the screen belongs to the row under test.
    private val charging = BatteryReadout(
        levelPercent = 82,
        status = BatteryManager.BATTERY_STATUS_CHARGING,
        plugged = BatteryManager.BATTERY_PLUGGED_AC,
        health = BatteryManager.BATTERY_HEALTH_GOOD,
        technology = "Li-ion",
        temperatureTenthsC = 314,
        voltageMillivolts = 4_000,
        currentNowMicroamps = 2_000_000,
        chargeCounterMicroampHours = 3_800_000,
        cycleCount = 142,
        maxChargingCurrentMicroamps = 2_000_000,
        maxChargingVoltageMicrovolts = 9_000_000,
    )

    private fun render(readout: BatteryReadout?) {
        compose.setContent {
            BatteryHubScreen(
                readout = readout,
                captureEnabled = true,
                teaser = ChargeTeaserState.None,
                onBack = {},
                onOpenHistory = {},
                onEnableCapture = {},
                onOpenSession = {},
            )
        }
    }

    @Test
    fun `the measured charge power and the advertised maximum are both shown`() {
        render(charging)
        compose.onNodeWithText(string(R.string.battery_detail_power)).assertExists()
        // 4.0 V x 2.0 A measured at the battery...
        compose.onNodeWithText("8.0 W").assertExists()
        // ...against the 9 V / 2 A the charger claims it could deliver.
        compose.onNodeWithText(string(R.string.battery_detail_charger_max)).assertExists()
        compose.onNodeWithText("18.0 W").assertExists()
    }

    @Test
    fun `a limit hold says the battery is not charging, not that nothing was reported`() {
        render(charging.copy(status = BatteryManager.BATTERY_STATUS_NOT_CHARGING))
        // The status row spells this out as "Plugged in, not charging", so this is the power row.
        compose.onNodeWithText(string(R.string.battery_value_not_charging)).assertExists()
    }

    @Test
    fun `unplugged there is no charge power and nothing advertised`() {
        render(
            charging.copy(
                status = BatteryManager.BATTERY_STATUS_DISCHARGING,
                plugged = 0,
                currentNowMicroamps = -500_000,
            ),
        )
        compose.onNodeWithText(string(R.string.battery_value_not_charging)).assertExists()
        // Nothing is connected, so the advertised maximum is the screen's only "Not reported" — the
        // extras are not read through while off the charger even when the platform left them set.
        compose.onNodeWithText(string(R.string.battery_value_not_reported)).assertExists()
    }

    @Test
    fun `a charger advertising nothing reports nothing rather than claiming zero`() {
        render(
            charging.copy(
                maxChargingCurrentMicroamps = null,
                maxChargingVoltageMicrovolts = null,
            ),
        )
        compose.onNodeWithText("8.0 W").assertExists()
        compose.onNodeWithText(string(R.string.battery_value_not_reported)).assertExists()
    }

    @Test
    fun `an unreadable battery never claims the battery is not charging`() {
        render(null)
        compose.onNodeWithText(string(R.string.battery_detail_power)).assertExists()
        compose.onAllNodesWithText(string(R.string.battery_value_not_charging)).assertCountEquals(0)
    }
}
