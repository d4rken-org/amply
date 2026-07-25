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
 * Current battery state observed at process start, used to reconcile a session left open by a
 * process death. Deliberately *not* a [StatsSample]: that type's contract is "built from the exact
 * intent the charge-session service evaluated", while this comes from an independent sticky read
 * before any service tick exists.
 */
data class ResumeProbe(
    val elapsedRealtimeMillis: Long,
    val bootId: Long,
    val plugged: Boolean,
    val percent: Int?,
)

/** Outcome of reconciling a dangling open session against a [ResumeProbe]. */
sealed interface ResumeDecision {

    /** Reattach [session] — the same plug event is still in progress, as far as can be told. */
    data class Resume(val session: ChargeSessionEntity) : ResumeDecision

    /** Seal the row instead; [reason] is for diagnostics only. */
    data class Reject(val reason: Reason) : ResumeDecision

    enum class Reason {
        /** Row is already sealed — nothing to reconcile. */
        CLOSED,

        /** No external power now, so whatever charge was underway has ended. */
        UNPLUGGED,

        /** Boot identity is unknown on either side, so a same-boot claim can't be made. */
        BOOT_UNKNOWN,

        /** A reboot happened; elapsed-realtime readings from two boots can't be compared. */
        BOOT_MISMATCH,

        /** Probe predates the row's last sample — the clock base can't be the one we recorded against. */
        TIME_WENT_BACKWARDS,

        /** Charge level fell while we weren't looking, so the device discharged in the gap. */
        LEVEL_DROPPED,
    }
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

    /**
     * True when a just-sealed session captured nothing worth keeping, so the recorder should delete it
     * rather than persist a spurious history row. This is exactly the "toggle record on then off while
     * already plugged" artifact: enabling mid-charge opens a session on the immediate tick, and the
     * following disable seals it with `end == start`.
     *
     * Deliberately keyed on the sealed *outcome*, not the sample count alone — a genuine plug that is
     * pulled within one cadence window (< 20 s) also has a single sample yet records a real duration
     * and/or a level change, and must be retained. A session is discardable only when it spans no
     * time, gained no charge, and recorded at most the opening sample. The sticky limit/override flags
     * are intentionally NOT a reason to keep it: a single instantaneous sample that merely observed an
     * OEM limit or an active override, with zero elapsed time and no curve, is still the empty toggle
     * artifact (a real hold/override session accrues elapsed time, so it fails the zero-duration test).
     */
    fun isDiscardable(sealed: ChargeSessionEntity): Boolean {
        val end = sealed.endedElapsedRealtimeMillis ?: sealed.startedElapsedRealtimeMillis
        val zeroDuration = end - sealed.startedElapsedRealtimeMillis <= 0
        val noLevelGain = sealed.startPercent == null ||
            sealed.endPercent == null ||
            sealed.endPercent == sealed.startPercent
        return zeroDuration && noLevelGain && sealed.runningSampleCount <= 1
    }

    /**
     * Reconcile a session left open by a process death against the battery state observed at the next
     * process start. Resuming keeps the real plug-in time and one history row for one physical charge;
     * the alternative (always sealing) restarts the session at process-launch time, which reads as a
     * wrong "Since …" and splits one charge into two rows.
     *
     * Continuity here is **inferred, not observed** — nothing survived the gap to witness it. A replug
     * at an equal-or-higher level is indistinguishable from an uninterrupted plug, and a level *drop*
     * while plugged is possible (heavy load, weak charger, thermal throttling, an OEM hold). So these
     * guards are best-effort and deliberately biased toward merging: an occasional merged plug cycle
     * beats fragmenting real sessions. Two things keep that bias honest, both applied on [Resume]:
     * the row is flagged [ChargeSessionEntity.partial], and the last power/temperature readings are
     * dropped so [fold] credits **nothing** for the unobserved gap (see the null handling in
     * [creditInterval]). A wrong merge therefore costs an over-long duration — never invented
     * power/temperature averages.
     */
    fun evaluateResume(row: ChargeSessionEntity, probe: ResumeProbe): ResumeDecision {
        val lastObserved = row.runningLastElapsedRealtimeMillis ?: row.startedElapsedRealtimeMillis
        val reason = when {
            row.endedAtWallMillis != null -> ResumeDecision.Reason.CLOSED
            !probe.plugged -> ResumeDecision.Reason.UNPLUGGED
            // Checked before equality: the sentinel compares equal to itself across *different* boots.
            probe.bootId == BootIdSource.UNAVAILABLE ||
                row.bootId == BootIdSource.UNAVAILABLE -> ResumeDecision.Reason.BOOT_UNKNOWN
            probe.bootId != row.bootId -> ResumeDecision.Reason.BOOT_MISMATCH
            probe.elapsedRealtimeMillis < lastObserved -> ResumeDecision.Reason.TIME_WENT_BACKWARDS
            droppedLevel(row.runningLastPercent, probe.percent) -> ResumeDecision.Reason.LEVEL_DROPPED
            else -> null
        }
        if (reason != null) return ResumeDecision.Reject(reason)
        return ResumeDecision.Resume(
            row.copy(
                // The curve has a hole and the continuity is inferred — never present this as a clean
                // plug→unplug history.
                partial = true,
                runningLastPowerMilliwatts = null,
                runningLastTemperatureTenthsC = null,
            ),
        )
    }

    /** True only when both readings exist and the level fell — a missing reading is not evidence. */
    private fun droppedLevel(lastPercent: Int?, probePercent: Int?): Boolean =
        lastPercent != null && probePercent != null && probePercent < lastPercent

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
