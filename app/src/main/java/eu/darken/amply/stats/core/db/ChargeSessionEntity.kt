package eu.darken.amply.stats.core.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * One physical charge session (plug → unplug). Row is created on plug-in and stays open
 * (`endedAtWallMillis == null`) until the charger is removed, so a battery that reaches 100% and is
 * held there by an OEM limiter remains a single session rather than fragmenting into many.
 *
 * Timing is stored twice on purpose: wall-clock for display, and boot-scoped
 * [android.os.SystemClock.elapsedRealtime] plus [bootId] for durations and ordering that survive
 * manual clock changes / NTP / DST. A duration is only trustworthy when start and end share the same
 * [bootId]; a session sealed after a reboot is marked [partial].
 *
 * Summary statistics are accumulated online while the session is open (see the running-* columns),
 * never by re-scanning raw samples at close — so retention can purge old [BatterySampleEntity] rows
 * without corrupting a finished summary, and a very long session never triggers an unbounded scan.
 */
@Entity(tableName = "charge_sessions")
data class ChargeSessionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,

    val startedAtWallMillis: Long,
    val startedElapsedRealtimeMillis: Long,
    val bootId: Long,

    val endedAtWallMillis: Long? = null,
    val endedElapsedRealtimeMillis: Long? = null,
    /** One of [eu.darken.amply.stats.core.StatsSealReason] names; null while open. */
    val endReason: String? = null,

    val startPercent: Int? = null,
    val endPercent: Int? = null,
    /** Raw [android.os.BatteryManager.EXTRA_PLUGGED] bitmask at start (AC/USB/WIRELESS/DOCK). */
    val pluggedRaw: Int? = null,

    /** When [android.os.BatteryManager.BATTERY_STATUS_FULL] was first seen this cycle, if ever. */
    val fullReachedAtWallMillis: Long? = null,

    /**
     * True when the session does not represent a clean plug→unplug: capture was enabled mid-charge,
     * started already at 100%, or the session was sealed by process recovery / reboot. The UI labels
     * these as partial rather than presenting them as complete histories.
     */
    val partial: Boolean = false,

    /** Set at close from the running weighted sums; null while open. */
    val avgPowerMilliwatts: Int? = null,
    val avgTemperatureTenthsC: Int? = null,

    // --- Online accumulators (mutated on every recorded sample while the session is open) ---
    val runningSampleCount: Int = 0,
    /**
     * Per-metric time-weighted sums and their denominators (kept separate because power and
     * temperature can be absent on different ticks). Average = sum / duration.
     */
    val runningPowerWeightedSum: Double = 0.0,
    val runningPowerWeightedDurationMillis: Long = 0,
    val runningTemperatureWeightedSum: Double = 0.0,
    val runningTemperatureWeightedDurationMillis: Long = 0,
    val runningPeakPowerMilliwatts: Int? = null,
    val runningMinTemperatureTenthsC: Int? = null,
    val runningMaxTemperatureTenthsC: Int? = null,
    /** Last observed values, used to weight the next interval and to seal on recovery. */
    val runningLastPowerMilliwatts: Int? = null,
    val runningLastTemperatureTenthsC: Int? = null,
    val runningLastPercent: Int? = null,
    val runningLastElapsedRealtimeMillis: Long? = null,
    /** Wall time of the last recorded sample; the seal endpoint when no fresh tick is available. */
    val runningLastWallMillis: Long? = null,
    /** Sticky: an OEM charge limit was observed holding at least once this cycle. */
    val limitHitEvidence: Boolean = false,
    /** Sticky: an Amply full-charge override owned this plug cycle (forces limitHit false). */
    val overrideSeen: Boolean = false,
)
