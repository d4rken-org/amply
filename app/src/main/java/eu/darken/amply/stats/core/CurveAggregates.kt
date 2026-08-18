package eu.darken.amply.stats.core

import kotlin.math.roundToInt

/**
 * The min / time-weighted average / max of one metric across a session, plus how many samples
 * actually carried a value for it. A metric with no readings at all has no [MetricStats] — the
 * caller hides the row rather than printing zeros.
 */
data class MetricStats(
    val min: Int,
    val avg: Int,
    val max: Int,
    val sampleCount: Int,
)

/** Per-metric statistics for one session's curve. A null entry means the metric was never reported. */
data class CurveAggregates(
    val level: MetricStats? = null,
    val power: MetricStats? = null,
    val voltage: MetricStats? = null,
    val current: MetricStats? = null,
    val temperature: MetricStats? = null,
) {

    companion object {
        val EMPTY = CurveAggregates()

        /**
         * Aggregate one session's **raw, undecimated** points.
         *
         * Feeding this a decimated curve would be wrong: `StatsDownsampler.decimate` thins with a
         * uniform stride, so a brief temperature or current extreme is dropped outright and a
         * "Maximum" computed from the survivors is not the session's maximum.
         */
        fun of(points: List<ChargeCurvePoint>): CurveAggregates = CurveAggregates(
            level = points.statsOf { it.percent },
            power = points.statsOf { it.powerMilliwatts },
            voltage = points.statsOf { it.voltageMillivolts },
            current = points.statsOf { it.currentNowMicroamps },
            temperature = points.statsOf { it.temperatureTenthsC },
        )
    }
}

/**
 * Longest inter-sample interval credited to a single reading, mirroring
 * [StatsSessionEngine.MAX_WEIGHT_GAP_MILLIS] so a Doze gap can't massively overweight one stale
 * value here either.
 */
private const val MAX_WEIGHT_GAP_MILLIS = StatsSessionEngine.MAX_WEIGHT_GAP_MILLIS

/**
 * One metric's statistics over [this].
 *
 * The average is **time-weighted** (left-Riemann: each sample is credited the interval up to the
 * next one), not a plain mean. `StatsCadence` records on every level change as well as on its
 * timer, so samples are denser while charging fast — a plain mean would over-weight exactly that
 * stretch. It also matches how `ChargeSessionSummary.avgPowerMilliwatts` is folded online, so one
 * session can never show two different "average power" figures on two screens.
 *
 * Sums accumulate in [Double]/[Long]: `currentNowMicroamps` in the millions overflows an `Int` sum
 * after a few hundred samples.
 */
private fun List<ChargeCurvePoint>.statsOf(select: (ChargeCurvePoint) -> Int?): MetricStats? {
    var min = Int.MAX_VALUE
    var max = Int.MIN_VALUE
    var count = 0
    var plainSum = 0L
    var weightedSum = 0.0
    var weightedDuration = 0L

    forEachIndexed { index, point ->
        val value = select(point) ?: return@forEachIndexed
        count++
        plainSum += value
        if (value < min) min = value
        if (value > max) max = value
        val next = getOrNull(index + 1) ?: return@forEachIndexed
        // Negative (clock went backwards) collapses to zero rather than subtracting weight.
        val dt = (next.elapsedFromStartMillis - point.elapsedFromStartMillis)
            .coerceIn(0L, MAX_WEIGHT_GAP_MILLIS)
        if (dt > 0) {
            weightedSum += value.toDouble() * dt
            weightedDuration += dt
        }
    }

    if (count == 0) return null
    // No interval carried weight (a single sample, or every gap zero-length): the plain mean is the
    // only honest answer, and it degenerates to the value itself for one sample.
    val avg = if (weightedDuration > 0) {
        (weightedSum / weightedDuration).roundToInt()
    } else {
        (plainSum.toDouble() / count).roundToInt()
    }
    return MetricStats(min = min, avg = avg, max = max, sampleCount = count)
}

/** A session's decimated curve paired with the aggregates taken from its raw samples. */
data class SessionMetricData(
    val curve: List<ChargeCurvePoint>,
    val aggregates: CurveAggregates,
)
