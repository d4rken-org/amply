package eu.darken.amply.charging.core

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import eu.darken.amply.R
import eu.darken.amply.charging.core.access.AccessBackend
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
import kotlinx.coroutines.flow.update
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
    /** True when applying a policy needs Shizuku (system-namespace adapter WSS can't write). */
    val writeRequiresShizuku: Boolean = false,
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
) {
    /**
     * Whether a policy write can currently land. For system-namespace adapters (OnePlus/ColorOS)
     * Shizuku specifically is required — WSS can read the state but cannot write it — so controls
     * across every surface (dashboard, widget, tile) must gate on this, not on `access.canControl`.
     */
    val canApply: Boolean
        get() = controlEnabled && when {
            writeRequiresShizuku -> access?.shizuku?.ready == true
            else -> access?.canControl == true
        }
}

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
    // Separate from operationMutex: the ~25s grant deliberately never holds operationMutex (see below),
    // but manual and automatic callers must still be single-flighted against each other.
    private val grantMutex = Mutex()
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

    suspend fun grantWriteSecureSettings(): Boolean = grantMutex.withLock {
        // Single-flight: the manual setup-card button and the automatic coordinator can both call this.
        // Whoever queued behind an in-flight grant rechecks here and short-circuits if WSS already landed
        // (also covers an external adb grant). Reconcile via refresh() so the UI and the auto-grant
        // coordinator observe the granted permission instead of a stale "missing" snapshot. Uses the
        // lightweight direct-only status, not a full snapshot (which would also poll Shizuku/PackageManager).
        if (accessResolver.direct.status().ready) {
            log(TAG) { "WSS already granted; reconciling and skipping grant" }
            return@withLock refresh().access?.direct?.ready == true
        }
        // Drive only `grantingWss` (the setup-card spinner) via an atomic update; never touch the shared
        // `busy` flag, which a concurrent policy apply owns. Deliberately WITHOUT holding operationMutex:
        // the grant is a slow blocking pm grant and must not serialize ahead of a protective-policy
        // restore/apply. The trailing refresh() still takes operationMutex to reconcile state.
        mutableState.update { it.copy(grantingWss = true, message = null) }
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
            mutableState.update {
                it.copy(
                    grantingWss = false,
                    message = (if (granted) R.string.charging_message_wss_granted else R.string.charging_message_wss_failed)
                        .toCaString(),
                )
            }
            return@withLock granted
        } finally {
            // refreshLocked() preserves grantingWss so an unrelated refresh mid-grant can't clear the
            // spinner; guarantee it never sticks under cancellation or a failed refresh. Non-suspending,
            // so it is safe during coroutine teardown.
            mutableState.update { if (it.grantingWss) it.copy(grantingWss = false) else it }
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
     * Sync-readback adapters use world-readable / unprivileged keys, so the DIRECT provider read is
     * authoritative and — unlike a Shizuku user-service bind, which can block up to ~15s on a cold process
     * ([ShizukuController.service]) — never stalls. Read direct first so that bind is never on the critical
     * path (the widget/tile refresh after a tap); consult Shizuku only as a fallback when the direct read is
     * not authoritative on some ROM. A nominally "ready" but misbehaving Shizuku service therefore can no
     * longer delay verification.
     */
    private suspend fun readSyncWithFallback(adapter: ChargingAdapter): ChargeObservation? {
        val shizuku = accessResolver.shizuku.takeIf { accessResolver.shizuku.status().ready }
        return readSyncDirectFirst(adapter, accessResolver.direct, shizuku)
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
        val backend = accessResolver.writeBackend(preferShizuku = adapter.preferShizukuForWrites)
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
                // Sync-readback adapters read the direct provider first (authoritative, never stalls on a
                // cold Shizuku bind); async (Pixel) keeps the preferred-backend + hardware path.
                val read = when (adapter.verification) {
                    VerificationStrategy.SYNC_READBACK -> readSyncWithFallback(adapter)
                    VerificationStrategy.ASYNC_HARDWARE ->
                        accessResolver.readBackend()?.let { adapter.read(it) }
                }
                when {
                    read is ChargeObservation.Verified -> read
                    // A readable-but-unrecognized OEM value must not be masked by a stale last
                    // request — the state is genuinely unknown, and a session start refuses on it.
                    read is ChargeObservation.Unknown && read.unrecognizedValue -> read
                    else -> adapter.readHardware(context)
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
            writeRequiresShizuku = adapter?.preferShizukuForWrites == true,
            controlEnabled = selection.support.controlEnabled,
            contributionWanted = selection.support.contributionWanted,
            access = access,
            observation = observation,
            pending = pending,
            busy = false,
            // grantingWss is intentionally left default here; mergeRefreshedState (below) carries an
            // in-flight grant's spinner over from the previous state so a concurrent refresh can't clear it.
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
            "refresh(adapter=${published.adapterId}, access=$accessState, " +
                "observation=${published.observation}, pending=${published.pending?.target})"
        }
        return published
    }

    private companion object {
        val TAG = logTag("Charging", "Repository")
    }
}

