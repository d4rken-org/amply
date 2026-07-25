package eu.darken.amply.main.ui.dashboard

import eu.darken.amply.battery.core.BatteryReadout
import eu.darken.amply.stats.core.ChargeSessionSummary
import eu.darken.amply.stats.core.StatsLiveSession

/**
 * The stats-dashboard slice of the dashboard state: capture switch, pipeline health, and the Room
 * teaser data. Produced by the dashboard ViewModel, consumed by [ChargingCardPresentation.from].
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
 * What the charging card's *session* body shows. There is exactly one card and it holds a fixed slot
 * on the dashboard; only this content adapts.
 *
 * These states deliberately do **not** carry the [BatteryReadout]. The live reading is shown in every
 * state — including [Promo], where capture is off and no session data exists at all — so it is passed
 * to the card alongside this, not through it. Folding it in would imply the reading depends on the
 * capture opt-in, which is exactly the coupling this card exists to break.
 *
 * Truth rules:
 * - "On the charger" is decided by the RAW battery readout ([BatteryReadout.onCharger]), never by the
 *   recorder's DB row: a stale open row must not claim [Live] while unplugged, and a missing row
 *   must not hide that the phone is on the charger ([ConnectedWithoutSession]).
 * - A null `plugged` (not reported) is [Indeterminate], not idle: the card claims neither a charger
 *   nor the absence of one when the platform reported neither.
 * - Previous-boot rows are already excluded by the recorder's boot-scoped query.
 * - A row is not enough to claim [Live]: capture must also be running. When the last service-start
 *   attempt failed, no ticks are arriving, so an open row (including one the recorder resumed after a
 *   process restart, which stays open indefinitely by design) would otherwise render as a live
 *   session frozen at its last values — and hide the retry action that could fix it.
 */
sealed interface ChargingCardPresentation {

    /** Capture is off — advertise what enabling unlocks. */
    data object Promo : ChargingCardPresentation

    /** Capture is on, stats DB queries not yet answered. */
    data object Loading : ChargingCardPresentation

    /** The stats pipeline failed — shown instead of silently freezing or hiding the card. */
    data object Unavailable : ChargingCardPresentation

    /**
     * Capture is on and healthy, but the platform is not reporting a plug state, so whether a charge
     * is happening is simply unknown.
     *
     * Needed because "no charger reported" and "reported no charger" are different facts.
     * [BatteryReadout.onCharger] deliberately collapses them for the question *may we act*, where
     * false is the safe answer — but presenting that collapse would answer *what is happening* with a
     * claim we can't support. Without this, a battery reader that has failed for long enough to be
     * declared unreadable would flip an in-progress charge to the idle "last charge" teaser, trading
     * one false statement for another.
     */
    data object Indeterminate : ChargingCardPresentation

    /** Capture on, charger connected, recorder row open — the in-progress session at a glance. */
    data class Live(val session: StatsLiveSession) : ChargingCardPresentation

    /**
     * Capture on and the charger is connected, but there is no session to show live — no recorder row
     * yet (install-while-plugged, service-start latency), or capture isn't running because the last
     * start attempt failed ([startFailed]), in which case any open row is frozen and not shown.
     */
    data class ConnectedWithoutSession(val startFailed: Boolean) : ChargingCardPresentation

    /** Capture on, not connected — last-session teaser (or empty note). */
    data class Idle(
        val lastSession: ChargeSessionSummary?,
        val sessionCount: Int,
    ) : ChargingCardPresentation

    companion object {
        fun from(stats: StatsDashboardState, readout: BatteryReadout?): ChargingCardPresentation = when {
            !stats.enabled -> Promo
            stats.unavailable -> Unavailable
            stats.loading -> Loading
            // Before the connection branches: an unreported plug state can't answer either of them.
            readout?.plugged == null -> Indeterminate
            readout.onCharger -> when (val live = stats.live) {
                null -> ConnectedWithoutSession(startFailed = stats.startFailed)
                // A failed start means no ticks: show the retry rather than a frozen "live" session.
                else -> when {
                    stats.startFailed -> ConnectedWithoutSession(startFailed = true)
                    else -> Live(session = live)
                }
            }
            else -> Idle(lastSession = stats.lastSession, sessionCount = stats.sessionCount)
        }
    }
}
