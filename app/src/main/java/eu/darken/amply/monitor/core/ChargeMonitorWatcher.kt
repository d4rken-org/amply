package eu.darken.amply.monitor.core

/**
 * A single sample of public battery state, handed to every [ChargeMonitorWatcher] on each
 * evaluation tick of the charge-session foreground service. All fields come from the sticky
 * [android.content.Intent.ACTION_BATTERY_CHANGED] broadcast and need no permission.
 */
data class ChargeMonitorTick(
    val plugged: Boolean,
    /** Battery level in percent, or -1 when it can't be determined. */
    val percent: Int,
    /** Raw [android.os.BatteryManager.EXTRA_STATUS] value. */
    val batteryStatus: Int,
    /** Whether a temporary full-charge session is currently active. */
    val sessionActive: Boolean,
)

/**
 * An optional, permission-free observer of battery ticks hosted by the charge-session service.
 * Watchers are contributed via Hilt `@IntoSet` multibindings so the service depends only on this
 * contract, never on a concrete feature package.
 *
 * Implementations must be non-blocking and tolerant of concurrent process lifecycles. They must
 * never throw to signal "disabled" — [isEnabled] failures are treated as `false` by the host and
 * must not block safety-critical charge-policy recovery.
 */
interface ChargeMonitorWatcher {
    /** Stable identifier used in failure logs. */
    val id: String

    /** Whether this watcher currently needs the monitor service alive. */
    suspend fun isEnabled(): Boolean

    /** Handle one battery evaluation tick. Invoked on every tick, including during a session. */
    suspend fun onBatteryTick(tick: ChargeMonitorTick)
}
