package eu.darken.amply.main.ui.battery

import android.app.Application
import android.os.BatteryManager
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.text.TextLayoutResult
import androidx.test.core.app.ApplicationProvider
import eu.darken.amply.R
import eu.darken.amply.battery.core.BatteryReadout
import eu.darken.amply.common.compose.chart.SPARKLINE_TEST_TAG
import eu.darken.amply.stats.core.ChargeCurvePoint
import eu.darken.amply.stats.core.CurveMetricAvailability
import eu.darken.amply.stats.ui.ChargeTimeState
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The hub's tile grid and the detail rows below it. The tall qualifier renders the whole list, so a
 * missing row is a missing row rather than one scrolled out of the viewport.
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

    // Level and current vary; voltage is recorded but constant; power was never recorded at all.
    private val curve = (0..8).map { i ->
        ChargeCurvePoint(
            elapsedFromStartMillis = i * 300_000L,
            percent = 60 + i,
            powerMilliwatts = null,
            temperatureTenthsC = 300 + i,
            voltageMillivolts = 4_000,
            currentNowMicroamps = 2_000_000 - i * 100_000,
        )
    }

    private fun render(
        readout: BatteryReadout?,
        curve: List<ChargeCurvePoint> = emptyList(),
        // Defaults to what the drawn curve holds; a case that needs them to disagree passes its own.
        availability: CurveMetricAvailability = CurveMetricAvailability.of(curve),
        captureEnabled: Boolean = true,
        onOpenMetric: (BatteryMetric) -> Unit = {},
    ) {
        compose.setContent {
            BatteryHubScreen(
                readout = readout,
                captureEnabled = captureEnabled,
                teaser = ChargeTeaserState.None,
                // Recording is on, so the badged opt-in card never renders — these cases are about
                // the readout itself.
                showProBadge = false,
                onBack = {},
                onOpenHistory = {},
                onEnableCapture = {},
                onOpenSession = {},
                onOpenMetric = onOpenMetric,
                curve = curve,
                availability = availability,
                chargeTime = ChargeTimeState.NotEnoughData(sessions = 1),
            )
        }
    }

    @Test
    fun `with recording off there is no charge-time card at all`() {
        // The estimates come entirely from recorded history, so with nothing being recorded there is
        // no card rather than an empty one.
        render(charging, captureEnabled = false)
        compose.onAllNodesWithText(string(R.string.charge_time_title)).assertCountEquals(0)
        compose.onAllNodesWithText(string(R.string.charge_time_not_enough)).assertCountEquals(0)
    }

    @Test
    fun `with recording on the charge-time card is present`() {
        render(charging)
        compose.onNodeWithText(string(R.string.charge_time_title)).assertExists()
        compose.onNodeWithText(string(R.string.charge_time_not_enough)).assertExists()
    }

    @Test
    fun `all six tiles render`() {
        render(charging)
        listOf(
            R.string.battery_metric_level_title,
            R.string.battery_metric_power_title,
            R.string.battery_metric_voltage_title,
            R.string.battery_metric_current_title,
            R.string.battery_metric_temperature_title,
            R.string.battery_detail_status,
        ).forEach { res ->
            compose.onNodeWithText(string(res).uppercase()).assertExists()
        }
    }

    @Test
    fun `every pre-existing detail row survives the tile grid`() {
        render(charging)
        listOf(
            R.string.battery_detail_power_source,
            R.string.battery_detail_technology,
            R.string.battery_detail_health,
            R.string.battery_detail_cycle_count,
            R.string.battery_detail_charger_max,
            R.string.battery_detail_charge_counter,
            // "Health" is both a section title and a row label, so match the first of either.
        ).forEach { res -> compose.onAllNodesWithText(string(res)).onFirst().assertExists() }
    }

    @Test
    fun `the measured charge power and the advertised maximum are both shown`() {
        render(charging)
        // 4.0 V x 2.0 A measured at the battery...
        compose.onNodeWithText("8.0 W").assertExists()
        // ...against the 9 V / 2 A the charger claims it could deliver.
        compose.onNodeWithText(string(R.string.battery_detail_charger_max)).assertExists()
        compose.onNodeWithText("18.0 W").assertExists()
    }

    @Test
    fun `a limit hold says the battery is not charging, not that nothing was reported`() {
        render(charging.copy(status = BatteryManager.BATTERY_STATUS_NOT_CHARGING))
        // The status tile spells this out as "Plugged in, not charging", so this is the power tile.
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
    @Config(qualifiers = "+w411dp")
    fun `the longest status value renders whole at phone width`() {
        // The reported case: on a Pixel 7a the status tile clipped this to "Plugged in, n…". The
        // width qualifier is the geometry under test — half of a 411dp screen, minus the tile's own
        // padding — so a regression to a single fixed-height line fails here rather than on a device.
        render(charging.copy(status = BatteryManager.BATTERY_STATUS_NOT_CHARGING))
        val status = string(R.string.battery_status_plugged_not_charging)
        val node = compose.onNodeWithText(status).fetchSemanticsNode()
        val layouts = mutableListOf<TextLayoutResult>()
        node.config[SemanticsActions.GetTextLayoutResult].action?.invoke(layouts)
        val layout = layouts.single()
        layout.layoutInput.text.text shouldBe status
        layout.hasVisualOverflow shouldBe false
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
        compose.onNodeWithText(string(R.string.battery_metric_power_title).uppercase()).assertExists()
        compose.onAllNodesWithText(string(R.string.battery_value_not_charging)).assertCountEquals(0)
    }

    @Test
    fun `only the metrics with a drawable shape render a sparkline`() {
        render(charging, curve)
        // Level, current and temperature vary; voltage is flat and power has no samples at all.
        // Unmerged: a tappable tile's card merges its descendants, which would hide the canvas.
        compose.onAllNodesWithTag(SPARKLINE_TEST_TAG, useUnmergedTree = true).assertCountEquals(3)
    }

    @Test
    fun `a metric with no variation is still tappable`() {
        var opened: BatteryMetric? = null
        render(charging, curve) { opened = it }
        // Voltage is constant across the curve, so it draws nothing — but min == avg == max is still
        // an answer, so the tile must open.
        compose.onNodeWithText(string(R.string.battery_metric_voltage_title).uppercase()).performClick()
        opened shouldBe BatteryMetric.VOLTAGE
    }

    @Test
    fun `a metric with no samples at all is not tappable`() {
        var opened: BatteryMetric? = null
        render(charging, curve) { opened = it }
        compose.onNodeWithText(string(R.string.battery_metric_power_title).uppercase()).performClick()
        opened shouldBe null
    }

    @Test
    fun `a metric the decimated curve dropped is still tappable`() {
        // The drawn curve is decimated, so an intermittently reported metric can lose every one of
        // its readings to the stride. Availability is taken from the raw samples, and it is what
        // decides the tap — the detail screen reads those same raw samples.
        var opened: BatteryMetric? = null
        render(
            charging,
            curve,
            availability = CurveMetricAvailability.of(curve).copy(power = true),
        ) { opened = it }
        compose.onNodeWithText(string(R.string.battery_metric_power_title).uppercase()).performClick()
        opened shouldBe BatteryMetric.POWER
    }

    @Test
    fun `without a recorded curve no tile navigates anywhere`() {
        var opened: BatteryMetric? = null
        render(charging) { opened = it }
        listOf(
            R.string.battery_metric_level_title,
            R.string.battery_metric_temperature_title,
        ).forEach { res ->
            compose.onNodeWithText(string(res).uppercase()).performClick()
        }
        opened shouldBe null
        compose.onAllNodesWithTag(SPARKLINE_TEST_TAG, useUnmergedTree = true).assertCountEquals(0)
    }
}
