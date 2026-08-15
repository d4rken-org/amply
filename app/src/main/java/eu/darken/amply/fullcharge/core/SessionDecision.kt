package eu.darken.amply.fullcharge.core

import eu.darken.amply.charging.core.ChargeObservation
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

/**
 * Decides whether a settings-change notification observed during an active session is a genuine
 * native change (cancel the session without restoring) or noise (ignore, keep the session).
 *
 * Android dispatches settings notifications asynchronously, so the session's OWN override write can
 * be delivered after the observer registers, and an OEM provider may notify without any value
 * change at all — both observed on HyperOS 3 `tanzanite`, where blind cancellation ended the
 * session mid-charge and the protective policy was never restored (issue #48). Where the adapter's
 * configuration is synchronously readable, a notification whose readback still decodes to the
 * session's override policy is therefore treated as noise. Deliberate trade-off: a native change
 * that still decodes to the override policy — re-selecting the same value, or on multi-key
 * adapters editing an auxiliary key the decoded policy ignores (e.g. Samsung's threshold while the
 * PauseAtFull override is active) — is indistinguishable from that noise and keeps the session
 * running, and the later restore can overwrite such an auxiliary edit with pre-session values.
 * Accepted: only reachable by editing protection settings mid-session, and never a charge-safety
 * regression (the protective policy still comes back).
 *
 * Everything else cancels, preserving the previous blanket behavior: a different verified policy
 * is a real native change; an unrecognized or unreadable value cannot be attributed to the
 * session; and without sync readback (Pixel, [readback] null) nothing can be verified.
 */
object NativeChangeGuard {
    fun shouldCancel(readback: ChargeObservation?, overridePolicy: ChargePolicy): Boolean =
        !(readback is ChargeObservation.Verified && readback.policy == overridePolicy)
}

enum class SessionDecision {
    CONTINUE,
    MARK_CONNECTED,
    /** Plug-latched grace: first disconnect observed — open the replug window instead of restoring. */
    MARK_DISCONNECTED,
    /** Plug-latched grace: replug inside the window — the plug transition latched the override. */
    MARK_REPLUGGED,
    RESTORE_FULL,
    RESTORE_DISCONNECTED,
    RESTORE_ARM_TIMEOUT,
    RESTORE_SAFETY_TIMEOUT,
}

object SessionDecisionEngine {
    const val ARM_TIMEOUT_MILLIS = 15 * 60 * 1000L
    const val SAFETY_TIMEOUT_MILLIS = 24 * 60 * 60 * 1000L

    /**
     * Replug grace for plug-latched adapters: how long after a disconnect the session keeps waiting
     * for the replug that latches its override, instead of restoring immediately. Wall clock — the
     * timestamp is persisted in the session record and must survive process death. Long enough to
     * read a notification and re-seat a cable; bounded because during grace the device sits unplugged
     * with the override *configured* — expiry restores, and a post-expiry replug then latches the
     * already-restored protective value (fail safe).
     */
    const val REPLUG_GRACE_MILLIS = 30_000L

    /**
     * [replugGraceMillis] > 0 only for plug-latched adapters
     * ([eu.darken.amply.charging.core.adapter.ChargingAdapter.policyLatchesAtPlug]); 0 disables the
     * grace path entirely, keeping every other adapter's decisions unchanged.
     */
    fun decide(
        session: ChargeSessionRecord,
        nowMillis: Long,
        plugged: Boolean,
        full: Boolean,
        armTimeoutMillis: Long = ARM_TIMEOUT_MILLIS,
        safetyTimeoutMillis: Long = SAFETY_TIMEOUT_MILLIS,
        replugGraceMillis: Long = 0L,
    ): SessionDecision {
        val age = (nowMillis - session.startedAtMillis).coerceAtLeast(0)
        return when {
            full -> SessionDecision.RESTORE_FULL
            age >= safetyTimeoutMillis -> SessionDecision.RESTORE_SAFETY_TIMEOUT
            session.connectedSeen && !plugged -> when {
                replugGraceMillis <= 0L -> SessionDecision.RESTORE_DISCONNECTED
                session.disconnectedAtMillis == null -> SessionDecision.MARK_DISCONNECTED
                // Expiry — or a backwards wall clock, which voids the window's evidence: fail safe
                // and restore rather than waiting on a timestamp that proves nothing anymore.
                nowMillis - session.disconnectedAtMillis !in 0 until replugGraceMillis ->
                    SessionDecision.RESTORE_DISCONNECTED
                else -> SessionDecision.CONTINUE
            }
            // A replug only continues the session INSIDE the persisted window. A later replug (e.g.
            // the process was dead through expiry) has already latched whatever the key held — no
            // decision can affect the running plug session — so honoring the expired bound by
            // restoring is the conservative end state: config protective, session closed.
            session.connectedSeen && plugged && session.disconnectedAtMillis != null ->
                if (nowMillis - session.disconnectedAtMillis in 0 until replugGraceMillis) {
                    SessionDecision.MARK_REPLUGGED
                } else {
                    SessionDecision.RESTORE_DISCONNECTED
                }
            !session.connectedSeen && plugged -> SessionDecision.MARK_CONNECTED
            !session.connectedSeen && !plugged && age >= armTimeoutMillis ->
                SessionDecision.RESTORE_ARM_TIMEOUT
            else -> SessionDecision.CONTINUE
        }
    }
}
