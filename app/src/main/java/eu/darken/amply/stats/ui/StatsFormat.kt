package eu.darken.amply.stats.ui

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
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

    /** Elapsed-time axis tick: seconds under a minute (so short sessions still show a scale), else m/h. */
    fun elapsedAxis(millis: Long): String {
        if (millis < 0) return "0s"
        if (millis < 60_000L) return "${millis / 1000L}s"
        return duration(millis) ?: "0m"
    }

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

    /**
     * Absolute local date+time for a session's wall-clock timestamp. Zone and locale are resolved per
     * call (not captured in a cached formatter) so a timezone or locale change mid-process is honoured.
     * Overload with explicit [zone]/[locale] exists for deterministic tests.
     */
    fun dateTime(
        millis: Long,
        zone: ZoneId = ZoneId.systemDefault(),
        locale: Locale = Locale.getDefault(),
    ): String = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM)
        .withLocale(locale)
        .withZone(zone)
        .format(Instant.ofEpochMilli(millis))

    // --- Chart legend "min→max unit" spans; null when the metric has no data ---

    fun percentSpan(min: Int?, max: Int?): String? =
        if (min == null || max == null) null else "$min→$max%"

    fun powerSpan(minMilliwatts: Int?, maxMilliwatts: Int?): String? {
        if (minMilliwatts == null || maxMilliwatts == null) return null
        return String.format(Locale.getDefault(), "%.1f→%.1f W", minMilliwatts / 1000.0, maxMilliwatts / 1000.0)
    }

    fun temperatureSpan(minTenthsC: Int?, maxTenthsC: Int?): String? {
        if (minTenthsC == null || maxTenthsC == null) return null
        return String.format(Locale.getDefault(), "%.1f→%.1f °C", minTenthsC / 10.0, maxTenthsC / 10.0)
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
