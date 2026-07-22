package eu.darken.amply.stats.core

import eu.darken.amply.monitor.core.ChargeMonitorTick
import eu.darken.amply.monitor.core.ChargeMonitorWatcher
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Contributes battery-statistics capture to the charge-session monitor. [isEnabled] reflects the
 * opt-in preference, so when capture is on the service's existing keep-alive logic holds the
 * foreground service (and its persistent notification) up continuously — that is the always-on
 * behavior.
 *
 * [onBatteryTick] does no blocking work: it copies the tick into a [RawStatsTick] and hands it to
 * [ChargeStatsRecorder], which does every DataStore/Binder/Room read on its own IO thread. This runs
 * under the service's `commandMutex`, so it must never touch the disk or a Binder — a slow read here
 * could delay the safety-critical charge-policy restore.
 */
@Singleton
class ChargeStatsWatcher @Inject constructor(
    private val preferences: StatsPreferences,
    private val recorder: ChargeStatsRecorder,
) : ChargeMonitorWatcher {

    override val id = "battery_stats"

    override suspend fun isEnabled(): Boolean = preferences.isCaptureEnabledNow()

    override suspend fun onBatteryTick(tick: ChargeMonitorTick) {
        recorder.offer(
            RawStatsTick(
                plugged = tick.plugged,
                percent = tick.percent,
                batteryStatus = tick.batteryStatus,
                sessionActive = tick.sessionActive,
                batteryIntent = tick.batteryIntent,
                observedElapsedRealtimeMillis = tick.observedElapsedRealtimeMillis,
                wallMillis = tick.wallClockMillis,
            ),
        )
    }
}
