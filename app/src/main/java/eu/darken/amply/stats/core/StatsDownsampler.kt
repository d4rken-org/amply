package eu.darken.amply.stats.core

/**
 * Pure, API-26-safe curve decimation. Deliberately avoids SQL window functions (not uniformly
 * available on older device SQLite) — the projection query returns all of a session's points and
 * this thins them in memory to at most [maxPoints], always keeping the first and last so the curve's
 * endpoints are exact.
 */
object StatsDownsampler {

    fun <T> decimate(points: List<T>, maxPoints: Int): List<T> {
        if (maxPoints < 2 || points.size <= maxPoints) return points
        val lastIndex = points.size - 1
        // Uniform stride across the interior; first and last are pinned.
        val result = ArrayList<T>(maxPoints)
        val step = lastIndex.toDouble() / (maxPoints - 1)
        var previousIndex = -1
        for (i in 0 until maxPoints) {
            val index = if (i == maxPoints - 1) lastIndex else Math.round(i * step).toInt()
            if (index != previousIndex) {
                result.add(points[index])
                previousIndex = index
            }
        }
        return result
    }
}
