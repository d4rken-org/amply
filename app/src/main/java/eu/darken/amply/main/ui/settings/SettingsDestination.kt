package eu.darken.amply.main.ui.settings

enum class SettingsDestination {
    DASHBOARD,
    SETTINGS,
    GENERAL,
    DIAGNOSTICS,
    SUPPORT,
    ACKNOWLEDGEMENTS,
    RECONNECT_GESTURE,

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
}
