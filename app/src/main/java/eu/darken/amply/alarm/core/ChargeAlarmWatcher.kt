package eu.darken.amply.alarm.core

import eu.darken.amply.common.debug.logging.Logging
import eu.darken.amply.common.debug.logging.log
import eu.darken.amply.common.debug.logging.logTag
import eu.darken.amply.monitor.core.ChargeMonitorTick
import eu.darken.amply.monitor.core.ChargeMonitorWatcher
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Bridges the pure [ChargeAlarmEngine] to the monitor service and the durable latch/notification
 * side effects. Owns no in-memory alarm state — the "fired this cycle" flag lives in
 * [ChargeAlarmStore], so the alarm fires at most once per plug cycle even if the host service is
 * killed and recreated while still plugged above target. The notification side effect is behind
 * [ChargeAlarmNotifier] so the latch/decision wiring is unit-testable without Android.
 */
@Singleton
class ChargeAlarmWatcher @Inject constructor(
    private val store: ChargeAlarmStore,
    private val notifier: ChargeAlarmNotifier,
) : ChargeMonitorWatcher {

    override val id = "charge_alarm"

    override suspend fun isEnabled(): Boolean = store.configNow().enabled

    override suspend fun onBatteryTick(tick: ChargeMonitorTick) {
        val config = store.configNow()
        if (!config.enabled) return

        when (ChargeAlarmEngine.decide(tick, config.targetPercent, store.firedCycle())) {
            ChargeAlarmDecision.FIRE -> {
                // Don't consume the cycle if the alert can't actually be delivered (notifications
                // off / channel muted): leave the latch clear so re-enabling delivery while still
                // plugged above target fires once, instead of silently swallowing this charge.
                if (!notifier.canDeliver()) return
                // Persist the latch BEFORE alerting: a crash after this yields a missed alert (rare
                // and harmless) rather than a duplicate one after the service restarts.
                store.setFiredCycle(true)
                // Re-read enabled right before showing to shrink the window where a concurrent
                // disable (which clears the latch and cancels) could be raced by a stale alert.
                if (store.configNow().enabled) {
                    log(TAG, Logging.Priority.INFO) { "Charge alarm firing at ${tick.percent}%" }
                    notifier.show(tick.percent)
                } else {
                    notifier.cancel()
                }
            }
            ChargeAlarmDecision.SUPPRESS -> store.setFiredCycle(true)
            ChargeAlarmDecision.REARM -> {
                store.setFiredCycle(false)
                notifier.cancel()
            }
            ChargeAlarmDecision.IDLE -> Unit
        }
    }

    private companion object {
        val TAG = logTag("Alarm", "Watcher")
    }
}
