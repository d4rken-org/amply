package eu.darken.amply.stats.ui

import eu.darken.amply.battery.core.BatteryReadout
import eu.darken.amply.stats.core.ChargeSessionSummary
import eu.darken.amply.stats.core.StatsLiveSession

/**
 * The stats-dashboard slice of the dashboard state: capture switch, pipeline health, and the Room
 * teaser data. Produced by the dashboard ViewModel, consumed by [StatsCardPresentation.from].
 */
data class StatsDashboardState(
    val enabled: Boolean = false,
    /** Capture is on but the stats DB hasn't answered the initial queries yet. */
    val loading: Boolean = false,
    /** The stats pipeline failed (DB error) — data can't be shown, but the dashboard stays alive. */
    val unavailable: Boolean = false,
    /** The most recent charge-service start attempt threw — capture likely isn't running. */
    val startFailed: Boolean = false,
    /** Most recent finished charge session, for the teaser (null when none yet). */
    val lastSession: ChargeSessionSummary? = null,
    val sessionCount: Int = 0,
    /** The in-progress charge session (null unless a session row is open for this boot). */
    val live: StatsLiveSession? = null,
)

/**
 * What the single dashboard stats card shows. The card sits in one fixed slot; only its content
 * adapts through these states.
 *
 * Truth rules:
 * - "On the charger" is decided by the RAW battery readout ([BatteryReadout.plugged]), never by the
 *   recorder's DB row: a stale open row must not claim [Live] while unplugged, and a missing row
 *   must not hide that the phone is on the charger ([ConnectedWithoutSession]).
 * - A null `plugged` (not reported) collapses conservatively to not-connected — the card never
 *   claims a charger it can't observe.
 * - Previous-boot rows are already excluded by the recorder's boot-scoped query; a same-boot row
 *   surviving process death can briefly present as [Live] until the recorder's first tick seals it.
 */
sealed interface StatsCardPresentation {

    /** Capture is off — advertise what enabling unlocks. */
    data object Promo : StatsCardPresentation

    /** Capture is on, stats DB queries not yet answered. */
    data object Loading : StatsCardPresentation

    /** The stats pipeline failed — shown instead of silently freezing or hiding the card. */
    data object Unavailable : StatsCardPresentation

    /** Capture on, charger connected, recorder row open — the in-progress session at a glance. */
    data class Live(
        val session: StatsLiveSession,
        val battery: BatteryReadout,
    ) : StatsCardPresentation

    /**
     * Capture on and the charger is connected, but no recorder row exists yet — install-while-
     * plugged, service-start latency, or a failed start ([startFailed]).
     */
    data class ConnectedWithoutSession(
        val battery: BatteryReadout,
        val startFailed: Boolean,
    ) : StatsCardPresentation

    /** Capture on, not connected — last-session teaser (or empty note). */
    data class Idle(
        val lastSession: ChargeSessionSummary?,
        val sessionCount: Int,
    ) : StatsCardPresentation

    companion object {
        fun from(stats: StatsDashboardState, readout: BatteryReadout?): StatsCardPresentation = when {
            !stats.enabled -> Promo
            stats.unavailable -> Unavailable
            stats.loading -> Loading
            readout != null && (readout.plugged ?: 0) != 0 -> when (val live = stats.live) {
                null -> ConnectedWithoutSession(battery = readout, startFailed = stats.startFailed)
                else -> Live(session = live, battery = readout)
            }
            else -> Idle(lastSession = stats.lastSession, sessionCount = stats.sessionCount)
        }
    }
}
