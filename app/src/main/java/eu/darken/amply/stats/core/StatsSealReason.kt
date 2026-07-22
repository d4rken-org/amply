package eu.darken.amply.stats.core

/**
 * Why a charge session was closed. Note there is no `FULL` reason: a session represents a whole
 * plug→unplug cycle and stays open after reaching 100% (the moment is recorded as `fullReachedAt`),
 * so being held at full by an OEM limiter never fragments it into repeated degenerate sessions.
 */
enum class StatsSealReason {
    /** Charger removed — the normal, complete end of a session. */
    UNPLUGGED,

    /** Reopened an open session after a process restart within the same boot; curve has a gap. */
    INTERRUPTED,

    /** Open session found after a reboot; elapsed-time continuity across power-off can't be trusted. */
    REBOOT,

    /** Capture was turned off while a session was open. */
    DISABLED,
}
