package eu.darken.amply.battery.ui

import java.util.Locale
import kotlin.math.roundToInt

/**
 * Pure, locale-aware formatting helpers for [eu.darken.amply.battery.core.BatteryReadout] values.
 * Kept `internal` (not private) so they can be unit-tested directly; each returns `null` when the
 * input is `null` so callers can render a single "Not reported" fallback.
 */

/** Tenths-of-a-degree Celsius → "31.4 °C" (sign preserved for cold batteries). */
internal fun formatTemperature(tenthsC: Int?, locale: Locale = Locale.getDefault()): String? {
    if (tenthsC == null) return null
    return String.format(locale, "%.1f °C", tenthsC / 10.0)
}

/** Millivolts → "3.85 V". */
internal fun formatVoltage(millivolts: Int?, locale: Locale = Locale.getDefault()): String? {
    if (millivolts == null) return null
    return String.format(locale, "%.2f V", millivolts / 1000.0)
}

/**
 * Microamps → "-1234 mA", sign preserved (negative = discharging, positive = charging, per the
 * OEM's convention). Rounded to whole milliamps.
 */
internal fun formatCurrent(microamps: Int?, locale: Locale = Locale.getDefault()): String? {
    if (microamps == null) return null
    val milliamps = (microamps / 1000.0).roundToInt()
    return String.format(locale, "%d mA", milliamps)
}

/** Microamp-hours → "4321 mAh". */
internal fun formatChargeCounter(microampHours: Int?, locale: Locale = Locale.getDefault()): String? {
    if (microampHours == null) return null
    val milliampHours = (microampHours / 1000.0).roundToInt()
    return String.format(locale, "%d mAh", milliampHours)
}
