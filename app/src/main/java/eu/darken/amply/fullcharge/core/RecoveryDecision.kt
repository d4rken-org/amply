package eu.darken.amply.fullcharge.core

import eu.darken.amply.charging.core.BackendKind
import eu.darken.amply.charging.core.ChargeObservation
import eu.darken.amply.charging.core.ChargePolicy

enum class RecoveryDecision {
    WAIT,
    REWRITE,
    DONE_OK,
    GIVE_UP,
}

/**
 * Boot-time restore writes can race Settings Intelligence's content-observer registration and
 * silently never reach the charging HAL. This engine drives a bounded verify/re-write loop.
 *
 * Two tracks:
 * - Strict: divergence is genuinely detectable, so re-write until the budget runs out and only
 *   then alarm. Absence of the long-life hardware state only counts as evidence while plugged
 *   near/above the limit; a contradicting hardware read counts at any level.
 * - Nudge: divergence is not detectable (unplugged, below-limit, or a target without a distinct
 *   hardware state). One delayed re-write re-triggers the observer once the system is up; never
 *   alarm without evidence.
 */
object BootRecoveryEngine {
    const val TICK_MILLIS = 5_000L
    const val VERIFY_DELAY_MILLIS = 25_000L
    const val NUDGE_DELAY_MILLIS = 75_000L
    const val MAX_REWRITES = 3
    const val TOTAL_BUDGET_MILLIS = 150_000L
    const val NEAR_LIMIT_PERCENT = 79

    fun decide(
        target: ChargePolicy,
        plugged: Boolean,
        percent: Int,
        observation: ChargeObservation?,
        sinceLastWriteMillis: Long,
        totalElapsedMillis: Long,
        rewriteCount: Int,
    ): RecoveryDecision {
        val hardwareRead = (observation as? ChargeObservation.Verified)
            ?.takeIf { it.backend == BackendKind.BATTERY_HARDWARE }
        if (hardwareRead?.policy == target) return RecoveryDecision.DONE_OK

        val contradicted = plugged && hardwareRead != null
        val absenceIsEvidence = plugged &&
            target is ChargePolicy.FixedLimit &&
            percent >= NEAR_LIMIT_PERCENT
        return if (contradicted || absenceIsEvidence) {
            when {
                sinceLastWriteMillis < VERIFY_DELAY_MILLIS -> RecoveryDecision.WAIT
                totalElapsedMillis >= TOTAL_BUDGET_MILLIS -> RecoveryDecision.GIVE_UP
                rewriteCount < MAX_REWRITES -> RecoveryDecision.REWRITE
                else -> RecoveryDecision.WAIT
            }
        } else {
            when {
                rewriteCount >= 1 -> RecoveryDecision.DONE_OK
                sinceLastWriteMillis >= NUDGE_DELAY_MILLIS -> RecoveryDecision.REWRITE
                else -> RecoveryDecision.WAIT
            }
        }
    }
}
