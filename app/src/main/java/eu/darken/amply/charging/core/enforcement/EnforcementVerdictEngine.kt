package eu.darken.amply.charging.core.enforcement

import eu.darken.amply.charging.core.ChargeObservation
import eu.darken.amply.charging.core.ChargePolicy
import eu.darken.amply.stats.core.StatsLimitHitDetector

/**
 * The window a verdict is made inside. Any change to the adapter, the ROM build, the configured cap,
 * the policy generation (Amply wrote something), or the plug session RESETS observation progress.
 *
 * That reset is not bookkeeping, it prevents a concrete false REFUTE: with a bare "seen below the
 * cap" watermark, a user sitting at 78% under a 80% cap who switches to a 70% cap would instantly
 * look like a device charging past its limit, and a perfectly good device would be refuted for good.
 */
data class EnforcementEpoch(
    val adapterId: String,
    val buildIdentity: String,
    val capPercent: Int,
    /** Changes whenever Amply writes a policy — see `ChargingPreferences.lastRequestedAt`. */
    val policyGeneration: Long,
    /** Monotonic counter of unplugged→plugged transitions; a new plug session starts a new window. */
    val plugSessionId: Long,
)

/** One evaluation tick, everything the verdict depends on, and nothing Android-specific. */
data class EnforcementSample(
    val adapterId: String,
    val buildIdentity: String,
    /** The adapter's *configured* state readback; only [ChargeObservation.Verified] can be evaluated. */
    val configured: ChargeObservation?,
    /** A temporary full-charge session deliberately lifts the cap, so nothing is observable then. */
    val sessionActive: Boolean,
    val plugged: Boolean,
    /** Battery level, or -1 when unknown. An unknown level moves no state at all. */
    val percent: Int,
    val batteryStatus: Int?,
    val chargingStatus: Int?,
    val currentNowMicroamps: Int?,
    val policyGeneration: Long,
    val plugSessionId: Long,
    val elapsedRealtimeMillis: Long,
    val wallMillis: Long,
)

/** Rolling observation state for one [EnforcementEpoch]; carried by the caller, never global. */
data class EnforcementProgress(
    val epoch: EnforcementEpoch,
    /** True once the level was observed strictly increasing inside this epoch. */
    val rose: Boolean,
    /**
     * The level a still-unbroken upward climb started from, or null. Only set from a sample at or
     * below the cap: a device that was already above the cap when the epoch opened proves nothing by
     * staying there. Cleared whenever the level drops (the climb is no longer monotonic).
     */
    val climbBase: Int?,
    val holdSamples: Int,
    val holdSinceElapsedMillis: Long,
    val lastPercent: Int,
)

data class EnforcementOutcome(
    val progress: EnforcementProgress?,
    val verdict: EnforcementVerdict?,
)

/**
 * Decides whether the charging hardware actually enforces a configured cap, from the public battery
 * broadcast alone. Pure and JVM-testable: the caller threads [EnforcementProgress] through.
 *
 * The asymmetry between the two verdicts is deliberate.
 *
 * **CONFIRM** needs a *sustained plateau*, never one tick. [StatsLimitHitDetector.heldNow] answers
 * "limit likely reached" and fires on any plugged, below-full, NOT_CHARGING sample — thermal
 * suspension, a weak or renegotiating charger, and a kernel charge pause all qualify. So a
 * confirmation additionally requires an observed rise inside the same epoch, the level parked in the
 * band just below the cap, and the hold to persist across [MIN_HOLD_SAMPLES] samples spanning
 * [MIN_HOLD_MILLIS]. Anything weaker leaves the device a candidate.
 *
 * **REFUTE** keys on a *trend*, not a status sample. Requiring `BATTERY_STATUS_CHARGING` would be a
 * false-negative source: a ROM can carry the level past the cap while reporting UNKNOWN, NOT_CHARGING
 * or FULL, which would leave an earlier CONFIRMED build trusted indefinitely. Battery status and
 * charge current corroborate a hold but never gate the refutation.
 */
object EnforcementVerdictEngine {

    /**
     * Bumped whenever this heuristic is tightened. Stored on every [EnforcementEvidence], so a
     * confirmation produced by a weaker version stops counting instead of being trusted forever.
     */
    const val ALGORITHM_VERSION = 1

