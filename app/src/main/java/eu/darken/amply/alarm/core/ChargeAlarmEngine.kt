package eu.darken.amply.alarm.core

import eu.darken.amply.monitor.core.ChargeMonitorTick

enum class ChargeAlarmDecision {
    /** Do nothing. */
    IDLE,

    /** Level reached the target while charging; alert the user to unplug. */
    FIRE,

    /** A full-charge session owns this plug cycle; mark it fired without alerting. */
    SUPPRESS,

    /** The charger was removed; clear the latch so the next charge can alert again. */
    REARM,
}

/**
 * Pure decision for the charge alarm. Stateless: the "already fired this plug cycle" latch is
 * passed in ([alreadyFired]) and persisted by the caller, so the decision survives process death —
 * a killed-and-restarted service re-derives the same outcome instead of re-alerting.
 *
 * Keyed on plugged + percent only (never the charging-status extra) so it behaves identically
 * across OEMs, including when a native limiter holds the battery "Not charging" below the target.
 */
object ChargeAlarmEngine {

    fun decide(
        tick: ChargeMonitorTick,
        targetPercent: Int,
        alreadyFired: Boolean,
    ): ChargeAlarmDecision = when {
        // Unplug is the only re-arm: clear the latch once, then stay idle until plugged again.
        !tick.plugged -> if (alreadyFired) ChargeAlarmDecision.REARM else ChargeAlarmDecision.IDLE
        // A temporary full charge is a deliberate request; never nag during it, but claim the
        // cycle so restoring at 100% (session cleared, still plugged) can't fire a late alarm.
        tick.sessionActive -> if (alreadyFired) ChargeAlarmDecision.IDLE else ChargeAlarmDecision.SUPPRESS
        alreadyFired || tick.percent < 0 || tick.percent < targetPercent -> ChargeAlarmDecision.IDLE
        else -> ChargeAlarmDecision.FIRE
    }
}
