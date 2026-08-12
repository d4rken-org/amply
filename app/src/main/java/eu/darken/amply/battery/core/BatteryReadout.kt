package eu.darken.amply.battery.core

/**
 * A permission-free snapshot of public battery state, read from the sticky
 * [android.content.Intent.ACTION_BATTERY_CHANGED] broadcast plus
 * [android.os.BatteryManager.getIntProperty]. Every field is nullable: anything the platform can't
 * report (unsupported property, missing extra, pre-API-34 cycle count) is `null` rather than a
 * magic sentinel, so the UI can render "Not reported" instead of a bogus value.
 *
 * [currentNowMicroamps] keeps its sign — negative or positive is OEM-defined and is shown verbatim
 * as "Current now"; it is never abs()'d into a misleading "draw".
 */
data class BatteryReadout(
    val levelPercent: Int? = null,
    val status: Int? = null,
    /** Raw [android.os.BatteryManager.EXTRA_CHARGING_STATUS] (hidden Pixel charge-policy state). */
    val chargingStatus: Int? = null,
    val plugged: Int? = null,
    val health: Int? = null,
    val technology: String? = null,
    val temperatureTenthsC: Int? = null,
    val voltageMillivolts: Int? = null,
    val currentNowMicroamps: Int? = null,
    val chargeCounterMicroampHours: Int? = null,
    val cycleCount: Int? = null,
    /**
     * Charger-advertised maximum, not a measurement: what the connected supply says it can deliver.
     * Only meaningful while something is connected, and never a substitute for the measured draw.
     *
     * Sourced from the `max_charging_current` / `max_charging_voltage` extras of
     * [android.content.Intent.ACTION_BATTERY_CHANGED]. The framework populates them, but their
     * `BatteryManager` constants are `@hide` and absent from the public SDK, so `BatteryReader` reads
     * the literal AOSP keys.
     */
    val maxChargingCurrentMicroamps: Int? = null,
    val maxChargingVoltageMicrovolts: Int? = null,
) {
    /**
     * External power is reported. A null (not reported) [plugged] collapses conservatively to false —
     * nothing may claim a charger it can't observe. This is the single "on the charger" rule; it is
     * deliberately independent of [status], because a device held at a charge limit reports
     * `BATTERY_STATUS_NOT_CHARGING` while still connected.
     */
    val onCharger: Boolean get() = (plugged ?: 0) != 0

    companion object {
        val UNKNOWN = BatteryReadout()
    }
}
