package eu.darken.amply.fullcharge.core

import eu.darken.amply.common.debug.logging.Logging
import eu.darken.amply.common.debug.logging.log
import eu.darken.amply.common.debug.logging.logTag
import kotlinx.coroutines.CancellationException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Sequences interrupted-session detection around the service's session/recovery pickups. It decides
 * — from persisted work provenance, the boot counter, and the pure [InterruptionDecisionEngine] —
 * whether a process death actually cost the user a restore, records at most one dashboard event when
 * it did, and otherwise silently re-adopts surviving work to the current process so a same-process
 * retry never double-records.
 *
 * Every public method is best-effort: a non-cancellation failure is caught and logged, never
 * propagated, so interruption bookkeeping can never strand the service's own cleanup. Cancellation
 * still propagates.
 */
@Singleton
class InterruptionAssessor @Inject constructor(
    private val fullChargeStore: FullChargeStore,
    private val interruptionStore: InterruptionStore,
    private val exitSource: ExitSource,
    private val identity: ProcessIdentity,
    private val bootCountProvider: BootCountProvider,
) {

    /** A survived same-boot pickup that may warrant an event; consumed exactly once by a terminal handler. */
    class PickupAssessment internal constructor(
        internal val provenance: WorkProvenance,
        internal val workId: String?,
        internal val survived: Boolean,
        internal val sameBoot: Boolean,
    ) {
        private var consumed = false

        internal fun consume(): Boolean {
            if (consumed) return false
            consumed = true
            return true
        }
    }

    /**
     * A recovery pickup: the stable [workId] of the recovering work (present even when no assessment
     * is produced, so a convergence can resolve a prior event) plus the candidate [assessment].
     */
    class RecoveryPickup internal constructor(
        internal val workId: String?,
        internal val assessment: PickupAssessment?,
    )

    /**
     * Assess a resumed persisted session. Returns a candidate assessment only when the session's work
     * crossed a same-boot process death; for legacy/no-death/reboot pickups it silently adopts the
     * session to the current process and returns null (no event).
     */
    suspend fun captureSessionPickup(record: ChargeSessionRecord): PickupAssessment? = runSafeOrNull {
        val provenance = record.provenance
        if (provenance == null || !isSurvivedSameBoot(provenance)) {
            fullChargeStore.adoptSessionOwner(currentProvenance())
            return@runSafeOrNull null
        }
        PickupAssessment(provenance, workId = record.workId, survived = true, sameBoot = true)
    }

    /** The session resumed unharmed (CONTINUE / MARK_CONNECTED): adopt, no event. */
    suspend fun onSessionDecision(assessment: PickupAssessment?, decision: SessionDecision) = runSafe {
        if (assessment == null || !assessment.consume()) return@runSafe
        when (decision) {
            SessionDecision.MARK_CONNECTED -> fullChargeStore.markConnectedAndAdopt(currentProvenance())
            else -> fullChargeStore.adoptSessionOwner(currentProvenance())
        }
    }

    /** A restore was due after the pickup and has finished; record the outcome. */
    suspend fun onSessionRestoreFinished(assessment: PickupAssessment?, success: Boolean) = runSafe {
        if (assessment == null || !assessment.consume()) return@runSafe
        val outcome = if (success) InterruptionOutcome.RESTORED_LATE else InterruptionOutcome.STILL_PENDING
        recordEvent(assessment, outcome)
        // A failed restore leaves the session persisted with the dead process's provenance; adopt so a
        // same-process retry never re-fires. A successful restore has already cleared the session.
        if (fullChargeStore.currentSession() != null) fullChargeStore.adoptSessionOwner(currentProvenance())
    }

    /**
     * Assess a recovery pickup. Recovery reaches here for both a pending target and a session-only
     * restore, so provenance is read from the pending target first, then the session. Non-candidate
     * pickups silently adopt any surviving recovery work — but the [RecoveryPickup] still carries the
     * work id so a convergence can resolve a prior event even without an assessment.
     *
     * Each store is read exactly **once**: reading provenance and work id through separate accessors
     * could straddle a concurrent adopt or clear and pair a token from one version of the record with
     * a work id from another.
     */
    suspend fun captureRecoveryPickup(): RecoveryPickup = runSafeOrNull {
        val recovery = fullChargeStore.currentRecovery()
        val session = fullChargeStore.currentSession()
        val provenance = recovery?.provenance ?: session?.provenance
        val workId = recovery?.workId ?: session?.workId
        if (provenance == null || !isSurvivedSameBoot(provenance)) {
            adoptRecoveryWork()
            return@runSafeOrNull RecoveryPickup(workId = workId, assessment = null)
        }
        RecoveryPickup(
            workId = workId,
            assessment = PickupAssessment(provenance, workId = workId, survived = true, sameBoot = true),
        )
    } ?: RecoveryPickup(workId = null, assessment = null)

    /** The recovery flow finished; resolve a converged prior event, map its outcome, re-adopt retry work. */
    suspend fun onRecoveryFinished(pickup: RecoveryPickup, result: BootRecoveryFlow.Result) = runSafe {
        // A converged recovery restored the protective policy; resolve any prior still-pending event
        // for this work FIRST — unconditionally, even when this pickup records nothing itself.
        if (result.outcome == BootRecoveryFlow.Outcome.CONVERGED) {
            pickup.workId?.let { interruptionStore.markRestored(it) }
        }
        val assessment = pickup.assessment
        if (assessment != null && assessment.consume()) {
            val outcome = InterruptionDecisionEngine.recoveryOutcome(
                survived = assessment.survived,
                sameBoot = assessment.sameBoot,
                result = result,
            )
            if (outcome != null) recordEvent(assessment, outcome)
        }
        // Re-adopt a pending target still persisted for retry so a same-process retry never double-records.
        fullChargeStore.adoptRecoveryOwner(currentProvenance())
    }

    /** A later restore for [workId] succeeded: upgrade a still-pending / unconfirmed event. */
    suspend fun onRestoreSucceeded(workId: String?) = runSafe {
        if (workId == null) return@runSafe
        interruptionStore.markRestored(workId)
    }

    /** An explicit user policy write supersedes the situation the event warned about; clear it. */
    suspend fun onExplicitPolicyWrite() = runSafe {
        interruptionStore.clearPending()
    }

    private fun isSurvivedSameBoot(provenance: WorkProvenance): Boolean {
        val survived = InterruptionDecisionEngine.survivedDeath(provenance.token, identity.token)
        val sameBoot = InterruptionDecisionEngine.sameBoot(provenance.bootCount, bootCountProvider.current())
        return survived && sameBoot
    }

    private suspend fun adoptRecoveryWork() {
        val provenance = currentProvenance()
        fullChargeStore.adoptRecoveryOwner(provenance)
        fullChargeStore.adoptSessionOwner(provenance)
    }

    private suspend fun recordEvent(assessment: PickupAssessment, outcome: InterruptionOutcome) {
        val reason = InterruptionDecisionEngine.classifyExit(
            exits = exitSource.recentExits(EXIT_LOOKBACK),
            ownerPid = assessment.provenance.pid,
            notBeforeMillis = assessment.provenance.createdAtMillis,
        )
        interruptionStore.record(
            InterruptionEvent(
                occurredAtMillis = System.currentTimeMillis(),
                reason = reason,
                outcome = outcome,
                // The stable work id (never the mutable owner token) correlates a later restore; a
                // record carrying provenance always carries a work id, so the fallback is unreachable.
                workId = assessment.workId ?: assessment.provenance.token,
            ),
        )
    }

    private fun currentProvenance() = WorkProvenance(
        token = identity.token,
        pid = identity.pid,
        bootCount = bootCountProvider.current(),
        createdAtMillis = System.currentTimeMillis(),
    )

    private suspend fun runSafe(block: suspend () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log(TAG, Logging.Priority.WARN) { "Interruption bookkeeping failed: ${e.message}" }
        }
    }

    private suspend fun <T> runSafeOrNull(block: suspend () -> T?): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        log(TAG, Logging.Priority.WARN) { "Interruption assessment failed: ${e.message}" }
        null
    }

    private companion object {
        val TAG = logTag("FullCharge", "InterruptionAssessor")

        // How many historical process-exit records to scan when classifying the interruption reason.
        const val EXIT_LOOKBACK = 16
    }
}
