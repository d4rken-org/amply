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
    val plugged: Int? = null,
    val health: Int? = null,
    val technology: String? = null,
    val temperatureTenthsC: Int? = null,
    val voltageMillivolts: Int? = null,
    val currentNowMicroamps: Int? = null,
    val chargeCounterMicroampHours: Int? = null,
    val cycleCount: Int? = null,
) {
    companion object {
        val UNKNOWN = BatteryReadout()
    }
}
