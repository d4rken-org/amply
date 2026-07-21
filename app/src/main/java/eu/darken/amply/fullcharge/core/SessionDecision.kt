package eu.darken.amply.fullcharge.core

import eu.darken.amply.charging.core.ChargePolicy
import eu.darken.amply.fullcharge.core.ChargeSessionRecord

sealed interface SessionStartDecision {
    /** No session needed: the active policy already lets the battery reach 100%. */
    data object AlreadyChargesFull : SessionStartDecision

    data class Start(val restorePolicy: ChargePolicy) : SessionStartDecision
}

object SessionStartDecider {

    /**
     * [current] is the observed active policy (null when unreadable), [overridePolicy] what a session
     * would write. The restore target must be applicable later: an observed or stored policy that the
     * active adapter cannot apply (e.g. a Pixel 80% limit carried onto a legacy Samsung that only
     * supports 85%) falls through to the adapter's own default.
     */
    fun decide(
        current: ChargePolicy?,
        overridePolicy: ChargePolicy,
        storedProtective: ChargePolicy,
        supportedPolicies: List<ChargePolicy>,
        defaultProtective: ChargePolicy,
    ): SessionStartDecision {
        if (current != null && (current == overridePolicy || current.allowsFullCharge)) {
            return SessionStartDecision.AlreadyChargesFull
        }
        val restorePolicy = current?.takeIf { it in supportedPolicies }
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
