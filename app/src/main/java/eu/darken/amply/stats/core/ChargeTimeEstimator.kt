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
) {
    val hasAnyTarget: Boolean get() = toEightyMillis != null || toFullMillis != null
}

/** An estimate plus which history it came from. */
data class ChargeTimeProjection(
    val estimate: ChargeTimeEstimate,
    val basis: ChargeTimeBasis,
)

/**
 * One usable band: the median across sessions of that session's mean milliseconds per 1% inside it,
 * **with** the sessions that produced that median.
 *
 * The contributors are stored per band rather than per stratum because a figure may only be
 * described by the charges behind it: unioning every usable band's contributors would let a session
 * that covered one isolated band count towards a duration it contributed nothing to.
 */
data class ChargeTimeBand(
    val medianMillisPerPercent: Long,
    val sessionIds: Set<Long>,
)

/**
 * One stratum of the model: the usable bands, keyed by their start level (0, 10, … 90).
 *
 * A band that only one session ever crossed is **not** here: a single charge produces ~10
 * observations inside one band, so counting observations would let one charge masquerade as
 * corroborated history.
 *
 * [sessionPowerMilliwatts] carries the contributing sessions' own recorded averages, so the speed
 * figure can be narrowed to whichever of them actually stand behind a projection.
 */
data class ChargeTimeStratum(
    val bands: Map<Int, ChargeTimeBand> = emptyMap(),
    val sessionPowerMilliwatts: Map<Long, Int> = emptyMap(),
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
     * Falls back to the pooled history only when pooling actually answers a target the same-type
     * history could not — a median mixing a slow wireless charge with a fast wired one describes
     * neither, so it is worth the trade only when it buys a figure, and the caller is told which it
     * got and says so.
     *
     * The condition is not "the same-type stratum produced a target": [toEightyMillis] is null by
     * rule from 80% up, so at 82% with same-type history that stops below 80 both targets are null
     * and a target-only test would label the card "across all charger types" (and render the pooled
     * split) although the same-type history is the better description of everything else on it.
     */
    fun project(
        model: ChargeTimeModel,
        currentPercent: Int,
        chargingType: ChargingType,
    ): ChargeTimeProjection? {
        if (!model.hasData) return null
        val level = currentPercent.coerceIn(0, 100)
        val sameTypeEstimate = model.byType[chargingType]?.let { estimate(it, level) }
        val pooledEstimate = estimate(model.pooled, level)
        if (sameTypeEstimate != null &&
            (sameTypeEstimate.hasAnyTarget || pooledEstimate?.hasAnyTarget != true)
        ) {
            return ChargeTimeProjection(sameTypeEstimate, ChargeTimeBasis.SAME_TYPE)
        }
        return pooledEstimate?.let { ChargeTimeProjection(it, ChargeTimeBasis.POOLED) }
    }

    /**
     * The figures for [level], plus the provenance of exactly those figures.
     *
     * Null when nothing at all could be projected — no target and no split segment. A card whose
     * "From N charges" line described bands that produced nothing displayed would be worse than the
     * not-enough-data state it replaces.
     */
    private fun estimate(stratum: ChargeTimeStratum, level: Int): ChargeTimeEstimate? {
        // Nothing to count down to once the target is behind us.
        val toEighty = if (level >= 80) null else span(stratum, level, 80)
        val toFull = if (level >= 100) null else span(stratum, level, 100)
        val toFifty = span(stratum, 0, 50)
        val fiftyToEighty = span(stratum, 50, 80)
        val eightyToHundred = span(stratum, 80, 100)

        val shown = listOfNotNull(toEighty, toFull, toFifty, fiftyToEighty, eightyToHundred)
        if (shown.isEmpty()) return null

        // Only the spans that actually produced a figure: those charges, and no others, are what the
        // provenance line and the speed median describe.
        val contributors = shown.flatMapTo(mutableSetOf()) { it.sessionIds }
        return ChargeTimeEstimate(
            toEightyMillis = toEighty?.millis,
            toFullMillis = toFull?.millis,
            avgSpeedMilliwatts = contributors
                .mapNotNull { stratum.sessionPowerMilliwatts[it] }
                .takeIf { it.isNotEmpty() }
                ?.let { median(it.map(Int::toLong)).toInt() },
            split = ChargeBandSplit(
                toFiftyMillis = toFifty?.millis,
                fiftyToEightyMillis = fiftyToEighty?.millis,
                eightyToHundredMillis = eightyToHundred?.millis,
            ),
            basedOnSessions = contributors.size,
        )
    }

    /** A projected stretch: its duration and the sessions the bands it consumed were medians of. */
    private data class Span(val millis: Long, val sessionIds: Set<Long>)

    /** Sum of the per-percent rates from [from] to [to]; null as soon as one band is unusable. */
    private fun span(stratum: ChargeTimeStratum, from: Int, to: Int): Span? {
        if (from >= to) return null
        var total = 0L
        val contributors = mutableSetOf<Long>()
        for (percent in from until to) {
            val band = stratum.bands[bandOf(percent)] ?: return null
            total += band.medianMillisPerPercent
            contributors += band.sessionIds
        }
        return Span(millis = total, sessionIds = contributors)
    }

    private fun stratum(
        observations: List<BandObservation>,
        sessionPowerMilliwatts: Map<Long, Int>,
    ): ChargeTimeStratum {
        val bands = mutableMapOf<Int, ChargeTimeBand>()
        val contributors = mutableSetOf<Long>()

        observations.groupBy { bandOf(it.percentFrom) }.forEach { (band, inBand) ->
            // One rate per session first, so a session that crossed the band slowly contributes one
            // slow vote rather than ten.
            val perSession = inBand.groupBy { it.sessionId }
                .mapValues { (_, steps) -> steps.sumOf { it.millis } / steps.size }
            if (perSession.size < MIN_SESSIONS_PER_BAND) return@forEach
            bands[band] = ChargeTimeBand(
                medianMillisPerPercent = median(perSession.values.toList()),
                sessionIds = perSession.keys.toSet(),
            )
            contributors += perSession.keys
        }

        return ChargeTimeStratum(
            bands = bands,
            // Kept for the sessions behind a usable band only; the projection narrows it further to
            // the ones behind the figures it actually shows.
            sessionPowerMilliwatts = sessionPowerMilliwatts.filterKeys { it in contributors },
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
