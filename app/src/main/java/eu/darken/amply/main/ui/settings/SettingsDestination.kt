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
     * The conditional charge rules. Reached from the dashboard card *and* the settings hub, so it
     * records where to return to — see `rulesOrigin` at the composition root.
     */
    CHARGE_RULES,

    /** The single-rule editor, always entered from (and returning to) [CHARGE_RULES]. */
    CHARGE_RULE_EDIT,

    /**
     * The recording **preferences** — the capture switch and the retention window — reached from the
     * settings hub. Not to be confused with [CHARGE_HISTORY], which is the recorded data itself.
     */
    CHARGING_HISTORY_SETTINGS,

    /** "Battery & charging" — the single telemetry destination (live readout + charge teaser). */
    BATTERY,

    /** One metric of one charge session, always entered from (and returning to) [BATTERY]. */
    BATTERY_METRIC_DETAIL,

    /** The recorded-session list (the data), reached from [BATTERY]'s top bar. */
    CHARGE_HISTORY,
    STATS_SESSION_DETAIL,

    /**
     * The guided qualification run, which drives the charge limit and watches whether the charging
     * hardware obeys it. Reached from the dashboard's enforcement card on builds where control is
     * held back pending exactly that evidence.
     */
    QUALIFICATION,

    /**
     * The upgrade screen. Reached from several places (the settings entry, a gated affordance, the
     * dashboard promo, the tile/widget), so it records where to return to rather than assuming one
     * parent — see `upgradeOrigin` at the composition root.
     */
    UPGRADE,
}
