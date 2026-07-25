package eu.darken.amply.stats.core

/**
 * Why a charge session was closed. Note there is no `FULL` reason: a session represents a whole
 * plug→unplug cycle and stays open after reaching 100% (the moment is recorded as `fullReachedAt`),
 * so being held at full by an OEM limiter never fragments it into repeated degenerate sessions.
 */
enum class StatsSealReason {
    /** Charger removed — the normal, complete end of a session. */
    UNPLUGGED,

    /**
     * Found open after a process restart and *not* resumable — the charge it recorded could not be
     * shown to still be running (unplugged since, level dropped, unknown boot). A restart that is
     * consistent with the same plug event reattaches the row instead of sealing it, so this reason
     * means the evidence failed, not merely that the process died.
     */
    INTERRUPTED,

    /** Open session found after a reboot; elapsed-time continuity across power-off can't be trusted. */
    REBOOT,

    /** Capture was turned off while a session was open. */
    DISABLED,
}
