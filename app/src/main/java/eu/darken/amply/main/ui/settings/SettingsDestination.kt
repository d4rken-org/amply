package eu.darken.amply.main.ui.settings

enum class SettingsDestination {
    DASHBOARD,
    SETTINGS,
    GENERAL,
    DIAGNOSTICS,
    SUPPORT,
    ACKNOWLEDGEMENTS,
    /** The charge-control preferences (the reconnect gesture and its arming basis), from the settings hub. */
    CHARGING,

    /**
     * The recording **preferences** — the capture switch and the retention window — reached from the
     * settings hub. Not to be confused with [CHARGE_HISTORY], which is the recorded data itself.
     */
    CHARGING_HISTORY_SETTINGS,

    /** "Battery & charging" — the single telemetry destination (live readout + charge teaser). */
    BATTERY,

    /** The recorded-session list (the data), reached from [BATTERY]'s top bar. */
    CHARGE_HISTORY,
    STATS_SESSION_DETAIL,

    /**
     * The upgrade screen. Reached from several places (the settings entry, a gated affordance, the
     * dashboard promo, the tile/widget), so it records where to return to rather than assuming one
     * parent — see `upgradeOrigin` at the composition root.
     */
    UPGRADE,
}
