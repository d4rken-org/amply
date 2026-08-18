package eu.darken.amply.main.ui.battery

import android.app.Application
import android.os.BatteryManager
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
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
        // The accessibility font scale to render at; the platform's own scale is unaffected by the
        // qualifiers, so a large-font case has to provide it.
        fontScale: Float = 1f,
        onOpenMetric: (BatteryMetric) -> Unit = {},
    ) {
        compose.setContent {
            CompositionLocalProvider(
                LocalDensity provides Density(LocalDensity.current.density, fontScale),
            ) {
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
        // The tile is half a screen wide, so the words become a dash — but only for the state the
        // battery positively reported. What this case exists to prove is that the two stay
        // distinguishable: every other reading here is present, so "Not reported" (a device that
        // exposes no figure, which the dash must never stand in for) is nowhere on the screen.
        compose.onNodeWithText(string(R.string.battery_value_none)).assertExists()
        compose.onAllNodesWithText(string(R.string.battery_value_not_reported)).assertCountEquals(0)
        // The status tile still spells it out as "Plugged in, not charging"; the bare words do not
        // appear, because the tile that would have carried them now shows the dash.
        compose.onAllNodesWithText(string(R.string.battery_value_not_charging)).assertCountEquals(0)
    }

    @Test
    fun `a charging battery that reports no current still says not reported, never a dash`() {
        // The figure is missing, not zero: the device is taking charge and simply exposes no
        // current. Collapsing that into the dash would claim an observation nothing made.
        render(charging.copy(currentNowMicroamps = null))
        compose.onAllNodesWithText(string(R.string.battery_value_none)).assertCountEquals(0)
        // Two tiles are affected — charge power, which is derived from the current, and current
        // itself.
        compose.onAllNodesWithText(string(R.string.battery_value_not_reported)).assertCountEquals(2)
    }

    @Test
    fun `the dash announces the state it replaces`() {
        render(charging.copy(status = BatteryManager.BATTERY_STATUS_NOT_CHARGING))
        // A dash is a meaningless glyph to a screen reader, so the tile speaks the words the width
        // did not allow.
        compose.onNodeWithContentDescription(string(R.string.battery_value_not_charging)).assertExists()
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
        // Off the charger there is no charge power to state, which the tile shows as a dash.
        compose.onNodeWithText(string(R.string.battery_value_none)).assertExists()
        compose.onAllNodesWithText(string(R.string.battery_value_not_charging)).assertCountEquals(0)
        // Nothing is connected, so the advertised maximum is the screen's only "Not reported" — the
        // extras are not read through while off the charger even when the platform left them set.
        // The single match also proves the dash did not displace it onto the power tile.
        compose.onNodeWithText(string(R.string.battery_value_not_reported)).assertExists()
    }

    /**
     * The status tile's laid-out value. Semantics carry the full string even when it is visually
     * truncated, so only the layout result can tell a whole rendering from a clipped one.
     */
    private fun statusValueLayout(): TextLayoutResult {
        val status = string(R.string.battery_status_plugged_not_charging)
        val node = compose.onNodeWithText(status).fetchSemanticsNode()
        val layouts = mutableListOf<TextLayoutResult>()
        node.config[SemanticsActions.GetTextLayoutResult].action?.invoke(layouts)
        val layout = layouts.single()
        layout.layoutInput.text.text shouldBe status
        return layout
    }

    @Test
    @Config(qualifiers = "+w411dp")
    fun `the longest status value renders whole at phone width`() {
        // The reported case: on a Pixel 7a the status tile clipped this to "Plugged in, n…". The
        // width qualifier is the geometry under test — half of a 411dp screen, minus the tile's own
        // padding — so a regression to a single fixed-height line fails here rather than on a device.
        render(charging.copy(status = BatteryManager.BATTERY_STATUS_NOT_CHARGING))
        statusValueLayout().hasVisualOverflow shouldBe false
    }

    @Test
    @Config(qualifiers = "+w411dp")
    fun `the longest status value renders whole at twice the font scale`() {
        // The same tile at the largest accessibility font scale, where the wrap alone is not enough:
        // two lines of this style hold 22 of the 24 characters, which is what the third line is for.
        // Nothing here changes the normal-scale rendering — the value box is a minimum height, so an
        // unneeded line is never drawn.
        render(charging.copy(status = BatteryManager.BATTERY_STATUS_NOT_CHARGING), fontScale = 2f)
        statusValueLayout().hasVisualOverflow shouldBe false
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
