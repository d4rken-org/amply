package eu.darken.amply.stats.core

/**
 * Pure retention window for recorded charge history: how far back entries are kept, and the wall-time
 * cutoff derived from it. Whole entries expire — a session plus its cascading samples — so the user's
 * setting means what it says ("keep history for N days") rather than silently keeping summaries
 * forever.
 *
 * Both functions clamp, so a corrupted or out-of-range stored value can never widen the window past
 * [MAX_DAYS] or collapse it to zero (which would wipe history on the next purge).
 *
 * Two accepted semantics:
 * - **Pre-launch data loss.** Session rows used to live forever, so the first purge on an existing
 *   development install drops entries older than the window without warning. Amply is pre-launch
 *   (`0.1.0-beta1`, no release cut) and stored preferences were already broken without migration, so
 *   there is no install base to protect and no first-run grace period is granted.
 * - **Wall-clock dependence.** The cutoff and `ChargeSessionEntity.endedAtWallMillis` are both wall
 *   time, so a large forward clock jump can expire otherwise-recent history. Guarding that would need
 *   a monotonic anchor the database does not carry — not worth it for a history feature.
 */
object StatsRetention {

    const val MIN_DAYS = 3
    const val MAX_DAYS = 14
    const val DEFAULT_DAYS = 14

    fun clampDays(days: Int): Int = days.coerceIn(MIN_DAYS, MAX_DAYS)

    fun cutoffWallMillis(nowWallMillis: Long, days: Int): Long =
        nowWallMillis - clampDays(days) * 24L * 60 * 60 * 1000
}
