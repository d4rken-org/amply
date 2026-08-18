package eu.darken.amply.main.ui.battery

import androidx.annotation.StringRes
import eu.darken.amply.R
import eu.darken.amply.battery.core.BatteryReadout
import eu.darken.amply.battery.ui.formatCurrent
import eu.darken.amply.battery.ui.formatTemperature
import eu.darken.amply.battery.ui.formatVoltage
import eu.darken.amply.stats.core.ChargeCurvePoint
import eu.darken.amply.stats.core.CurveAggregates
import eu.darken.amply.stats.core.CurveMetricAvailability
import eu.darken.amply.stats.core.MetricStats
import eu.darken.amply.stats.core.StatsPowerCalculator
import eu.darken.amply.stats.ui.StatsFormat

/**
 * A battery reading that exists both as a live value and as a recorded series — the set the hub can
 * offer a per-metric detail screen for.
 *
 * Status and the descriptive fields (technology, health) are deliberately absent: they are not
 * numeric series, so there is nothing to chart or to take a minimum of.
 *
 * Every accessor here works off the *raw* unit the sample stores (percent, milliwatts, millivolts,
 * microamps, tenths of a degree) and formatting is delegated to the existing shared formatters, so a
 * tile, a chart axis and a statistics row can never render the same reading differently.
 */
enum class BatteryMetric(
    @get:StringRes val titleRes: Int,
    @get:StringRes val explainerRes: Int,
) {
    LEVEL(R.string.battery_metric_level_title, R.string.battery_metric_level_explainer),
    POWER(R.string.battery_metric_power_title, R.string.battery_metric_power_explainer),
    VOLTAGE(R.string.battery_metric_voltage_title, R.string.battery_metric_voltage_explainer),
    CURRENT(R.string.battery_metric_current_title, R.string.battery_metric_current_explainer),
    TEMPERATURE(R.string.battery_metric_temperature_title, R.string.battery_metric_temperature_explainer),
    ;

    /** This metric's value at one recorded curve point, in its raw stored unit. */
    fun select(point: ChargeCurvePoint): Int? = when (this) {
        LEVEL -> point.percent
        POWER -> point.powerMilliwatts
        VOLTAGE -> point.voltageMillivolts
        CURRENT -> point.currentNowMicroamps
        TEMPERATURE -> point.temperatureTenthsC
    }

    /**
     * This metric's current value from a live readout.
     *
     * Power routes through [StatsPowerCalculator.chargeMilliwatts] rather than multiplying the two
     * fields here, so a discharge draw is never presented as charge power.
     */
    fun select(readout: BatteryReadout?): Int? = when (this) {
        LEVEL -> readout?.levelPercent
        POWER -> StatsPowerCalculator.chargeMilliwatts(readout)
        VOLTAGE -> readout?.voltageMillivolts
        CURRENT -> readout?.currentNowMicroamps
        TEMPERATURE -> readout?.temperatureTenthsC
    }

    fun stats(aggregates: CurveAggregates): MetricStats? = when (this) {
        LEVEL -> aggregates.level
        POWER -> aggregates.power
        VOLTAGE -> aggregates.voltage
        CURRENT -> aggregates.current
        TEMPERATURE -> aggregates.temperature
    }

    /** Formats a raw value in this metric's unit; null in, null out (the caller owns the fallback). */
    fun format(value: Int?): String? = when (this) {
        LEVEL -> value?.let { "$it%" }
        POWER -> StatsFormat.power(value)
        VOLTAGE -> formatVoltage(value)
        CURRENT -> formatCurrent(value)
        TEMPERATURE -> formatTemperature(value)
    }

    /** Formats a chart axis tick, which arrives as a float in the same raw unit. */
    fun formatAxis(value: Float): String = format(value.toInt()) ?: ""

    /**
     * True when the shown charge recorded at least one reading for this metric.
     *
     * Reads the availability flags rather than the drawn curve, because that curve is decimated: a
     * metric reported only intermittently can lose every one of its readings to the stride and would
     * then look absent although the detail screen (which reads the raw samples) has data to show.
     *
     * Deliberately *presence*, not variation: a constant metric is still worth opening — the chart
     * renders a zero-range series as a flat line on a real axis, and a min == avg == max reading is
     * a meaningful answer. Only the sparkline needs variation, because a self-normalized series
     * without a range would draw a fake midline.
     */
    fun hasSamples(availability: CurveMetricAvailability): Boolean = when (this) {
        LEVEL -> availability.level
        POWER -> availability.power
        VOLTAGE -> availability.voltage
        CURRENT -> availability.current
        TEMPERATURE -> availability.temperature
    }
}
