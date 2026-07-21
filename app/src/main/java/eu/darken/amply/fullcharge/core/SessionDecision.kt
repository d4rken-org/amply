package eu.darken.amply.fullcharge.core

import eu.darken.amply.charging.core.ChargePolicy
import eu.darken.amply.fullcharge.core.ChargeSessionRecord

sealed interface SessionStartDecision {
    /** No session needed: the active policy already lets the battery reach 100%. */
    data object AlreadyChargesFull : SessionStartDecision

    /**
     * The current configuration is readable but not a value this adapter recognizes (e.g. a
     * future OEM mode). Starting a session would overwrite it and later "restore" a guessed
     * policy — refuse instead of destructively normalizing state Amply cannot reproduce.
     */
    data object UnrecognizedCurrentState : SessionStartDecision

    data class Start(val restorePolicy: ChargePolicy) : SessionStartDecision
}

object SessionStartDecider {

    /**
     * [verifiedCurrent] is the authoritative observed policy (null when unverifiable) and is the only
     * basis for refusing — a stale last-request must never block a session (WSS-only Pixel, unplugged,
     * after a native change). [lastRequested] still contributes a restore candidate. The restore target
     * must be applicable later: a policy the active adapter cannot apply (e.g. a Pixel 80% limit
     * carried onto a legacy Samsung that only supports 85%) falls through to the adapter's own default,
     * and a full-reaching policy is never persisted as the "protective" restore target.
     */
    fun decide(
        verifiedCurrent: ChargePolicy?,
        lastRequested: ChargePolicy?,
        overridePolicy: ChargePolicy,
        storedProtective: ChargePolicy,
        supportedPolicies: List<ChargePolicy>,
        defaultProtective: ChargePolicy,
        currentUnrecognized: Boolean = false,
    ): SessionStartDecision {
        if (currentUnrecognized) return SessionStartDecision.UnrecognizedCurrentState
        if (verifiedCurrent != null &&
            (verifiedCurrent == overridePolicy || verifiedCurrent.allowsFullCharge)
        ) {
            return SessionStartDecision.AlreadyChargesFull
        }
        val restorePolicy = (verifiedCurrent ?: lastRequested)
            ?.takeIf { it in supportedPolicies && !it.allowsFullCharge }
            ?: storedProtective.takeIf { it in supportedPolicies }
            ?: defaultProtective
        return SessionStartDecision.Start(restorePolicy)
    }
}

enum class SessionDecision {
    CONTINUE,
    MARK_CONNECTED,
    RESTORE_FULL,
    RESTORE_DISCONNECTED,
    RESTORE_ARM_TIMEOUT,
    RESTORE_SAFETY_TIMEOUT,
}

object SessionDecisionEngine {
    const val ARM_TIMEOUT_MILLIS = 15 * 60 * 1000L
    const val SAFETY_TIMEOUT_MILLIS = 24 * 60 * 60 * 1000L

    fun decide(
        session: ChargeSessionRecord,
        nowMillis: Long,
        plugged: Boolean,
        full: Boolean,
        armTimeoutMillis: Long = ARM_TIMEOUT_MILLIS,
        safetyTimeoutMillis: Long = SAFETY_TIMEOUT_MILLIS,
    ): SessionDecision {
        val age = (nowMillis - session.startedAtMillis).coerceAtLeast(0)
        return when {
            full -> SessionDecision.RESTORE_FULL
            age >= safetyTimeoutMillis -> SessionDecision.RESTORE_SAFETY_TIMEOUT
            session.connectedSeen && !plugged -> SessionDecision.RESTORE_DISCONNECTED
            !session.connectedSeen && plugged -> SessionDecision.MARK_CONNECTED
            !session.connectedSeen && !plugged && age >= armTimeoutMillis ->
                SessionDecision.RESTORE_ARM_TIMEOUT
            else -> SessionDecision.CONTINUE
        }
    }
}
