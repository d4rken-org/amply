package eu.darken.amply.fullcharge.core

import eu.darken.amply.charging.core.ApplyResult
import eu.darken.amply.charging.core.ChargeObservation
import eu.darken.amply.charging.core.ChargePolicy
import eu.darken.amply.charging.core.ChargingRepository
import eu.darken.amply.charging.core.ChargingPreferences
import eu.darken.amply.fullcharge.core.FullChargeStore
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ChargeSessionManager @Inject constructor(
    private val repository: ChargingRepository,
    private val preferences: ChargingPreferences,
    private val sessionStore: FullChargeStore,
) {
    private val mutex = Mutex()

    suspend fun begin(nowMillis: Long = System.currentTimeMillis()): ApplyResult = mutex.withLock {
        sessionStore.currentSession()?.let {
            return@withLock ApplyResult(
                success = true,
                observation = repository.state.value.observation,
                message = "Temporary session already active",
            )
        }
        val adapter = repository.currentAdapter()
        val overridePolicy = adapter?.sessionOverridePolicy ?: ChargePolicy.Unrestricted
        val observation = repository.refresh().observation
        val decision = SessionStartDecider.decide(
            verifiedCurrent = (observation as? ChargeObservation.Verified)?.policy,
            lastRequested = (observation as? ChargeObservation.LastRequested)?.policy,
            overridePolicy = overridePolicy,
            storedProtective = preferences.protectivePolicyNow(),
            supportedPolicies = adapter?.supportedPolicies.orEmpty(),
            defaultProtective = adapter?.defaultProtectivePolicy ?: ChargePolicy.FixedLimit(80),
        )
        if (decision is SessionStartDecision.AlreadyChargesFull) {
            return@withLock ApplyResult(
                success = false,
                observation = repository.state.value.observation,
                message = "Charging already reaches 100%; no temporary session needed",
            )
        }
        val restorePolicy = (decision as SessionStartDecision.Start).restorePolicy

        // Persist recovery state before removing the limit.
        sessionStore.startSession(restorePolicy, nowMillis)
        val result = repository.applyTemporary(overridePolicy)
        if (result.success) {
            result
        } else {
            // A two-key OEM transition can partially succeed. Keep recovery state unless the
            // original protective policy is successfully written back immediately.
            val rollback = repository.applyPersistent(restorePolicy)
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
        val result = repository.applyPersistent(session.restorePolicy)
        if (result.success) sessionStore.clearSession()
        result
    }

    suspend fun cancelWithoutRestore() = mutex.withLock {
        sessionStore.clearSession()
    }

    suspend fun markConnected() = sessionStore.markConnected()
}

fun ChargeObservation.policyOrNull(): ChargePolicy? = when (this) {
    is ChargeObservation.Verified -> policy
    is ChargeObservation.LastRequested -> policy
    else -> null
}
