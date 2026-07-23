package eu.darken.amply.stats.core

import eu.darken.amply.stats.core.db.ChargeSessionEntity
import kotlin.math.roundToInt

/** What the recorder should do with a [StatsSample] given the current open-session state. */
sealed interface StatsTransition {
    /** Start a new charge session. [partial] when the charge was already underway when observed. */
    data class Open(val partial: Boolean) : StatsTransition

    /** Continue the open session; [record] gates folding + writing a curve point (see [StatsCadence]). */
    data class Append(val record: Boolean) : StatsTransition

    /** Close the open session. */
    data class Seal(val reason: StatsSealReason) : StatsTransition

    /** Unplugged with no open session — nothing to do in P1 (discharge tracking is a later phase). */
    data object Ignore : StatsTransition
}

/**
 * Pure charge-session segmentation and online aggregation. Holds no state itself: the open session
 * is a [ChargeSessionEntity] the recorder loads from (and persists to) Room, so every decision
 * survives process death and is JVM-unit-testable without Android or a database.
 *
 * Aggregates are folded online with a left-Riemann, time-weighted scheme: each inter-sample interval
 * is attributed to the value observed at its start, so irregular sample spacing (30 s polls mixed
 * with battery broadcasts) does not bias the averages. Intervals are clamped to [MAX_WEIGHT_GAP_MILLIS]
 * so a Doze gap can't massively overweight one stale reading.
 */
object StatsSessionEngine {

    /** Longest inter-sample interval credited to a single reading (10 min). */
    const val MAX_WEIGHT_GAP_MILLIS = 600_000L

    fun decide(
        hasOpenSession: Boolean,
        previousPlugged: Boolean?,
        plugged: Boolean,
        recordDue: Boolean,
    ): StatsTransition = when {
        !hasOpenSession && !plugged -> StatsTransition.Ignore
        // Clean start only when we just saw an unplugged tick; otherwise capture began mid-charge.
        !hasOpenSession && plugged -> StatsTransition.Open(partial = previousPlugged != false)
        hasOpenSession && plugged -> StatsTransition.Append(record = recordDue)
        else -> StatsTransition.Seal(StatsSealReason.UNPLUGGED)
    }

    fun open(sample: StatsSample, partial: Boolean): ChargeSessionEntity = ChargeSessionEntity(
        startedAtWallMillis = sample.wallMillis,
        startedElapsedRealtimeMillis = sample.elapsedRealtimeMillis,
        bootId = sample.bootId,
        startPercent = sample.percent,
        pluggedRaw = sample.pluggedRaw,
        // Starting already full means we missed the charge — present it as partial.
        partial = partial || sample.full,
        fullReachedAtWallMillis = if (sample.full) sample.wallMillis else null,
        runningSampleCount = 1,
        runningPeakPowerMilliwatts = sample.powerMilliwatts,
        runningMinTemperatureTenthsC = sample.temperatureTenthsC,
        runningMaxTemperatureTenthsC = sample.temperatureTenthsC,
        runningLastPowerMilliwatts = sample.powerMilliwatts,
        runningLastTemperatureTenthsC = sample.temperatureTenthsC,
        runningLastPercent = sample.percent,
        runningLastElapsedRealtimeMillis = sample.elapsedRealtimeMillis,
        runningLastWallMillis = sample.wallMillis,
        limitHitEvidence = sample.limitHeldNow,
        overrideSeen = sample.overrideActive,
    )

    fun fold(session: ChargeSessionEntity, sample: StatsSample): ChargeSessionEntity {
        val credited = creditInterval(session, sample.elapsedRealtimeMillis)
        return session.copy(
            runningSampleCount = session.runningSampleCount + 1,
            runningPowerWeightedSum = credited.powerSum,
            runningPowerWeightedDurationMillis = credited.powerDuration,
            runningTemperatureWeightedSum = credited.tempSum,
            runningTemperatureWeightedDurationMillis = credited.tempDuration,
            runningPeakPowerMilliwatts = maxNullable(session.runningPeakPowerMilliwatts, sample.powerMilliwatts),
            runningMinTemperatureTenthsC = minNullable(session.runningMinTemperatureTenthsC, sample.temperatureTenthsC),
            runningMaxTemperatureTenthsC = maxNullable(session.runningMaxTemperatureTenthsC, sample.temperatureTenthsC),
            // Do NOT carry a stale value forward: an absent reading leaves the next interval uncredited.
            runningLastPowerMilliwatts = sample.powerMilliwatts,
            runningLastTemperatureTenthsC = sample.temperatureTenthsC,
            runningLastPercent = sample.percent ?: session.runningLastPercent,
            runningLastElapsedRealtimeMillis = sample.elapsedRealtimeMillis,
            runningLastWallMillis = sample.wallMillis,
            fullReachedAtWallMillis = session.fullReachedAtWallMillis
                ?: sample.wallMillis.takeIf { sample.full },
            limitHitEvidence = session.limitHitEvidence || sample.limitHeldNow,
            overrideSeen = session.overrideSeen || sample.overrideActive,
        )
    }

