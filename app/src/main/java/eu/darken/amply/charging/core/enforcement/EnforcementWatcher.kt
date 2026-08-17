package eu.darken.amply.charging.core.enforcement

import eu.darken.amply.monitor.core.ChargeMonitorTick
import eu.darken.amply.monitor.core.ChargeMonitorWatcher
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Contributes enforcement observation to the charge-session monitor.
 *
 * [onBatteryTick] does no blocking work: it copies the tick — including the exact battery intent, so
 * the recorder can fall back to the level off the *same* observation — and hands it to
 * [EnforcementRecorder], which does every DataStore, provider and Binder read on its own IO thread.
 * This runs under the service's dispatch lock, so a slow read here could delay the safety-critical
 * charge-policy restore.
 */
@Singleton
class EnforcementWatcher @Inject constructor(
    private val recorder: EnforcementRecorder,
) : ChargeMonitorWatcher {

    override val id = "enforcement_evidence"

    override suspend fun isEnabled(): Boolean = recorder.shouldObserve()

    override suspend fun onBatteryTick(tick: ChargeMonitorTick) {
        recorder.offer(
            RawEnforcementTick(
                plugged = tick.plugged,
                percent = tick.percent,
                sessionActive = tick.sessionActive,
                batteryIntent = tick.batteryIntent,
                wallMillis = tick.wallClockMillis,
            ),
        )
    }
}
