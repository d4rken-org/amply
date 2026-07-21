package eu.darken.amply.charging.core

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import eu.darken.amply.R
import eu.darken.amply.charging.core.access.AccessResolver
import eu.darken.amply.charging.core.access.AccessSnapshot
import eu.darken.amply.charging.core.access.shizuku.ShizukuController
import eu.darken.amply.charging.core.adapter.AdapterRegistry
import eu.darken.amply.charging.core.adapter.AdapterSelection
import eu.darken.amply.charging.core.adapter.ChargingAdapter
import eu.darken.amply.charging.core.adapter.VerificationStrategy
import eu.darken.amply.charging.core.ChargingPreferences
import eu.darken.amply.common.ca.CaString
import eu.darken.amply.common.ca.caString
import eu.darken.amply.common.ca.toCaString
import eu.darken.amply.common.debug.logging.Logging
import eu.darken.amply.common.debug.logging.log
import eu.darken.amply.common.debug.logging.logTag
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Merge a freshly-computed [built] state with the live [prev] one for publication. A Shizuku-driven
 * WRITE_SECURE_SETTINGS grant sets busy/grantingWss/message outside operationMutex, so while that grant is
 * in flight a concurrent refresh preserves those transient fields instead of resetting them — the grant's
 * own finally block is what clears them. All non-transient fields come from the fresh [built] state.
 */
internal fun mergeRefreshedState(prev: ChargingState, built: ChargingState): ChargingState =
    if (prev.grantingWss) {
        built.copy(busy = prev.busy, grantingWss = true, message = prev.message)
    } else {
        built
    }

data class ChargingState(
    val device: DeviceInfo = DeviceInfo.current(),
    val adapterName: CaString = R.string.adapter_name_detecting.toCaString(),
    val adapterId: String? = null,
    val supportedPolicies: List<ChargePolicy> = emptyList(),
    val reconnectSupported: Boolean = false,
    /** True when the adapter's configured state is directly readable — Shizuku adds nothing for verification. */
    val syncVerification: Boolean = false,
    val controlEnabled: Boolean = false,
    val contributionWanted: Boolean = false,
    val access: AccessSnapshot? = null,
    val observation: ChargeObservation = ChargeObservation.Unknown(R.string.charging_reason_loading.toCaString()),
    val pending: PendingRequest? = null,
    val busy: Boolean = false,
    // Set only while a Shizuku-driven WRITE_SECURE_SETTINGS grant is in flight, so the setup card can
    // show a progress cue on that specific action without conflating it with a policy apply (both busy).
    val grantingWss: Boolean = false,
    val message: CaString? = null,
)

