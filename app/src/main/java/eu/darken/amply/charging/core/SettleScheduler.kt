package eu.darken.amply.charging.core

/**
 * Schedules a one-shot surface refresh at the end of a request's settling window, so the transient
 * "applying…" cue clears (and any surfaces that don't observe [ChargingRepository.state] — the widget,
 * the tile — get pushed an update) even if the process is killed before the window elapses.
 */
interface SettleScheduler {
    fun schedule(requestedAtMillis: Long)
}
