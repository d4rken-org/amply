package eu.darken.amply.stats.core

import kotlin.math.abs

/**
 * Pure throttle deciding whether a plugged tick should be *recorded* (folded into the session's
 * online aggregates and written as a curve point). The host service already ticks roughly every 30 s
 * plus on battery broadcasts; without throttling a fast charge's frequent percent/temperature
 * broadcasts would write many near-duplicate rows.
 *
 * A tick is recorded when it is the first of a session, when the minimum interval has elapsed, or
 * when the battery level changed since the last recorded point (so charge steps are never missed).
 * Aggregates are time-weighted downstream, so the ~30 s spacing this produces stays accurate.
 */
object StatsCadence {

    const val CHARGING_MIN_INTERVAL_MILLIS = 20_000L

    fun shouldRecord(
        lastRecordedElapsedMillis: Long?,
        nowElapsedMillis: Long,
        lastRecordedPercent: Int?,
        currentPercent: Int?,
    ): Boolean {
        if (lastRecordedElapsedMillis == null) return true
        // A backwards elapsed jump (should not happen within a boot) forces a record rather than
        // silently starving the curve.
        val since = nowElapsedMillis - lastRecordedElapsedMillis
        if (since >= CHARGING_MIN_INTERVAL_MILLIS || since < 0) return true
        if (currentPercent != null && lastRecordedPercent != null &&
            abs(currentPercent - lastRecordedPercent) >= 1
        ) {
            return true
        }
        return false
    }
}
