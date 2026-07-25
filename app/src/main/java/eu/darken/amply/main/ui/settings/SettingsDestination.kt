package eu.darken.amply.main.ui.settings

enum class SettingsDestination {
    DASHBOARD,
    SETTINGS,
    GENERAL,
    DIAGNOSTICS,
    SUPPORT,
    ACKNOWLEDGEMENTS,
    RECONNECT_GESTURE,

    /** "Battery & charging" — the single telemetry destination (live readout + charge teaser). */
    BATTERY,

    /** The recorded-session list, reached from [BATTERY]'s top bar. */
    CHARGE_HISTORY,
    STATS_SESSION_DETAIL,
}