    /**
     * Merge a below-cadence sample's sticky evidence ([ChargeSessionEntity.limitHitEvidence] /
     * [ChargeSessionEntity.overrideSeen]) into [session] without folding aggregates or advancing the
     * curve. Returns the updated entity, or null when neither flag changed so the recorder can skip
     * the write. A hold or override can begin and end entirely between two recorded points, so this
     * captures that evidence even when [StatsCadence] drops the sample itself.
     */
    fun latchEvidence(session: ChargeSessionEntity, sample: StatsSample): ChargeSessionEntity? {
        val limitHitEvidence = session.limitHitEvidence || sample.limitHeldNow
        val overrideSeen = session.overrideSeen || sample.overrideActive
        if (limitHitEvidence == session.limitHitEvidence && overrideSeen == session.overrideSeen) {
            return null
        }
        return session.copy(limitHitEvidence = limitHitEvidence, overrideSeen = overrideSeen)
    }

    /**
     * Close [session]. [endWallMillis]/[endElapsedMillis]/[endPercent] come from the unplug tick when
     * available, else the session's last recorded values (recovery/disable seals). The final open
     * interval is credited before averaging so the tail isn't lost.
     */
    fun seal(
        session: ChargeSessionEntity,
        reason: StatsSealReason,
        endWallMillis: Long,
        endElapsedMillis: Long,
        endPercent: Int?,
    ): ChargeSessionEntity {
        val credited = creditInterval(session, endElapsedMillis)
        val avgPower = if (credited.powerDuration > 0) {
            (credited.powerSum / credited.powerDuration).roundToInt()
        } else {
            session.runningLastPowerMilliwatts
        }
        val avgTemp = if (credited.tempDuration > 0) {
            (credited.tempSum / credited.tempDuration).roundToInt()
        } else {
            session.runningLastTemperatureTenthsC
        }
        return session.copy(
            endedAtWallMillis = endWallMillis,
            endedElapsedRealtimeMillis = endElapsedMillis,
            endReason = reason.name,
            endPercent = endPercent ?: session.runningLastPercent,
            avgPowerMilliwatts = avgPower,
            avgTemperatureTenthsC = avgTemp,
            runningPowerWeightedSum = credited.powerSum,
            runningPowerWeightedDurationMillis = credited.powerDuration,
            runningTemperatureWeightedSum = credited.tempSum,
            runningTemperatureWeightedDurationMillis = credited.tempDuration,
            partial = session.partial || reason == StatsSealReason.INTERRUPTED || reason == StatsSealReason.REBOOT,
        )
    }

    private class Credited(
        val powerSum: Double,
        val powerDuration: Long,
        val tempSum: Double,
        val tempDuration: Long,
    )

    /** Credit the interval from the last recorded sample up to [untilElapsedMillis] to the last values. */
    private fun creditInterval(session: ChargeSessionEntity, untilElapsedMillis: Long): Credited {
        val last = session.runningLastElapsedRealtimeMillis ?: untilElapsedMillis
        val dt = (untilElapsedMillis - last).coerceIn(0, MAX_WEIGHT_GAP_MILLIS)
        val prevPower = session.runningLastPowerMilliwatts
        val prevTemp = session.runningLastTemperatureTenthsC
        // Inline null checks so prevPower/prevTemp smart-cast to non-null inside each branch.
        return Credited(
            powerSum = session.runningPowerWeightedSum +
                if (prevPower != null && dt > 0) prevPower.toDouble() * dt else 0.0,
            powerDuration = session.runningPowerWeightedDurationMillis +
                if (prevPower != null && dt > 0) dt else 0L,
            tempSum = session.runningTemperatureWeightedSum +
                if (prevTemp != null && dt > 0) prevTemp.toDouble() * dt else 0.0,
            tempDuration = session.runningTemperatureWeightedDurationMillis +
                if (prevTemp != null && dt > 0) dt else 0L,
        )
    }

    private fun maxNullable(a: Int?, b: Int?): Int? = when {
        a == null -> b
        b == null -> a
        else -> maxOf(a, b)
    }

    private fun minNullable(a: Int?, b: Int?): Int? = when {
        a == null -> b
        b == null -> a
        else -> minOf(a, b)
    }
}
