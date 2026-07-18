package eu.darken.amply.fullcharge.core

import eu.darken.amply.fullcharge.core.ChargeSessionRecord

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
    ): SessionDecision {
        val age = (nowMillis - session.startedAtMillis).coerceAtLeast(0)
        return when {
            full -> SessionDecision.RESTORE_FULL
            age >= SAFETY_TIMEOUT_MILLIS -> SessionDecision.RESTORE_SAFETY_TIMEOUT
            session.connectedSeen && !plugged -> SessionDecision.RESTORE_DISCONNECTED
            !session.connectedSeen && plugged -> SessionDecision.MARK_CONNECTED
            !session.connectedSeen && !plugged && age >= ARM_TIMEOUT_MILLIS ->
                SessionDecision.RESTORE_ARM_TIMEOUT
            else -> SessionDecision.CONTINUE
        }
    }
}
