package eu.darken.amply.charging.core.enforcement

import eu.darken.amply.charging.core.ChargeObservation
import eu.darken.amply.charging.core.ChargePolicy

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
     * hours ago justify refuting a level the battery has merely been sitting at since.
     *
     * It is what keeps an above-cap epoch from refuting on its own: a device sitting still at 95%
     * under a 70% cap is a device that stopped charging, and only an observed climb from there says
     * the cap is being ignored.
     */
    val climbRose: Boolean,
    val lastPercent: Int,
)

data class EnforcementOutcome(
    val progress: EnforcementProgress?,
    val verdict: EnforcementVerdict?,
)

/**
 * Decides whether the charging hardware **fails** to enforce a configured cap, from the public
 * battery broadcast alone. Pure and JVM-testable: the caller threads [EnforcementProgress] through.
 *
 * **There is only one verdict, [EnforcementVerdict.REFUTED]. Nothing observable can confirm a cap.**
 * That is a measured conclusion, not caution: `BatteryManager.EXTRA_CHARGING_STATUS` == 4 looked like
 * a hardware hold signal — a Pixel 6 (`oriole`, LineageOS 23.2) holding at a 70% cap reports it — but
 * it is *session-scoped*. On the same device, raising the cap to 80 left the extra reading 4 while
 * the battery was actively charging at level 70, ten points below the cap. It means "limit mode is
 * enabled for this plug session", not "charging is stopped right now", which is exactly what
 * `StatsLimitHitDetector`'s KDoc documents for Pixel. Nothing else in the broadcast discriminates
 * either: between a cap hold and a thermal or weak-supply pause the only field that differs is
 * `BatteryManager.EXTRA_STATUS`, which a thermal pause produces too. So a passive observer cannot
 * tell "the cap is working" from "charging happens to be paused", and Amply never claims a cap is
 * verified from observation.
 *
 * The known way to earn a real confirmation is a **guided two-cap challenge**: write a cap below the
 * current level and watch charging cut, raise it and watch charging resume, cut again — a sequence
 * no thermal pause can imitate. It needs the user to keep the device plugged through a scripted
 * write sequence, and it is deliberately NOT implemented here.
 *
 * **REFUTE needs no hardware corroboration at all.** It keys on a *trend*: the level climbing past
 * the cap is self-evident, whatever the hardware reports. Requiring `BATTERY_STATUS_CHARGING` or a
 * hardware signal would be a false-negative source — a ROM can carry the level past the cap while
 * reporting UNKNOWN, NOT_CHARGING or FULL. The climb is tracked from ANY level, above the cap
 * included: epochs routinely open above it (a full-charge session restored early at 84%, a process
 * death at 82%), and those are exactly the runs where an unenforced cap keeps climbing to 100%.
 */
object EnforcementVerdictEngine {

    /**
     * Bumped whenever this heuristic materially changes. Stored on every [EnforcementEvidence], so a
     * verdict produced by a different version stops counting instead of being trusted forever.
     */
    const val ALGORITHM_VERSION = 1

    /**
     * How far ABOVE the cap the level must climb before enforcement is refuted. Upstream's
     * `Limit.java` margin is hysteresis *below* the target and justifies no allowance above it, and
     * every extra point here only delays detecting a device that never limits. Three points absorb a
     * one-off level-reporting overshoot without hiding a real climb.
     */
    const val OVERSHOOT_ALLOWANCE = 3

    fun evaluate(previous: EnforcementProgress?, sample: EnforcementSample): EnforcementOutcome {
        val epoch = epochOf(sample) ?: return EnforcementOutcome(null, null)
        val prior = previous?.takeIf { it.epoch == epoch }
        // An unknown level is not evidence of anything: it must not open, advance, or break a climb.
        if (sample.percent !in 0..100) return EnforcementOutcome(prior, null)

        val cap = epoch.capPercent
        // Phase-local climb tracking: a drop resets the base AND clears the rise, so the rise a
        // refutation rests on is always the one that led into the current level. Equal samples
        // continue the climb without ever establishing a rise on their own.
        val dropped = prior != null && sample.percent < prior.lastPercent
        val climbBase = if (prior == null || dropped) sample.percent else prior.climbBase
        val climbRose = when {
            prior == null || dropped -> false
            sample.percent > prior.lastPercent -> true
            else -> prior.climbRose
        }
        val progress = EnforcementProgress(
            epoch = epoch,
            climbBase = climbBase,
            climbRose = climbRose,
            lastPercent = sample.percent,
        )
        // The refutation keys on the CLIMB, not on the level alone: a lone or flat above-cap sample
        // proves nothing (the epoch may simply have opened there), while an observed rise to beyond
        // the cap does — wherever inside or above the cap that rise started.
        val verdict = EnforcementVerdict.REFUTED
            .takeIf { climbRose && sample.percent >= cap + OVERSHOOT_ALLOWANCE }
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
