package eu.darken.amply.stats.core

/** Which history a projection was drawn from — same charger type, or every type pooled together. */
enum class ChargeTimeBasis { SAME_TYPE, POOLED }

/**
 * A charge broken into the stretches that behave differently: the bulk phase, the taper, and the
 * slow top-up. Each is **independently nullable**, because requiring full 0-100 coverage would hide
 * the whole breakdown from anyone who never drains below 20%.
 */
data class ChargeBandSplit(
    val toFiftyMillis: Long? = null,
    val fiftyToEightyMillis: Long? = null,
    val eightyToHundredMillis: Long? = null,
) {
    val hasAny: Boolean
        get() = toFiftyMillis != null || fiftyToEightyMillis != null || eightyToHundredMillis != null
}

/**
 * What a charge from the current level is expected to take, projected from recorded history.
 *
 * A null target is never a zero: it means Amply has not watched enough charges across that stretch
 * to say, or (for [toEightyMillis] at 80% and above) that there is nothing left to count down to.
 * A user who always unplugs at 80% therefore gets a null [toFullMillis] rather than an extrapolation
 * over a stretch their device has never been observed doing.
 */
data class ChargeTimeEstimate(
    val toEightyMillis: Long?,
    val toFullMillis: Long?,
    /** Median of the contributing sessions' recorded (already time-weighted) average power. */
    val avgSpeedMilliwatts: Int?,
    val split: ChargeBandSplit,
    /** Distinct contributing sessions — never the global finished-session count. */
    val basedOnSessions: Int,
)

/** An estimate plus which history it came from. */
data class ChargeTimeProjection(
    val estimate: ChargeTimeEstimate,
    val basis: ChargeTimeBasis,
)

/**
 * One stratum of the model: the usable bands and who contributed to them.
 *
 * [bands] maps a band's start level (0, 10, … 90) to the median across sessions of that session's
 * mean milliseconds per 1% inside the band. A band that only one session ever crossed is **not**
 * here: a single charge produces ~10 observations inside one band, so counting observations would
 * let one charge masquerade as corroborated history.
 */
data class ChargeTimeStratum(
    val bands: Map<Int, Long> = emptyMap(),
    val sessionIds: Set<Long> = emptySet(),
    val avgSpeedMilliwatts: Int? = null,
) {
    val hasData: Boolean get() = bands.isNotEmpty()
}

/** The expensive part of the estimate: everything folded out of history, independent of the level. */
data class ChargeTimeModel(
    val byType: Map<ChargingType, ChargeTimeStratum> = emptyMap(),
    val pooled: ChargeTimeStratum = ChargeTimeStratum(),
    /** Distinct sessions that produced any observation at all, usable or not. */
    val observedSessions: Int = 0,
) {
    val hasData: Boolean get() = pooled.hasData || byType.values.any { it.hasData }
}

/**
 * Projects charge times from recorded 1% steps.
 *
 * Split in two on purpose: [buildModel] is the history fold and only has to rerun when history
 * changes, while [project] is a cheap pure function of the current level. That is what lets the
 * estimate actually count down as the battery fills, instead of being pinned to whatever level it
 * happened to be built at.
 */
object ChargeTimeEstimator {

    /** Bands are 10% wide: wide enough for several sessions to overlap, narrow enough to taper. */
    const val BAND_SIZE = 10

    /** A band needs this many distinct sessions before it may be used. */
    const val MIN_SESSIONS_PER_BAND = 2

    fun bandOf(percent: Int): Int = (percent.coerceIn(0, 99) / BAND_SIZE) * BAND_SIZE

    fun buildModel(
        observations: List<BandObservation>,
        sessionPowerMilliwatts: Map<Long, Int> = emptyMap(),
    ): ChargeTimeModel {
        if (observations.isEmpty()) return ChargeTimeModel()
        return ChargeTimeModel(
            byType = observations
                .groupBy { it.chargingType }
                .mapValues { (_, forType) -> stratum(forType, sessionPowerMilliwatts) },
            pooled = stratum(observations, sessionPowerMilliwatts),
            observedSessions = observations.map { it.sessionId }.distinct().size,
        )
    }