    /**
     * How far BELOW the cap a hold still counts. Upstream's `Limit.java` sets the HAL floor to
     * `targetPct - margin` and `Toggle` resumes only after falling that far, so a device that really
     * enforces legitimately rests a few points under the number Amply wrote. Covers that margin plus
     * level-reporting rounding.
     */
    const val HOLD_BAND = 5

    /**
     * How far ABOVE the cap the level must climb before enforcement is refuted. Small and INDEPENDENT
     * of [HOLD_BAND]: upstream's margin is hysteresis *below* the target and justifies no allowance
     * above it, and every extra point here only delays detecting a device that never limits. Three
     * points absorb a one-off level-reporting overshoot without hiding a real climb. Keeping this ≥ 1
     * is also what makes the confirm band and the refute band disjoint.
     */
    const val OVERSHOOT_ALLOWANCE = 3

    /** The charge monitor evaluates roughly every 30s, so this is a plateau, not a single reading. */
    const val MIN_HOLD_SAMPLES = 3

    /**
     * A thermal pause or a charger renegotiation resolves well inside this; five minutes of a plugged,
     * non-advancing battery parked in the cap band is the cap holding. Measured on
     * `elapsedRealtime`, so a wall-clock change cannot shorten it.
     */
    const val MIN_HOLD_MILLIS = 5 * 60 * 1000L

    fun evaluate(previous: EnforcementProgress?, sample: EnforcementSample): EnforcementOutcome {
        val epoch = epochOf(sample) ?: return EnforcementOutcome(null, null)
        val prior = previous?.takeIf { it.epoch == epoch }
        // An unknown level is not evidence of anything: it must not open, advance, or break a climb.
        if (sample.percent !in 0..100) return EnforcementOutcome(prior, null)

        val cap = epoch.capPercent
        val last = prior?.lastPercent
        val rose = prior?.rose == true || (last != null && sample.percent > last)
        val climbBase = when {
            // A drop breaks the monotonic climb; the new level re-opens one only from inside the cap.
            last != null && sample.percent < last -> sample.percent.takeIf { it <= cap }
            prior?.climbBase != null -> prior.climbBase
            else -> sample.percent.takeIf { it <= cap }
        }
        val held = sample.percent in (cap - HOLD_BAND)..cap &&
            (last == null || sample.percent <= last) &&
            StatsLimitHitDetector.heldNow(
                plugged = sample.plugged,
                chargingStatus = sample.chargingStatus,
                batteryStatus = sample.batteryStatus,
                percent = sample.percent,
                currentNowMicroamps = sample.currentNowMicroamps,
            )
        val holdSamples = if (held) (prior?.holdSamples ?: 0) + 1 else 0
        val holdSince = when {
            !held -> 0L
            prior != null && prior.holdSamples > 0 -> prior.holdSinceElapsedMillis
            else -> sample.elapsedRealtimeMillis
        }
        val progress = EnforcementProgress(
            epoch = epoch,
            rose = rose,
            climbBase = climbBase,
            holdSamples = holdSamples,
            holdSinceElapsedMillis = holdSince,
            lastPercent = sample.percent,
        )
        val verdict = when {
            climbBase != null && sample.percent >= cap + OVERSHOOT_ALLOWANCE -> EnforcementVerdict.REFUTED
            rose && holdSamples >= MIN_HOLD_SAMPLES &&
                sample.elapsedRealtimeMillis - holdSince >= MIN_HOLD_MILLIS -> EnforcementVerdict.CONFIRMED
            else -> null
        }
        return EnforcementOutcome(progress, verdict)
    }

    /**
     * The window this sample belongs to, or null when nothing is observable: unplugged (no charge to
     * limit), a running full-charge session (the cap is deliberately lifted), a configured state that
     * is not a *verified* [ChargePolicy.FixedLimit] (Adaptive has no observable cap, and a merely
     * last-requested policy is not known to be configured at all), or a 100% "cap", which limits
     * nothing.
     */
    private fun epochOf(sample: EnforcementSample): EnforcementEpoch? {
        if (!sample.plugged || sample.sessionActive) return null
        val verified = sample.configured as? ChargeObservation.Verified ?: return null
        val cap = (verified.policy as? ChargePolicy.FixedLimit)?.percent ?: return null
        if (cap >= 100) return null
        return EnforcementEpoch(
            adapterId = sample.adapterId,
            buildIdentity = sample.buildIdentity,
            capPercent = cap,
            policyGeneration = sample.policyGeneration,
            plugSessionId = sample.plugSessionId,
        )
    }
}
