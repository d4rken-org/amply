package eu.darken.amply.charging.core.enforcement

import android.content.Context
import android.content.Intent
import dagger.hilt.android.qualifiers.ApplicationContext
import eu.darken.amply.battery.core.BatteryReadout
import eu.darken.amply.battery.core.BatteryReader
import eu.darken.amply.charging.core.ChargePolicy
import eu.darken.amply.charging.core.ChargingPreferences
import eu.darken.amply.charging.core.ChargingRepository
import eu.darken.amply.charging.core.DeviceInfo
import eu.darken.amply.charging.core.adapter.AdapterRegistry
import eu.darken.amply.common.debug.logging.Logging
import eu.darken.amply.common.debug.logging.log
import eu.darken.amply.common.debug.logging.logTag
import eu.darken.amply.main.core.SurfaceUpdater
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * One battery evaluation as handed over by [EnforcementWatcher]: raw fields only, so the watcher can
 * enqueue it without touching the disk, a Binder, or DataStore on the charge-session service's
 * dispatch lock.
 */
data class RawEnforcementTick(
    val plugged: Boolean,
    val percent: Int,
    val batteryStatus: Int,
    val sessionActive: Boolean,
    val batteryIntent: Intent?,
    val observedElapsedRealtimeMillis: Long,
    val wallMillis: Long,
)

/**
 * Serialized, off-service-thread runner for [EnforcementVerdictEngine].
 *
 * Mirrors `ChargeStatsRecorder`: [offer] only enqueues, and every read the verdict needs — the
 * configured-policy read-back, the durable evidence, the policy generation, the live battery
 * properties — happens on this recorder's own IO coroutine. That is what keeps a slow read here from
 * delaying the safety-critical charge-policy restore.
 *
 * A reached verdict is persisted once (the engine keeps re-reaching it while the condition holds),
 * and a *stored* verdict immediately refreshes [ChargingRepository] and re-pushes the widget and
 * tile: the tier it changes decides whether those surfaces may offer control at all.
 */