    /**
     * The projection for [currentPercent] on [chargingType].
     *
     * Falls back to the pooled history when the same-type stratum cannot answer either target from
     * here — a median mixing a slow wireless charge with a fast wired one describes neither, so the
     * caller is told which it got and says so.
     */
    fun project(
        model: ChargeTimeModel,
        currentPercent: Int,
        chargingType: ChargingType,
    ): ChargeTimeProjection? {
        if (!model.hasData) return null
        val level = currentPercent.coerceIn(0, 100)
        val sameType = model.byType[chargingType]
        val sameTypeEstimate = sameType?.let { estimate(it, level) }
        if (sameTypeEstimate != null && (sameTypeEstimate.toEightyMillis != null || sameTypeEstimate.toFullMillis != null)) {
            return ChargeTimeProjection(sameTypeEstimate, ChargeTimeBasis.SAME_TYPE)
        }
        return ChargeTimeProjection(estimate(model.pooled, level), ChargeTimeBasis.POOLED)
    }

    private fun estimate(stratum: ChargeTimeStratum, level: Int): ChargeTimeEstimate = ChargeTimeEstimate(
        // Nothing to count down to once the target is behind us.
        toEightyMillis = if (level >= 80) null else span(stratum, level, 80),
        toFullMillis = if (level >= 100) null else span(stratum, level, 100),
        avgSpeedMilliwatts = stratum.avgSpeedMilliwatts,
        split = ChargeBandSplit(
            toFiftyMillis = span(stratum, 0, 50),
            fiftyToEightyMillis = span(stratum, 50, 80),
            eightyToHundredMillis = span(stratum, 80, 100),
        ),
        basedOnSessions = stratum.sessionIds.size,
    )

    /** Sum of the per-percent rates from [from] to [to]; null as soon as one band is unusable. */
    private fun span(stratum: ChargeTimeStratum, from: Int, to: Int): Long? {
        if (from >= to) return null
        var total = 0L
        for (percent in from until to) {
            total += stratum.bands[bandOf(percent)] ?: return null
        }
        return total
    }

    private fun stratum(
        observations: List<BandObservation>,
        sessionPowerMilliwatts: Map<Long, Int>,
    ): ChargeTimeStratum {
        val bands = mutableMapOf<Int, Long>()
        val contributors = mutableSetOf<Long>()

        observations.groupBy { bandOf(it.percentFrom) }.forEach { (band, inBand) ->
            // One rate per session first, so a session that crossed the band slowly contributes one
            // slow vote rather than ten.
            val perSession = inBand.groupBy { it.sessionId }
                .mapValues { (_, steps) -> steps.sumOf { it.millis } / steps.size }
            if (perSession.size < MIN_SESSIONS_PER_BAND) return@forEach
            bands[band] = median(perSession.values.toList())
            contributors += perSession.keys
        }

        return ChargeTimeStratum(
            bands = bands,
            sessionIds = contributors,
            // The speed figure describes the same charges the durations do, so it is a median over
            // the contributing sessions' own recorded averages — not a recomputation.
            avgSpeedMilliwatts = contributors
                .mapNotNull { sessionPowerMilliwatts[it] }
                .takeIf { it.isNotEmpty() }
                ?.let { median(it.map(Int::toLong)).toInt() },
        )
    }

    private fun median(values: List<Long>): Long {
        val sorted = values.sorted()
        val middle = sorted.size / 2
        return if (sorted.size % 2 == 1) {
            sorted[middle]
        } else {
            // Averaged rather than "lower middle": with two sessions — the common case — taking one
            // side would discard half the evidence.
            (sorted[middle - 1] + sorted[middle]) / 2
        }
    }
}
