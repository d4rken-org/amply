package eu.darken.amply.stats.core

/**
 * Pure retention window for recorded charge history: how far back entries are kept, and the wall-time
 * cutoff derived from it. Whole entries expire — a session plus its cascading samples — so the user's
 * setting means what it says ("keep history for N days") rather than silently keeping summaries
 * past it.
 *
 * The window is one of [PRESETS]; [FOREVER] is a sentinel for "never expire" and yields a cutoff no
 * wall stamp can fall below. Any other stored value is [normalize]d onto a preset:
 * - `10` → `14`, `4` → `7`: a value between presets snaps **up**, so normalizing never narrows a
 *   window the user already had (which would delete data on the next purge).
 * - `0`, `-7` → [MIN_DAYS]: a corrupted value can never collapse the window to nothing.
 * - `400` → [MAX_DAYS]: a finite value never turns into [FOREVER].
 *
 * **Wall-clock dependence.** The cutoff and `ChargeSessionEntity.endedAtWallMillis` are both wall
 * time, so a large forward clock jump can expire otherwise-recent history. Guarding that would need
 * a monotonic anchor the database does not carry — not worth it for a history feature.
 */
object StatsRetention {

    const val FOREVER = Int.MAX_VALUE

    val PRESETS: List<Int> = listOf(3, 7, 14, 30, 90, 180, 365, FOREVER)

    const val MIN_DAYS = 3
    const val MAX_DAYS = 365
    const val DEFAULT_DAYS = 14

    fun isForever(days: Int): Boolean = days == FOREVER

    fun normalize(days: Int): Int = when {
        isForever(days) -> FOREVER
        days <= MIN_DAYS -> MIN_DAYS
        days > MAX_DAYS -> MAX_DAYS
        else -> PRESETS.first { it >= days }
    }

    fun presetIndexOf(days: Int): Int = PRESETS.indexOf(normalize(days))

    fun cutoffWallMillis(nowWallMillis: Long, days: Int): Long {
        val window = normalize(days)
        if (isForever(window)) return Long.MIN_VALUE
        return nowWallMillis - window * DAY_MILLIS
    }

    private const val DAY_MILLIS = 24L * 60 * 60 * 1000
}