@Singleton
class ChargingRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val registry: AdapterRegistry,
    private val accessResolver: AccessResolver,
    private val preferences: ChargingPreferences,
    private val shizukuController: ShizukuController,
    private val settleScheduler: SettleScheduler,
) {
    private val operationMutex = Mutex()
    private val mutableState = MutableStateFlow(ChargingState())
    val state: StateFlow<ChargingState> = mutableState.asStateFlow()

    suspend fun refresh(message: CaString? = null): ChargingState = operationMutex.withLock {
        refreshLocked(message)
    }

    /** Shizuku availability transitions, for a foreground watcher awaiting an external access grant. */
    fun accessEvents(): Flow<Unit> = shizukuController.accessEvents

    /**
     * Cheap access-only re-check for the foreground grant watcher: probe access and reconcile with a full
     * refresh ONLY when it actually changed. Skips while a grant/apply is in flight so a repeated poll can
     * never erase the grant spinner or a transient message — grantWriteSecureSettings() deliberately runs
     * its ~10s Binder call without the mutex — and avoids the full hardware/DataStore/package work (and
     * log line) of refresh() on every tick.
     */
    suspend fun refreshAccessIfChanged() {
        val current = mutableState.value
        if (current.busy || current.grantingWss) return
        // Let snapshot() failures (incl. CancellationException) propagate to the caller's monitor, which
        // rethrows cancellation and logs the rest — swallowing here would break lifecycle cancellation.
        val snapshot = accessResolver.snapshot()
        if (snapshot != current.access) refresh()
    }

    suspend fun applyPersistent(policy: ChargePolicy): ApplyResult = operationMutex.withLock {
        applyLocked(policy, persistent = true)
    }

    suspend fun applyTemporary(policy: ChargePolicy): ApplyResult = operationMutex.withLock {
        applyLocked(policy, persistent = false)
    }

    /** Re-write that forces a real settings change so a missed observer registration is re-triggered. */
    suspend fun reapplyPersistent(policy: ChargePolicy): ApplyResult = operationMutex.withLock {
        applyLocked(policy, persistent = true, forceNotify = true)
    }

    suspend fun requestShizukuPermission(): Boolean {
        val result = runCatching { shizukuController.requestPermission() }.getOrDefault(false)
        refresh(
            (if (result) R.string.charging_message_shizuku_granted else R.string.charging_message_shizuku_denied)
                .toCaString(),
        )
        return result
    }

    suspend fun grantWriteSecureSettings(): Boolean {
        // Show the in-progress cue immediately, but deliberately WITHOUT holding operationMutex: the grant
        // is a ~10s blocking pm grant and must not serialize ahead of a concurrent protective-policy
        // restore/apply. The trailing refresh() still takes the lock to reconcile state.
        mutableState.value = state.value.copy(busy = true, grantingWss = true, message = null)
        try {
            try {
                // The AIDL grant is a blocking Binder transaction; keep it off the main thread to avoid an ANR.
                withContext(Dispatchers.IO) { shizukuController.grantWriteSecureSettings() }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Ignored: success is judged by the refreshed permission state below, not this call's
                // boolean — pm grant can commit while its reply is lost.
                log(TAG, Logging.Priority.WARN) { "Shizuku WSS grant call failed: ${e.message}" }
            }
            val granted = refresh().access?.direct?.ready == true
            mutableState.value = state.value.copy(
                message = (if (granted) R.string.charging_message_wss_granted else R.string.charging_message_wss_failed)
                    .toCaString(),
            )
            return granted
        } finally {
            // refresh() clears these on the normal path; guarantee they never stick on this singleton under
            // cancellation or a failed refresh. Non-suspending, so it is safe during coroutine teardown.
            val current = state.value
            if (current.busy || current.grantingWss) {
                mutableState.value = current.copy(busy = false, grantingWss = false)
            }
        }
    }

    fun nativeSettingsIntent() = registry.select().adapter?.nativeSettingsIntent(context)

    fun currentAdapter(): ChargingAdapter? = registry.select().adapter

    /** Configured-settings readback, only for adapters whose writes are synchronously verifiable. */
    suspend fun syncReadback(): ChargeObservation? {
        val adapter = registry.select().adapter ?: return null
        if (adapter.verification != VerificationStrategy.SYNC_READBACK) return null
        return readSyncWithFallback(adapter)
    }

    /**
     * Sync-readback adapters use world-readable keys, so a nominally "ready" but misbehaving
     * Shizuku service (main binder up, user service broken) must not block verification —
     * fall back to the direct provider backend on a non-verified read.
     */
    private suspend fun readSyncWithFallback(adapter: ChargingAdapter): ChargeObservation? {
        val primary = accessResolver.readBackend() ?: return null
        val first = runCatching { adapter.read(primary) }.getOrNull()
        if (first is ChargeObservation.Verified || primary === accessResolver.direct) return first
        val second = runCatching { adapter.read(accessResolver.direct) }.getOrNull()
        // Signal strength: Verified > readable-but-unrecognized (must survive the fallback so
        // session start can refuse) > generic unreadable.
        return when {
            second is ChargeObservation.Verified -> second
            first is ChargeObservation.Unknown && first.unrecognizedValue -> first
            second is ChargeObservation.Unknown && second.unrecognizedValue -> second
            else -> first ?: second
        }
    }

    fun shizukuManagerPackage(): String? = shizukuController.managerPackage()

    private suspend fun applyLocked(
        policy: ChargePolicy,
        persistent: Boolean,
        forceNotify: Boolean = false,
    ): ApplyResult {
        log(TAG, Logging.Priority.INFO) {
            "apply(policy=${policy.stableId}, persistent=$persistent, forceNotify=$forceNotify)"
        }
        val selection = registry.select()
        val adapter = selection.adapter
        if (adapter == null || !selection.support.controlEnabled) {
            val detail = selection.support.detail.toCaString()
            val observation = ChargeObservation.Unsupported(detail)
            mutableState.value = state.value.copy(observation = observation, message = detail)
            return ApplyResult(false, observation, context.getString(selection.support.detail))
        }
        if (policy !in adapter.supportedPolicies) {
            val observation = ChargeObservation.Unsupported(
                caString {
                    it.getString(
                        R.string.charging_reason_policy_unsupported,
                        policy.label.get(it),
                        adapter.displayName.get(it),
                    )
                },
            )
            return ApplyResult(false, observation, "Unsupported policy")
        }
        val backend = accessResolver.writeBackend()
        if (backend == null) {
            val observation = ChargeObservation.NeedsSetup(R.string.charging_reason_needs_setup.toCaString())
            mutableState.value = state.value.copy(
                observation = observation,
                message = R.string.charging_message_setup_required.toCaString(),
            )
            return ApplyResult(false, observation, "Setup required")
        }

        mutableState.value = state.value.copy(
            busy = true,
            message = caString { it.getString(R.string.charging_message_applying, policy.label.get(it)) },
        )
        val written = try {
            if (forceNotify) adapter.reapply(policy, backend) else adapter.apply(policy, backend)
        } catch (e: CancellationException) {
            // A cancelled write must not run failure side effects (state churn, notifications).
            throw e
        } catch (e: Exception) {
            false
        }
        if (!written) {
            log(TAG, Logging.Priority.ERROR) { "Settings write failed for ${policy.stableId}" }
            val observation = ChargeObservation.Unknown(R.string.charging_reason_write_failed.toCaString())
            // Clear any stale pending so the failure is not masked by a prior request's "applying…" cue.
            mutableState.value = state.value.copy(
                busy = false,
                observation = observation,
                pending = null,
                message = R.string.charging_message_write_failed.toCaString(),
            )
            return ApplyResult(false, observation, "Write failed")
        }

        // The physical write committed. Record it durably even under cancellation (the setting already
        // changed), and never strand busy=true nor lose the fact that the write landed.
        val now = System.currentTimeMillis()
        withContext(NonCancellable) { preferences.recordRequested(policy, persistent, now) }
        return try {
            val access = accessResolver.snapshot()
            val observation = when (adapter.verification) {
                // The configured values are directly readable; any read backend verifies.
                VerificationStrategy.SYNC_READBACK -> readSyncWithFallback(adapter)
                VerificationStrategy.ASYNC_HARDWARE ->
                    if (access.shizuku.ready) adapter.read(accessResolver.shizuku) else null
            } as? ChargeObservation.Verified ?: ChargeObservation.LastRequested(policy)
            // Suppress the settling cue when there is no transition to wait for: sync-readback adapters
            // apply immediately, and async hardware may already report the target on a no-op re-apply.
            val settled = when (adapter.verification) {
                VerificationStrategy.SYNC_READBACK ->
                    (observation as? ChargeObservation.Verified)?.policy == policy
                VerificationStrategy.ASYNC_HARDWARE ->
                    (adapter.readHardware(context) as? ChargeObservation.Verified)?.let {
                        it.backend == BackendKind.BATTERY_HARDWARE && it.policy == policy
                    } ?: false
            }
            val pending = if (settled) null else PendingRequest(policy, now)
            // Timing copy lives solely in the dashboard's settling line; these must stay accurate
            // when nothing is pending (sync-readback adapters, no-op re-applies).
            val messageRes = if (observation is ChargeObservation.Verified) {
                R.string.charging_message_verified
            } else {
                R.string.charging_message_requested
            }
            val message = caString { it.getString(messageRes, policy.label.get(it)) }
            mutableState.value = state.value.copy(
                busy = false,
                access = access,
                observation = observation,
                pending = pending,
                message = message,
            )
            if (pending != null) settleScheduler.schedule(now)
            ApplyResult(true, observation, message.get(context))
                .also { log(TAG, Logging.Priority.INFO) { "Applied ${policy.stableId}: $observation" } }
        } catch (e: CancellationException) {
            // The write committed and is recorded; reflect it so the UI doesn't stay busy and the settle
            // clear still fires, then honour cancellation.
            mutableState.value = state.value.copy(
                busy = false,
                observation = ChargeObservation.LastRequested(policy),
                pending = PendingRequest(policy, now),
            )
            settleScheduler.schedule(now)
            throw e
        } catch (e: Exception) {
            // Write landed but metadata failed: report a truthful degraded success, keep the pending cue,
            // and guarantee busy is cleared.
            log(TAG, Logging.Priority.WARN) { "Post-write metadata failed for ${policy.stableId}: ${e.message}" }
            val observation = ChargeObservation.LastRequested(policy)
            val message = caString { it.getString(R.string.charging_message_requested, policy.label.get(it)) }
            mutableState.value = state.value.copy(
                busy = false,
                observation = observation,
                pending = PendingRequest(policy, now),
                message = message,
            )
            settleScheduler.schedule(now)
            ApplyResult(true, observation, message.get(context))
        }
    }

    private suspend fun refreshLocked(message: CaString?): ChargingState {
        val selection: AdapterSelection = registry.select()
        val access = accessResolver.snapshot()
        val adapter = selection.adapter
        val observation = when {
            adapter == null -> ChargeObservation.Unsupported(selection.support.detail.toCaString())
            !selection.support.controlEnabled -> ChargeObservation.Unsupported(selection.support.detail.toCaString())
            !access.canControl -> ChargeObservation.NeedsSetup(R.string.charging_reason_needs_setup.toCaString())
            else -> {
                val backend = accessResolver.readBackend()
                val read = if (backend != null) adapter.read(backend) else null
                if (read is ChargeObservation.Verified) {
                    read
                } else {
                    adapter.readHardware(context)
                        ?: preferences.lastRequestedNow()?.let(ChargeObservation::LastRequested)
                        ?: read
                        ?: ChargeObservation.Unknown(R.string.charging_reason_state_unavailable.toCaString())
                }
            }
        }
        // Confirmation must come from the hardware, not the settings-level `observation` above: with Shizuku
        // or WSS the settings readback is `Verified` while the HAL is still converging, so pending would
        // otherwise never clear until the window expired.
        val pending = computeRefreshPending(
            reqPolicy = preferences.lastRequestedNow(),
            reqAt = preferences.lastRequestedAtNow(),
            now = System.currentTimeMillis(),
            observation = observation,
            hardware = adapter?.readHardware(context),
            verification = adapter?.verification ?: VerificationStrategy.ASYNC_HARDWARE,
        )
        val built = ChargingState(
            device = DeviceInfo.current(context),
            adapterName = adapter?.displayName ?: R.string.adapter_name_unsupported.toCaString(),
            adapterId = adapter?.id,
            supportedPolicies = adapter?.supportedPolicies.orEmpty(),
            reconnectSupported = adapter?.reconnectGestureSupported == true,
            syncVerification = adapter?.verification == VerificationStrategy.SYNC_READBACK,
            controlEnabled = selection.support.controlEnabled,
            contributionWanted = selection.support.contributionWanted,
            access = access,
            observation = observation,
            pending = pending,
            busy = false,
            message = message,
        )
        // A WSS grant publishes its busy/grantingWss/message cue OUTSIDE operationMutex (its ~10s Binder
        // call must not serialize behind a policy apply), so any concurrent refresh — this access poll, or
        // the resume/battery refreshes in MainActivity — must not clobber it. updateAndGet closes the
        // check-then-act window: if a grant became active between building and publishing, its transient
        // fields carry over and the grant's own finally block still clears them.
        val published = mutableState.updateAndGet { prev -> mergeRefreshedState(prev, built) }
        log(TAG, Logging.Priority.VERBOSE) {
            val accessState = published.access
                ?.let { "direct=${it.direct.ready},shizuku=${it.shizuku.ready}" }
                ?: "none"
            "refresh(adapter=${published.adapterId}, access=$accessState, observation=${published.observation})"
        }
        return published
    }

    /**
     * Reconstruct the pending settling request from the persisted target+timestamp, clearing it once the
     * window elapses, the hardware confirms the target, or access is lost. Deliberately does NOT clear on
     * "hardware reports a different policy": mid-transition the hardware legitimately still shows the old
     * policy, so that is not evidence of a native change. A genuine native change within the window shows a
     * stale cue for at most one window before expiry corrects it.
     */
    private fun computeRefreshPending(
        reqPolicy: ChargePolicy?,
        reqAt: Long,
        now: Long,
        observation: ChargeObservation,
        hardware: ChargeObservation?,
        verification: VerificationStrategy,
    ): PendingRequest? {
        if (reqPolicy == null || reqAt <= 0L) return null
        if (now - reqAt !in 0 until SETTLING_WINDOW_MILLIS) return null
        if (observation is ChargeObservation.Unsupported || observation is ChargeObservation.NeedsSetup) return null
        val confirmed = when (verification) {
            // Settings readback IS confirmation when writes apply synchronously.
            VerificationStrategy.SYNC_READBACK ->
                observation is ChargeObservation.Verified && observation.policy == reqPolicy
            VerificationStrategy.ASYNC_HARDWARE ->
                hardware is ChargeObservation.Verified &&
                    hardware.backend == BackendKind.BATTERY_HARDWARE &&
                    hardware.policy == reqPolicy
        }
        if (confirmed) return null
        return PendingRequest(reqPolicy, reqAt)
    }

    private companion object {
        val TAG = logTag("Charging", "Repository")
    }
}