/**
 * Read a sync-readback adapter's configured state, preferring the [direct] provider and consulting
 * [shizuku] (may be null when not ready) only as a fallback. Direct reads of these adapters' world-readable
 * keys are authoritative and cannot stall, so an *authoritative* direct read — [ChargeObservation.Verified]
 * or a readable-but-unrecognized OEM value — short-circuits without ever binding the Shizuku user service
 * (both backends read the same settings provider, so Shizuku could not report anything stronger). Only a
 * genuinely unreadable direct result falls back to Shizuku.
 */
internal suspend fun readSyncDirectFirst(
    adapter: ChargingAdapter,
    direct: AccessBackend,
    shizuku: AccessBackend?,
): ChargeObservation? {
    val primary = readObservationOrNull(adapter, direct)
    if (primary.isAuthoritativeSyncRead()) return primary
    val fallback = shizuku?.let { readObservationOrNull(adapter, it) }
    return chooseSyncObservation(primary, fallback)
}

/**
 * A sync read that resolves the pending transition on its own: a confirmed policy or a readable-but-
 * unrecognized OEM value (the setting *was* read, we just don't map the value). Mirrors the SYNC_READBACK
 * arm of [computeRefreshPending] — what clears pending is exactly what makes the Shizuku fallback redundant.
 */
private fun ChargeObservation?.isAuthoritativeSyncRead(): Boolean =
    this is ChargeObservation.Verified ||
        (this is ChargeObservation.Unknown && unrecognizedValue)

/** Read via one backend, turning ordinary failures into null but never swallowing cancellation. */
internal suspend fun readObservationOrNull(
    adapter: ChargingAdapter,
    backend: AccessBackend,
): ChargeObservation? = try {
    adapter.read(backend)
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    null
}

/**
 * Pick the strongest of a [primary] (direct) and [fallback] (Shizuku) sync read: Verified beats a
 * readable-but-unrecognized value (which must survive so a session start can refuse) beats a generic
 * unreadable one. Primary wins ties at each tier.
 */
internal fun chooseSyncObservation(
    primary: ChargeObservation?,
    fallback: ChargeObservation?,
): ChargeObservation? = when {
    primary is ChargeObservation.Verified -> primary
    fallback is ChargeObservation.Verified -> fallback
    primary is ChargeObservation.Unknown && primary.unrecognizedValue -> primary
    fallback is ChargeObservation.Unknown && fallback.unrecognizedValue -> fallback
    else -> primary ?: fallback
}

/**
 * Reconstruct the pending settling request from the persisted target+timestamp, clearing it once the window
 * elapses or the request is confirmed resolved.
 *
 * The confirmation signal differs by strategy. For [VerificationStrategy.ASYNC_HARDWARE] (Pixel) only a
 * matching BATTERY_HARDWARE reading confirms, and a hardware reading for a *different* policy is deliberately
 * NOT treated as resolution — mid-transition the HAL legitimately still shows the old policy. For
 * [VerificationStrategy.SYNC_READBACK] the settings readback is authoritative and synchronous, so ANY
 * successful read resolves the transition: a Verified value (matching OR different — a different value is a
 * native/competing change that has already taken effect) or a readable-but-unrecognized OEM value. Only a
 * genuinely unreadable/generic-unknown sync state keeps the request pending until the window expires.
 */
internal fun computeRefreshPending(
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
        VerificationStrategy.SYNC_READBACK ->
            observation is ChargeObservation.Verified ||
                (observation is ChargeObservation.Unknown && observation.unrecognizedValue)
        VerificationStrategy.ASYNC_HARDWARE ->
            hardware is ChargeObservation.Verified &&
                hardware.backend == BackendKind.BATTERY_HARDWARE &&
                hardware.policy == reqPolicy
    }
    if (confirmed) return null
    return PendingRequest(reqPolicy, reqAt)
}
