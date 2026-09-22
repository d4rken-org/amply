package eu.darken.amply.fullcharge.core

import eu.darken.amply.common.debug.logging.Logging
import eu.darken.amply.common.debug.logging.log
import eu.darken.amply.common.debug.logging.logTag
import kotlinx.coroutines.CancellationException

/**
 * Sequencing and error policy for the recovery job that drives [BootRecoveryFlow].
 *
 * The job runs on a [kotlinx.coroutines.SupervisorJob] scope without an exception handler — which
 * isolates siblings but does not consume an uncaught exception — and it runs with monitoring
 * quiesced, so no poll is left to reconcile the "restoring" foreground state. Both halves of the
 * policy follow from that:
 *
 * - An ordinary exception anywhere in the body is logged and then answered with a best-effort
 *   warning. [BootRecoveryFlow] keeps its pending target when a re-write fails so the next service
 *   start retries it, and the warning is what tells the user a protective write is still owed —
 *   stopping silently would be worse than the alternative. A failed run is never reported as
 *   converged.
 * - [Hooks.continueOrStop] runs in a `finally`, so it happens on both paths. It reads storage
 *   itself, so the same failure can break it too; [Hooks.lastResort] is what then ends the
 *   foreground state.
 *
 * A [CancellationException] is rethrown unchanged at every stage and posts no warning: a recovery
 * cancelled by a newer command is not a failed one.
 *
 * Free of Android types and generic over the pickup bookkeeping [P], so it stays JVM-testable.
 */
internal class RecoveryJobFlow<P>(private val hooks: Hooks<P>) {

    interface Hooks<P> {
        /** Bookkeeping about the work being picked up, captured before the flow mutates it. */
        suspend fun capturePickup(): P

        /** Whatever must be in place before the flow's first write. */
        suspend fun prepare()

        suspend fun runRecovery(): BootRecoveryFlow.Result

        /** The protective policy is confirmed — drop any lingering alarm. */
        fun onConverged()

        suspend fun onFinished(pickup: P, result: BootRecoveryFlow.Result)

        /** Warn that a protective write is still owed. */
        fun warnRecoveryOwed()

        /** Terminal continuation: hand the service back to monitoring or stop it. */
        suspend fun continueOrStop()

        /** Drop the foreground notification and end the service, without touching dispatch state. */
        fun lastResort()
    }

    suspend fun run() {
        try {
            val pickup = hooks.capturePickup()
            hooks.prepare()
            val result = hooks.runRecovery()
            log(TAG) { "Boot recovery outcome: ${result.outcome}" }
            if (result.outcome == BootRecoveryFlow.Outcome.CONVERGED) hooks.onConverged()
            hooks.onFinished(pickup, result)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log(TAG, Logging.Priority.ERROR) { "Recovery job failed: ${e.message}" }
            bestEffort("Recovery warning") { hooks.warnRecoveryOwed() }
        } finally {
            try {
                hooks.continueOrStop()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log(TAG, Logging.Priority.ERROR) { "Recovery continuation failed: ${e.message}" }
                bestEffort("Recovery last resort") { hooks.lastResort() }
            }
        }
    }

    private inline fun bestEffort(label: String, block: () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log(TAG, Logging.Priority.ERROR) { "$label failed: ${e.message}" }
        }
    }

    companion object {
        val TAG = logTag("RecoveryJobFlow")
    }
}
