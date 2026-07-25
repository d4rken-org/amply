package eu.darken.amply.main.ui.battery

import eu.darken.amply.battery.core.BatteryReadout
import eu.darken.amply.main.ui.dashboard.ChargingCardPresentation
import eu.darken.amply.main.ui.dashboard.StatsDashboardState
import eu.darken.amply.stats.core.ChargeSessionSummary
import eu.darken.amply.stats.core.StatsLiveSession

/**
 * What the hub's charge-teaser section shows.
 *
 * Deliberately **derived from [ChargingCardPresentation.from]** rather than reimplemented. The naive
 * `live ?: lastSession` is wrong in two ways the card already solved: an open recorder row survives
 * unplugging until the recorder seals it (so it would render as "this charge" while the phone sits on
 * a desk), and a row left open by a failed service start is frozen at its last values (so it would
 * render as a live session that never advances). Routing through one decision keeps a fix to those
 * rules from having to be made twice.
 */
sealed interface ChargeTeaserState {

    /** Capture is off — the toggle stands alone, no session section. */
    data object CaptureOff : ChargeTeaserState

    /** Capture is on, the stats DB hasn't answered yet. Must not render the empty copy. */
    data object Loading : ChargeTeaserState

    /** The stats pipeline failed. Distinct from [None] — our outage is not the user's empty history. */
    data object Unavailable : ChargeTeaserState

    /** A charge is in progress. */
    data class Live(val session: StatsLiveSession) : ChargeTeaserState

    /** No charge in progress; the most recent finished one. */
    data class Last(val summary: ChargeSessionSummary) : ChargeTeaserState

    /** Capture is on and healthy, but nothing has been recorded yet. */
    data object None : ChargeTeaserState

    /**
     * The plug state is unreported, so neither "this charge" nor "last charge" can be claimed — see
     * [ChargingCardPresentation.Indeterminate].
     */
    data object Indeterminate : ChargeTeaserState

    companion object {
        fun from(stats: StatsDashboardState, readout: BatteryReadout?): ChargeTeaserState =
            when (val card = ChargingCardPresentation.from(stats, readout)) {
                ChargingCardPresentation.Promo -> CaptureOff
                ChargingCardPresentation.Loading -> Loading
                ChargingCardPresentation.Unavailable -> Unavailable
                ChargingCardPresentation.Indeterminate -> Indeterminate
                is ChargingCardPresentation.Live -> Live(card.session)
                // Plugged in but nothing live to show (no row yet, or a frozen one after a failed
                // start). The last finished charge is still the most useful thing here.
                is ChargingCardPresentation.ConnectedWithoutSession -> stats.lastSession.toTeaser()
                is ChargingCardPresentation.Idle -> card.lastSession.toTeaser()
            }

        private fun ChargeSessionSummary?.toTeaser(): ChargeTeaserState =
            this?.let { Last(it) } ?: None
    }
}
