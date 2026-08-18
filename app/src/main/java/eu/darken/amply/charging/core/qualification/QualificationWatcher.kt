package eu.darken.amply.charging.core.qualification

import eu.darken.amply.monitor.core.ChargeMonitorTick
import eu.darken.amply.monitor.core.ChargeMonitorWatcher
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Feeds the charge service's battery ticks to [QualificationRunner].
 *
 * Does no work of its own: [onBatteryTick] copies the tick and enqueues it, exactly as
 * `EnforcementWatcher` does. Watchers run under the service's dispatch lock with a 5 s budget, and
 * this one's real work — a settings readback, a policy write, a DataStore round-trip — is all far too
 * slow and too blocking to belong there.
 *
 * [isEnabled] returning true while a run record exists is also what keeps the foreground service
 * alive through the run (`ChargeSessionService.anyWatcherEnabled`) and what gets it restarted at boot
 * (`ServiceDispatch.startAction`). No service change is needed for either.
 */
@Singleton
class QualificationWatcher @Inject constructor(
    private val runner: QualificationRunner,
) : ChargeMonitorWatcher {

    override val id = "qualification_run"

    override suspend fun isEnabled(): Boolean = runner.isRunning()

    override suspend fun onBatteryTick(tick: ChargeMonitorTick) {
        // Only the fact that an evaluation is due, plus what the service knew about a live session.
        // The battery readings come from one snapshot the runner takes on its own worker, so the
        // level, the charge counter and the timestamp all describe the same instant.
        runner.offer(RawQualificationTick(sessionActive = tick.sessionActive))
    }
}