@Singleton
class EnforcementRecorder @Inject constructor(
    @ApplicationContext private val context: Context,
    private val registry: AdapterRegistry,
    private val evidenceStore: EnforcementEvidenceStore,
    private val preferences: ChargingPreferences,
    private val buildIdentity: BuildIdentitySource,
    private val repository: ChargingRepository,
    private val batteryReader: BatteryReader,
    @EnforcementDispatcher dispatcher: CoroutineDispatcher,
) {
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val ticks = Channel<RawEnforcementTick>(Channel.UNLIMITED)

    // Mutated only by the single consumer coroutine below — no locking needed.
    private var progress: EnforcementProgress? = null
    private var previousPlugged: Boolean? = null
    private var plugSessionId: Long = 0L

    init {
        scope.launch {
            for (tick in ticks) {
                try {
                    onTick(tick)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    log(TAG, Logging.Priority.WARN) { "Enforcement tick failed: ${e.message}" }
                }
            }
        }
    }

    /** Enqueue a raw tick. Returns immediately; the caller (a monitor watcher) never awaits I/O. */
    fun offer(tick: RawEnforcementTick) {
        ticks.trySend(tick)
    }

    /**
     * Whether this observation needs the monitor service alive. Deliberately narrower than "the
     * adapter wants evidence": holding the foreground service up at boot with no cap configured, on a
     * secondary user, or after a terminal refutation would be a persistent notification bought for
     * nothing.
     *
     * The configured-policy condition uses Amply's own last *persistent* write rather than a provider
     * read: this runs under a query budget on the service-start path, and the authoritative read-back
     * still gates every individual tick in [onTick].
     */
    suspend fun shouldObserve(): Boolean {
        val evidence = evidenceStore.currentState()
        val device = DeviceInfo.current(context)
        return shouldObserveEnforcement(
            evidence = evidence,
            isSystemUser = device.isSystemUser,
            adapterRequiresEvidence = registry.select(device, evidence).adapter?.enforcementEvidenceRequired == true,
            verificationStarted = preferences.verificationStartedForNow() == buildIdentity.current(),
            persistentPolicy = preferences.lastPersistentPolicyNow(),
        )
    }

    private suspend fun onTick(tick: RawEnforcementTick) {
        if (tick.plugged && previousPlugged == false) plugSessionId++
        previousPlugged = tick.plugged

        val evidence = evidenceStore.currentState()
        val existing = (evidence as? EnforcementEvidenceState.Present)?.evidence
        // A refutation is terminal and a corrupt record is treated as one: nothing left to observe.
        if (evidence is EnforcementEvidenceState.Corrupt) return
        if (existing?.verdict == EnforcementVerdict.REFUTED) return

        val device = DeviceInfo.current(context)
        val adapter = registry.select(device, evidence).adapter ?: return
        if (!adapter.enforcementEvidenceRequired || !device.isSystemUser) return
        if (preferences.verificationStartedForNow() != buildIdentity.current()) return

        val readout = tick.batteryIntent?.let { batteryReader.read(it) } ?: BatteryReadout.UNKNOWN
        val sample = EnforcementSample(
            adapterId = adapter.id,
            buildIdentity = buildIdentity.current(),
            // The authoritative *configured* state. Null on adapters that cannot be read back
            // synchronously, which the engine then declines to evaluate.
            configured = repository.syncReadback(),
            sessionActive = tick.sessionActive,
            plugged = tick.plugged,
            percent = tick.percent.takeIf { it >= 0 } ?: readout.levelPercent ?: -1,
            batteryStatus = tick.batteryStatus,
            chargingStatus = readout.chargingStatus,
            currentNowMicroamps = readout.currentNowMicroamps,
            policyGeneration = preferences.lastRequestedAtNow(),
            plugSessionId = plugSessionId,
            elapsedRealtimeMillis = tick.observedElapsedRealtimeMillis,
            wallMillis = tick.wallMillis,
        )
        val outcome = EnforcementVerdictEngine.evaluate(progress, sample)
        progress = outcome.progress
        val verdict = outcome.verdict ?: return
        // The engine keeps reaching the same verdict while the condition holds; only a change is news.
        if (existing != null && existing.verdict == verdict && existing.adapterId == adapter.id) return
        persist(verdict, adapter.id, outcome.progress?.epoch?.capPercent ?: 0, sample)
    }

    private suspend fun persist(
        verdict: EnforcementVerdict,
        adapterId: String,
        capPercent: Int,
        sample: EnforcementSample,
    ) {
        val evidence = EnforcementEvidence(
            adapterId = adapterId,
            buildIdentity = sample.buildIdentity,
            algorithmVersion = EnforcementVerdictEngine.ALGORITHM_VERSION,
            verdict = verdict,
            capPercent = capPercent,
            observedPercent = sample.percent,
            observedAtWallMillis = sample.wallMillis,
        )
        if (!evidenceStore.record(evidence)) return
        log(TAG, Logging.Priority.INFO) { "Enforcement $verdict at ${sample.percent}% (cap $capPercent%)" }
        // The verdict decides whether control may be offered at all, so the surfaces must not keep
        // rendering the previous tier until something else happens to refresh them.
        repository.refresh()
        try {
            SurfaceUpdater.updateNow(context)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log(TAG, Logging.Priority.WARN) { "Surface push failed: ${e.message}" }
        }
    }

    private companion object {
        val TAG = logTag("Charging", "Enforcement", "Recorder")
    }
}

/**
 * Whether enforcement observation currently needs the monitor service alive. Pure so the conditions
 * are JVM-testable: this is what keeps a foreground service (and its persistent notification) from
 * being held up at boot with no cap configured, on a secondary user, or after a terminal refutation.
 *
 * [persistentPolicy] is Amply's own last persistent write, the cheap durable stand-in for "a cap is
 * configured" — the authoritative read-back still gates each individual tick.
 */
internal fun shouldObserveEnforcement(
    evidence: EnforcementEvidenceState,
    isSystemUser: Boolean,
    adapterRequiresEvidence: Boolean,
    verificationStarted: Boolean,
    persistentPolicy: ChargePolicy?,
): Boolean {
    if (evidence is EnforcementEvidenceState.Corrupt) return false
    if ((evidence as? EnforcementEvidenceState.Present)?.evidence?.verdict == EnforcementVerdict.REFUTED) return false
    if (!adapterRequiresEvidence || !isSystemUser || !verificationStarted) return false
    return persistentPolicy is ChargePolicy.FixedLimit && persistentPolicy.percent < 100
}
