package eu.darken.amply.rules.core

import eu.darken.amply.monitor.core.ChargeMonitorTick
import eu.darken.amply.monitor.core.ChargeMonitorWatcher
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Keep-alive only — deliberately **not** where rules are evaluated.
 *
 * Watcher ticks are bounded by the service's per-watcher budget and are explicitly optional work
 * that may be dropped. A rule write is neither: it changes the charging policy and owes a restore.
 * So the service calls [RuleApplier.evaluate] directly in its evaluation path, and this binding
 * exists purely so the monitor service stays alive while rules (or owed rule work) exist.
 */
@Singleton
class RulesWatcher @Inject constructor(
    private val applier: RuleApplier,
) : ChargeMonitorWatcher {

    override val id = "charge_rules"

    override suspend fun isEnabled(): Boolean = applier.isServiceRequired()

    override suspend fun onBatteryTick(tick: ChargeMonitorTick) = Unit
}
