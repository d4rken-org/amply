package eu.darken.amply.battery.core

import android.os.BatteryManager

/**
 * Pure mapping from raw battery extras/properties to a [BatteryReadout]. Kept Android-free (only
 * the [BatteryManager] int constants) so it is directly JVM-unit-testable.
 *
 * Convention: callers pass the platform's own "unavailable" markers unchanged and this factory
 * normalizes them to `null` — [Int.MIN_VALUE] (the documented [BatteryManager.getIntProperty]
 * sentinel), the caller's [ABSENT] marker for a missing intent extra, and out-of-range values.
 */
object BatteryReadoutFactory {

    /** Sentinel a caller passes when a sticky-intent extra is entirely absent. */
    const val ABSENT = Int.MIN_VALUE

    /**
     * Floor for a device's implied full-charge capacity, below which its charge counter is taken to be
     * milli-scaled. 100 mAh sits an order of magnitude below the smallest plausible phone or tablet cell
     * (1000 mAh ⇒ 1_000_000 µAh) and an order of magnitude above what a milli-reporting device implies
     * (HONOR's 7100 mAh cell ⇒ ~7_100 µAh), so the two populations cannot overlap.
     * See [chargeCounterLooksMilliScaled].
     */
    private const val MIN_PLAUSIBLE_FULL_CAPACITY_MICROAMP_HOURS = 100_000L


    @Suppress("LongParameterList")
    fun build(
        level: Int = ABSENT,
        scale: Int = ABSENT,
        status: Int = ABSENT,
        chargingStatus: Int = ABSENT,
        plugged: Int = ABSENT,
        health: Int = ABSENT,
        technology: String? = null,
        temperatureTenths: Int = ABSENT,
        voltageMillivolts: Int = ABSENT,
        currentNowMicroamps: Int = ABSENT,
        chargeCounterMicroampHours: Int = ABSENT,
        cycleCount: Int = ABSENT,
        maxChargingCurrentMicroamps: Int = ABSENT,
        maxChargingVoltageMicrovolts: Int = ABSENT,
        romMisreportsUnits: Boolean = false,
    ): BatteryReadout {
        val percent = percentOrNull(level, scale)
        val chargeCounter = chargeCounterMicroampHours.orNull()
        // Both conditions, never one: the ROM must be a known misreporter (see BatteryUnitCalibration)
        // AND the anomaly must be visible in this reading, so a correctly-reporting build is left alone.
        val milliScaled = romMisreportsUnits && chargeCounterLooksMilliScaled(chargeCounter, percent)
        return BatteryReadout(
            levelPercent = percent,
            status = status.orNull(),
            chargingStatus = chargingStatus.orNull(),
            plugged = plugged.orNull(),
            health = health.orNull(),
            technology = technology?.trim()?.ifEmpty { null },
            temperatureTenthsC = temperatureTenths.orNull(),
            voltageMillivolts = voltageMillivolts.orNull(),
            // Current is signed; only the MIN_VALUE/absent sentinel is dropped, negatives are kept.
            currentNowMicroamps = currentNowMicroamps.orNull()?.toMicroUnits(milliScaled),
            chargeCounterMicroampHours = chargeCounter?.toMicroUnits(milliScaled),
            cycleCount = cycleCount.orNull(),
            // The charger extras are advertised capabilities: a device that reports them while nothing is
            // connected reports 0, which is "no charger" rather than "a 0 W charger". Deliberately NOT
            // rescaled: they come from different extras than the two properties the inference is drawn
            // from, and no misreporting device has been observed populating them at all.
            maxChargingCurrentMicroamps = maxChargingCurrentMicroamps.positiveOrNull(),
            maxChargingVoltageMicrovolts = maxChargingVoltageMicrovolts.positiveOrNull(),
        )
    }

    /**
     * Whether the **charge counter** is reported in milli-units where [BatteryManager] documents
     * micro-units. Confirmed on HONOR MagicOS 10 (issue #66): `6978` at 100% on a 7100 mAh cell, i.e. the
     * UI rendered "7 mAh".
     *
     * Inferred from the data rather than a device allowlist, because branding is not the cause and the
     * affected population is unknown. The discriminator is the **implied full-charge capacity**,
     * `counter × 100 / percent`, which normalizes out the charge level: a correctly reporting phone implies
     * at least ~1_000_000 µAh (a 1000 mAh cell), a milli-reporting one single-digit thousands, and the floor
     * sits an order of magnitude from both.
     *
     * **Never sufficient on its own.** `CHARGE_COUNTER` and `CURRENT_NOW` are independent HAL fields, so an
     * impossible counter says nothing about current, and a broken or freshly-reset counter on an otherwise
     * healthy device must not license multiplying its real current by a thousand. The caller therefore also
     * requires a known-misreporting ROM — see [BatteryUnitCalibration] for why corroborating from the
     * current reading instead turned out to be unsound.
     */
    internal fun chargeCounterLooksMilliScaled(chargeCounterMicroampHours: Int?, levelPercent: Int?): Boolean {
        if (chargeCounterMicroampHours == null || chargeCounterMicroampHours <= 0) return false
        if (levelPercent == null || levelPercent <= 0) return false
        val impliedFullCapacity = chargeCounterMicroampHours.toLong() * 100L / levelPercent.toLong()
        return impliedFullCapacity < MIN_PLAUSIBLE_FULL_CAPACITY_MICROAMP_HOURS
    }


    /** Milli → micro, in [Long] so a large correct-but-misdetected value could not wrap into nonsense. */
    private fun Int.toMicroUnits(milliScaled: Boolean): Int {
        if (!milliScaled) return this
        return (toLong() * 1000L).coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong()).toInt()
    }

    /** Percent only when the level/scale pair is internally consistent; otherwise `null`. */
    private fun percentOrNull(level: Int, scale: Int): Int? {
        if (scale <= 0 || level < 0 || level > scale) return null
        return level * 100 / scale
    }

    private fun Int.orNull(): Int? = if (this == ABSENT) null else this

    private fun Int.positiveOrNull(): Int? = if (this == ABSENT || this <= 0) null else this
}
