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
    /**
     * The selected adapter's hardware hold signal for this tick — see
     * [eu.darken.amply.charging.core.adapter.ChargingAdapter.hardwareHoldSignal]. True is the ONLY
     * value a CONFIRMED verdict is reachable from; false and null both leave the device under test.
     * REFUTED never consults it.
     */
    val hardwareHold: Boolean?,
    val currentNowMicroamps: Int?,
    val policyGeneration: Long,
    val plugSessionId: Long,
    val elapsedRealtimeMillis: Long,
    val wallMillis: Long,
)

/** Rolling observation state for one [EnforcementEpoch]; carried by the caller, never global. */
data class EnforcementProgress(
    val epoch: EnforcementEpoch,
    /**
     * The level the still-unbroken climb started from, or null before the epoch's first valid sample.
     * Set from ANY level, above the cap included: an epoch legitimately opens above the cap (a
     * full-charge session restored early at 84%, a process death at 82%), and refusing to track a
     * climb there is what let such a device charge on to 100% without ever being refuted. Reset to
     * the new level whenever the level drops (the climb is no longer monotonic).
     */
    val climbBase: Int?,
    /**
     * True once the level was observed increasing since [climbBase], i.e. within the CURRENT climb.
     * Phase-local on purpose: a global "rose at some point in this epoch" flag would let a rise from
     * hours ago keep vouching for a plateau that the battery has since been *losing* charge into.
     *
     * It is also what keeps an above-cap epoch from refuting on its own: a device sitting still at
     * 95% under a 70% cap is a device that stopped charging, and only an observed climb from there
     * says the cap is being ignored.
     */
    val climbRose: Boolean,
    /**
     * The level the running hold is pinned to, or null when no hold is running. A hold sustains only
     * while the level stays EXACTLY here; any change (up or down) starts a new hold instead.
     */
    val holdPercent: Int?,
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
 * **CONFIRM is hardware-corroborated only.** A passive plateau cannot distinguish a cap hold from a
 * thermal pause, a charger renegotiation or a weak supply: all of them park a plugged battery below
 * full while [StatsLimitHitDetector.heldNow] reads true, and five minutes of it is well within what
 * a hot phone on a weak charger produces. So a confirmation requires the adapter's
 * [eu.darken.amply.charging.core.adapter.ChargingAdapter.hardwareHoldSignal] to report a hold
 * ([EnforcementSample.hardwareHold] == true) *and* the behavioural evidence: a rise inside the
 * current climb, [StatsLimitHitDetector.heldNow], the level parked in the band just below the cap,
 * and that exact level sustained across [MIN_HOLD_SAMPLES] samples spanning [MIN_HOLD_MILLIS]. On an
 * adapter with no hardware signal (`hardwareHold == null`) CONFIRMED is unreachable and the device
 * simply stays under test forever — a stalled verification is a far cheaper error than claiming
 * protection that isn't there. The known alternative for such adapters is a *behavioural* challenge
 * (write a lower cap, watch charging cut, raise it, watch it resume, cut again), which no passive
 * observer can fake; it is deliberately NOT implemented here.
 *
 * **REFUTE deliberately needs no hardware corroboration at all.** It keys on a *trend*: the level
 * climbing past the cap is self-evident, whatever the hardware reports. Requiring
 * `BATTERY_STATUS_CHARGING` or a hardware signal would be a false-negative source — a ROM can carry
 * the level past the cap while reporting UNKNOWN, NOT_CHARGING or FULL, and a build with no hold
 * signal must still be refutable, or an unenforced cap would go on looking harmless. The climb is
 * tracked from ANY level, above the cap included: epochs routinely open above it (a full-charge
 * session restored early at 84%, a process death at 82%), and those are exactly the runs where an
 * unenforced cap keeps climbing to 100%.
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
     *
     * Only reachable on the hardware path: like [MIN_HOLD_SAMPLES] and [MIN_HOLD_MILLIS] it shapes a
     * confirmation, and no confirmation happens without [EnforcementSample.hardwareHold] == true.
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

    /**
     * The charge monitor evaluates roughly every 30s, so this is a plateau, not a single reading.
     * Reachable only on the hardware path (see [HOLD_BAND]).
     */
    const val MIN_HOLD_SAMPLES = 3

    /**
     * How long the hardware-corroborated hold must persist. Measured on `elapsedRealtime`, so a
     * wall-clock change cannot shorten it. This duration is NOT what separates a cap hold from a
     * thermal pause — nothing about a plateau's length can, which is why the hardware signal gates
     * the verdict; it only rejects a momentary coincidence. Reachable only on the hardware path
     * (see [HOLD_BAND]).
     */
    const val MIN_HOLD_MILLIS = 5 * 60 * 1000L

    fun evaluate(previous: EnforcementProgress?, sample: EnforcementSample): EnforcementOutcome {
        val epoch = epochOf(sample) ?: return EnforcementOutcome(null, null)
        val prior = previous?.takeIf { it.epoch == epoch }
        // An unknown level is not evidence of anything: it must not open, advance, or break a climb.
        if (sample.percent !in 0..100) return EnforcementOutcome(prior, null)

        val cap = epoch.capPercent
        val last = prior?.lastPercent
        // Phase-local climb tracking: a drop resets the base AND clears the rise, so the rise that
        // vouches for a hold is always the one that led into it. Equal samples continue the climb
        // (they are what a hold looks like) without ever establishing a rise on their own.
        val dropped = last != null && sample.percent < last
        val climbBase = when {
            last == null || dropped -> sample.percent
            else -> prior?.climbBase
        }
        val climbRose = when {
            last == null || dropped -> false
            sample.percent > last -> true
            else -> prior?.climbRose == true
        }
        // A hold pins to ONE level: "not increasing" would count a battery losing charge on a weak
        // charger as held. Any change restarts the hold; the hardware signal gates it entirely.
        val holding = sample.hardwareHold == true &&
            climbRose &&
            sample.percent in (cap - HOLD_BAND)..cap &&
            StatsLimitHitDetector.heldNow(
                plugged = sample.plugged,
                chargingStatus = sample.chargingStatus,
                batteryStatus = sample.batteryStatus,
                percent = sample.percent,
                currentNowMicroamps = sample.currentNowMicroamps,
            )
        val sustains = holding && prior?.holdPercent == sample.percent
        val holdSamples = when {
            !holding -> 0
            sustains -> (prior?.holdSamples ?: 0) + 1
            else -> 1
        }
        val holdSince = when {
            !holding -> 0L
            sustains -> prior?.holdSinceElapsedMillis ?: sample.elapsedRealtimeMillis
            else -> sample.elapsedRealtimeMillis
        }
        val progress = EnforcementProgress(
            epoch = epoch,
            climbBase = climbBase,
            climbRose = climbRose,
            holdPercent = sample.percent.takeIf { holding },
            holdSamples = holdSamples,
            holdSinceElapsedMillis = holdSince,
            lastPercent = sample.percent,
        )
        val verdict = when {
            // The refutation keys on the CLIMB, not on the level alone: a lone or flat above-cap
            // sample proves nothing (the epoch may simply have opened there), while an observed rise
            // to beyond the cap does — wherever inside or above the cap that rise started.
            climbRose && sample.percent >= cap + OVERSHOOT_ALLOWANCE -> EnforcementVerdict.REFUTED
            // hardwareHold, the rise and the band are already folded into `holding`.
            holdSamples >= MIN_HOLD_SAMPLES &&
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
