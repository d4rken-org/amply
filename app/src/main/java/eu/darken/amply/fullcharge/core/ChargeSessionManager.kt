package eu.darken.amply.fullcharge.core

import eu.darken.amply.charging.core.ApplyResult
import eu.darken.amply.charging.core.ChargeObservation
import eu.darken.amply.charging.core.ChargePolicy
import eu.darken.amply.charging.core.ChargingRepository
import eu.darken.amply.charging.core.ChargingPreferences
import eu.darken.amply.fullcharge.core.FullChargeStore
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ChargeSessionManager @Inject constructor(
    private val repository: ChargingRepository,
    private val preferences: ChargingPreferences,
    private val sessionStore: FullChargeStore,
    private val processIdentity: ProcessIdentity,
    private val bootCountProvider: BootCountProvider,
) {
    private val mutex = Mutex()

    suspend fun begin(
        nowMillis: Long = System.currentTimeMillis(),
        pluggedAtStart: Boolean? = null,
    ): ApplyResult = mutex.withLock {
        sessionStore.currentSession()?.let {
            return@withLock ApplyResult(
                success = true,
                observation = repository.state.value.observation,
                message = "Temporary session already active",
            )
        }
        val adapter = repository.currentAdapter()
        val overridePolicy = adapter?.sessionOverridePolicy ?: ChargePolicy.Unrestricted
        val refreshed = repository.refresh()
        val observation = refreshed.observation
        // Central guard: refuse before persisting a session when no backend can actually write
        // (e.g. a Shizuku-only OnePlus adapter with Shizuku not connected). Otherwise the session
        // is persisted, the override write fails, the rollback write fails too, and a phantom
        // recovery session is stranded — regardless of which surface (dashboard/widget/tile) called.
        if (!refreshed.canApply) {
            return@withLock ApplyResult(
                success = false,
                observation = observation,
                message = "Charging control needs Shizuku on this device",
            )
        }
        // The refresh observation is presentation-oriented: it masks a readable-but-unrecognized
        // OEM value behind LastRequested and can itself degrade to LastRequested when the
        // preferred backend fails. The raw sync readback (null for async adapters) is
        // authoritative for the start decision — both for refusing on an unrecognized value and
        // for the verified current policy.
        val syncRead = repository.syncReadback()
        val decision = SessionStartDecider.decide(
            verifiedCurrent = (syncRead as? ChargeObservation.Verified)?.policy
                ?: (observation as? ChargeObservation.Verified)?.policy,
            lastRequested = (observation as? ChargeObservation.LastRequested)?.policy,
            overridePolicy = overridePolicy,
            storedProtective = preferences.protectivePolicyNow(),
            supportedPolicies = adapter?.supportedPolicies.orEmpty(),
            defaultProtective = adapter?.defaultProtectivePolicy ?: ChargePolicy.FixedLimit(80),
            currentUnrecognized = syncRead is ChargeObservation.Unknown && syncRead.unrecognizedValue,
        )
        if (decision is SessionStartDecision.AlreadyChargesFull) {
            return@withLock ApplyResult(
                success = false,
                observation = repository.state.value.observation,
                message = "Charging already reaches 100%; no temporary session needed",
            )
        }
        if (decision is SessionStartDecision.UnrecognizedCurrentState) {
            return@withLock ApplyResult(
                success = false,
                observation = repository.state.value.observation,
                message = "The current OEM charging mode is not recognized; refusing to overwrite it",
            )
        }
        val restorePolicy = (decision as SessionStartDecision.Start).restorePolicy

        // Persist recovery state before removing the limit. Stamp this process's identity so a later
        // pickup can tell whether the session survived a process death (interruption detection), and a
        // stable work id that survives ownership adoption so a later restore can resolve the warning.
        sessionStore.startSession(
            restorePolicy = restorePolicy,
            startedAtMillis = nowMillis,
            workId = UUID.randomUUID().toString(),
            provenance = WorkProvenance(
                token = processIdentity.token,
                pid = processIdentity.pid,
                bootCount = bootCountProvider.current(),
                createdAtMillis = nowMillis,
            ),
            // Plug-latched adapter + started while plugged (or plug state unknown): the override
            // write below cannot take effect until an unplug→replug, so the session must surface
            // that instruction until the replug is observed.
            overrideAwaitingReplug = adapter?.policyLatchesAtPlug == true && pluggedAtStart != false,
        )
        val result = repository.applyTemporary(overridePolicy)
        if (result.success) {
            // Reconcile the persist-first conservative flag with the repository's authoritative
            // post-write computation (it re-samples plug state around the write), so the session
            // notification and the dashboard's pending hint can never disagree. Best-effort: a
            // failure here leaves the conservative flag, which errs toward showing the hint.
            val awaiting = repository.state.value.pending?.awaitingReplug == true
            runCatching { sessionStore.setOverrideAwaitingReplug(awaiting) }
            result
        } else {
            // A two-key OEM transition can partially succeed. Keep recovery state unless the
            // original protective policy is successfully written back immediately. Repaying a policy
            // the user already had is not new control, so it goes through the ungated restore path.
            val rollback = repository.restorePersistent(restorePolicy)
            if (rollback.success) sessionStore.clearSession()
            result.copy(
                message = if (rollback.success) {
                    "Temporary override failed; protective policy restored"
                } else {
                    "Temporary override failed and still needs recovery"
                },
            )
        }
    }

    suspend fun restore(): ApplyResult = mutex.withLock {
        val session = sessionStore.currentSession() ?: return@withLock ApplyResult(
            success = true,
            observation = repository.state.value.observation,
            message = "No temporary session is active",
        )
        // The session's restore obligation predates any evidence tier change (a nightly/OTA moves the
        // build identity, so a confirmed device can come back a candidate with a restore still owed).
        // The gate withholds NEW control; it must never keep Amply from repaying protection the user
        // already had, which would strand the device in the session's Unrestricted state.
        val result = repository.restorePersistent(session.restorePolicy)
        if (result.success) sessionStore.clearSession()
        result
    }

    suspend fun cancelWithoutRestore() = mutex.withLock {
        sessionStore.clearSession()
    }

    suspend fun markConnected() = sessionStore.markConnected()

    suspend fun markDisconnected(nowMillis: Long) = sessionStore.markDisconnected(nowMillis)

    suspend fun markReplugged() = sessionStore.markReplugged()
}

fun ChargeObservation.policyOrNull(): ChargePolicy? = when (this) {
    is ChargeObservation.Verified -> policy
    is ChargeObservation.LastRequested -> policy
    else -> null
}
