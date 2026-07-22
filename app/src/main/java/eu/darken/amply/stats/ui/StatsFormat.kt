package eu.darken.amply.stats.ui

import eu.darken.amply.stats.core.ChargingType
import java.util.Locale

/** Number/label formatting for the statistics UI. Units are formatted inline; labels use resources. */
object StatsFormat {

    fun duration(millis: Long?): String? {
        if (millis == null || millis < 0) return null
        val totalMinutes = millis / 60_000
        val hours = totalMinutes / 60
        val minutes = totalMinutes % 60
        return if (hours > 0) "${hours}h ${minutes}m" else "${minutes}m"
    }

    fun power(milliwatts: Int?): String? =
        milliwatts?.let { String.format(Locale.getDefault(), "%.1f W", it / 1000.0) }

    fun temperature(tenthsC: Int?): String? =
        tenthsC?.let { String.format(Locale.getDefault(), "%.1f °C", it / 10.0) }

    fun percentRange(start: Int?, end: Int?): String {
        val from = start?.let { "$it%" } ?: "—"
        val to = end?.let { "$it%" } ?: "—"
        return "$from → $to"
    }

    fun temperatureRange(min: Int?, max: Int?): String? {
        val minText = temperature(min)
        val maxText = temperature(max)
        return when {
            minText != null && maxText != null -> "$minText – $maxText"
            else -> minText ?: maxText
        }
    }
}

/** Maps a [ChargingType] to a user-facing string-resource id. */
fun chargingTypeLabel(type: ChargingType): Int = when (type) {
    ChargingType.AC -> eu.darken.amply.R.string.stats_charging_type_ac
    ChargingType.USB -> eu.darken.amply.R.string.stats_charging_type_usb
    ChargingType.WIRELESS -> eu.darken.amply.R.string.stats_charging_type_wireless
    ChargingType.DOCK -> eu.darken.amply.R.string.stats_charging_type_dock
    ChargingType.MIXED -> eu.darken.amply.R.string.stats_charging_type_mixed
    ChargingType.UNKNOWN -> eu.darken.amply.R.string.stats_charging_type_unknown
}
