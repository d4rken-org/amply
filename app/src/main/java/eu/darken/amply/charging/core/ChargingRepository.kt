package eu.darken.amply.charging.core

import android.content.Context
import android.content.Intent
import dagger.hilt.android.qualifiers.ApplicationContext
import android.os.BatteryManager
import eu.darken.amply.R
import eu.darken.amply.battery.core.BatteryReader
import eu.darken.amply.battery.core.BatteryReadout
import eu.darken.amply.charging.core.access.AccessBackend
import eu.darken.amply.charging.core.access.AccessResolver
import eu.darken.amply.charging.core.access.AccessSnapshot
import eu.darken.amply.charging.core.access.shizuku.ShizukuController
import eu.darken.amply.charging.core.adapter.AdapterRegistry
import eu.darken.amply.charging.core.adapter.AdapterSelection
import eu.darken.amply.charging.core.adapter.AdapterSupport
import eu.darken.amply.charging.core.adapter.ChargingAdapter
import eu.darken.amply.charging.core.adapter.OemChargingShortcuts
import eu.darken.amply.charging.core.adapter.VerificationStrategy
import eu.darken.amply.charging.core.ChargingPreferences
import eu.darken.amply.charging.core.enforcement.BuildIdentitySource
import eu.darken.amply.charging.core.enforcement.EnforcementEvidenceState
import eu.darken.amply.charging.core.enforcement.EnforcementEvidenceStore
import eu.darken.amply.charging.core.enforcement.EnforcementStatus
import eu.darken.amply.charging.core.qualification.QualificationEvidenceState
import eu.darken.amply.charging.core.qualification.QualificationEvidenceStore
import eu.darken.amply.charging.core.qualification.QualificationRunStore
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
    /**
     * Standing capability note for a control-enabled adapter (write latency, Shizuku requirement,
     * replug semantics — the `adapter_detail_*_ready` strings). Null whenever control is unavailable:
     * the gate-failure text already reaches the UI as the observation's reason, and populating both
     * would print the same sentence twice.
     */
    val adapterDetail: CaString? = null,
    val supportedPolicies: List<ChargePolicy> = emptyList(),
    /**
     * The selected adapter's protective default (e.g. FixedLimit(80) on Pixel, Adaptive on Xiaomi), or
     * null before an adapter is selected. Carried on the state so observing surfaces (the widget) can
     * label the "∞ protect" action without a separate suspend adapter lookup at composition time.
     */
    val defaultProtectivePolicy: ChargePolicy? = null,
    val reconnectSupported: Boolean = false,
    /** True when the adapter's configured state is directly readable — Shizuku adds nothing for verification. */
    val syncVerification: Boolean = false,
    /** True when applying a policy needs Shizuku (system-namespace adapter WSS can't write). */
    val writeRequiresShizuku: Boolean = false,
    val controlEnabled: Boolean = false,
    /**
     * Where this device sits on the "does the hardware actually enforce the cap" question, or null
     * where the question doesn't apply (every adapter but LineageOS today). Surfaces must not present
     * a settings-level read-back as proof while this is
     * [eu.darken.amply.charging.core.enforcement.EnforcementStatus.UNVERIFIED].
     */
    val enforcement: EnforcementStatus? = null,
    val contributionWanted: Boolean = false,
    /** See [eu.darken.amply.charging.core.adapter.AdapterSupport.guidedCaptureUseful]. */
    val guidedCaptureUseful: Boolean = true,
    /**
     * False until adapter selection has actually run. Callers that *withhold* UI on an adapter capability must
     * wait for this: the capability defaults are permissive, so acting on them before selection would briefly
     * show something the resolved state forbids.
     */
    val adapterResolved: Boolean = false,
    val access: AccessSnapshot? = null,
    val observation: ChargeObservation = ChargeObservation.Unknown(R.string.charging_reason_loading.toCaString()),
    val pending: PendingRequest? = null,
    /**
     * Standing contradiction: the charging hardware was EXPECTED to confirm the last requested
     * policy (see [eu.darken.amply.charging.core.adapter.ChargingAdapter.confirmationExpected]) and
     * still has not, well past the settling window. Catches both a stuck apply and "plugged in
     * today, HAL never engaged" — the silent-failure class where the configured setting reads back
     * fine while charging is not actually limited. Null whenever no expectation exists.
     */
    val unconfirmedTarget: ChargePolicy? = null,
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

    /**
     * True when an adapter — live or lab — recognized this device's family, i.e. Amply knows *what* it is looking
     * at even when it cannot control it. Derived from [adapterId] rather than carried separately: [AdapterRegistry]
     * returns an adapter exactly when some probe matched, and its catch-all is the only null-adapter selection, so a
     * mirrored flag could only ever drift.
     */
    val adapterMatched: Boolean get() = adapterId != null

    /**
     * Whether the unprivileged device metadata alone gives a maintainer somewhere to start, which is what makes it
     * worth a public device-support issue. Two independent sources, because neither covers the other:
     *
     * - a matched adapter, including a lab one. Those match on manufacturer or ROM marker, so "Samsung, One UI
     *   unreadable" still says the feature exists and names the skin whose key mapping to check.
     * - a ROM marker or a protection key/provider the probes found directly. [AdapterRegistry]'s family matchers are
     *   manufacturer lists and property checks, so a rebranded Oplus device or a LineageOS derivative that ships the
     *   settings provider without the Lineage property lands in the catch-all while still carrying a real lead.
     *
     * False means every probe came back empty: the report can only state that none of the known families matched,
     * which no maintainer can act on. Those devices are pointed at the contribution wizard, which can discover a key
     * Amply does not know yet, or at email, where a dead end costs one reply instead of a public issue.
     */
    val hasSupportLead: Boolean
        get() = adapterMatched ||
            device.hasProtectBattery ||
            device.hasBatteryChargeLimit ||
            device.isGrapheneOs ||
            device.hasLineageSettingsProvider ||
            device.hasChargingOptimization ||
            device.oneUiVersion != null ||
            device.hyperOsVersion != null ||
            device.oplusRomVersion != null ||
            // isLineageOs, not lineageOsVersion: the version property is SELinux-denied to apps and reads back
            // empty on every real LineageOS build, so it would contribute nothing here.
            device.isLineageOs
}

@Singleton
class ChargingRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val registry: AdapterRegistry,
    private val accessResolver: AccessResolver,
    private val preferences: ChargingPreferences,
    private val shizukuController: ShizukuController,
    private val settleScheduler: SettleScheduler,
    private val batteryReader: BatteryReader,
    private val evidenceStore: EnforcementEvidenceStore,
    private val qualificationStore: QualificationEvidenceStore,
    private val runStore: QualificationRunStore,
    private val buildIdentity: BuildIdentitySource,
) {
    private val operationMutex = Mutex()

    // Debounce carry-over for the hardware-unconfirmed detector (see debounceUnconfirmed). Only
    // touched inside refreshLocked, which always runs under operationMutex. Process death resets it —
    // the warning then just needs one more stability interval, never the unsafe direction.
    private var unconfirmedCandidate: ChargePolicy? = null
    private var unconfirmedSince: Long = 0L
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

    /**
     * Repay a protective policy the user ALREADY had: the session restore, its rollback, and boot
     * recovery. Identical to [applyPersistent]/[reapplyPersistent] except that it does not apply the
     * enforcement evidence tier.
     *
     * The gate exists to withhold NEW control on a build whose hardware was never observed honouring
     * a cap; withholding a restore instead strands the device in the temporary session's Unrestricted
     * state, which is the opposite of what the gate is for. The composite build identity changes on
     * every nightly/OTA, so a confirmed device with a session open across an update comes back as a
     * candidate with a restore still owed. Every other precondition still applies — the adapter must
     * match, the probe's own `controlEnabled` must hold (system user, provider present), the policy
     * must be supported, and a write backend must exist.
     *
     * Ordinary persistent and temporary user writes stay on the gated path.
     */
    internal suspend fun restorePersistent(policy: ChargePolicy, forceNotify: Boolean = false): ApplyResult =
        operationMutex.withLock {
            applyLocked(policy, persistent = true, forceNotify = forceNotify, evidenceGated = false)
        }

    /**
     * A write commanded by a guided qualification run. Ungated for the same reason as
     * [restorePersistent] — the tier is exactly the question the run exists to answer, so requiring it
     * first would make the run unable to run on any device that needs it — but with two extra guards
     * that [restorePersistent] does not need:
     *
     * - **[runToken] must match a live run record.** Without a run in flight this path does not exist,
     *   which is what keeps an ungated write from becoming a general bypass of the enforcement gate.
     *   The token is generated per run and never leaves the process except into that record.
     * - **`persistent = false`.** `ChargingPreferences.recordRequested` only writes `protective` and
     *   `lastPersistent` for persistent requests, so a run cycling through policies never disturbs the
     *   user's protective baseline or the reconnect gesture's any-level arming basis. Only the final
     *   restore, which goes through [restorePersistent], touches those.
     *
     * `forceNotify` routes through `ChargingAdapter.reapply`: the second cut writes a value that may
     * already be configured, and a same-value write does not re-trigger every OEM's observer.
     */
    internal suspend fun applyForQualification(policy: ChargePolicy, runToken: String): ApplyResult =
        operationMutex.withLock {
            val live = runStore.currentRun()
            if (live == null || live.runToken.isBlank() || live.runToken != runToken) {
                log(TAG, Logging.Priority.WARN) { "applyForQualification refused: no live run for this token" }
                val observation = ChargeObservation.Unsupported(
                    R.string.charging_reason_qualification_not_running.toCaString(),
                )
                return@withLock ApplyResult(false, observation, "No qualification run in progress")
            }
            applyLocked(policy, persistent = false, forceNotify = true, evidenceGated = false)
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

    /**
     * The selected adapter's *capabilities* only. The enforcement gate decides whether control is
     * allowed, never which adapter matched, so these callers pass the fail-closed
     * [EnforcementEvidenceState.Loading] rather than paying for a store read: the worst this can
     * produce is `controlEnabled = false`, which none of them reads.
     */
    private fun capabilityAdapter(): ChargingAdapter? =
        registry.select(
            evidenceState = EnforcementEvidenceState.Loading,
            qualification = QualificationEvidenceState.Loading,
        ).adapter

    /**
     * Never null: a device with no adapter still gets the generic battery-settings chain. Returning null here made
     * every unmapped device (any brand Amply carries no adapter for) land on Battery Saver, because the only caller
     * substituted that directly — while every lab adapter deliberately prefers the battery-usage screen.
     */
    fun nativeSettingsIntent(): Intent = capabilityAdapter()?.nativeSettingsIntent(context)
        ?: OemChargingShortcuts.genericBatterySettings(context)

    fun currentAdapter(): ChargingAdapter? = capabilityAdapter()

    /** Configured-settings readback, only for adapters whose writes are synchronously verifiable. */
    suspend fun syncReadback(): ChargeObservation? {
        val adapter = capabilityAdapter() ?: return null
        if (adapter.verification != VerificationStrategy.SYNC_READBACK) return null
        return readSyncWithFallback(adapter)
    }

    /**
     * Most sync-readback adapters use world-readable keys, so the DIRECT provider read is
     * authoritative and — unlike a Shizuku user-service bind, which can block up to ~15s on a cold process
     * ([ShizukuController.service]) — never stalls. Read direct first so that bind is never on the critical
     * path (the widget/tile refresh after a tap); consult Shizuku as the fallback when the direct read is
     * not authoritative — including GrapheneOS's @Protected key, where the direct read is always denied
     * and Shizuku (shell UID) is the only readable path. A nominally "ready" but misbehaving Shizuku service therefore can no
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
        evidenceGated: Boolean = true,
    ): ApplyResult {
        log(TAG, Logging.Priority.INFO) {
            "apply(policy=${policy.stableId}, persistent=$persistent, forceNotify=$forceNotify, " +
                "evidenceGated=$evidenceGated)"
        }
        val selection = if (evidenceGated) selectGated() else selectForRestore()
        val adapter = selection.adapter
        if (adapter == null || !selection.support.controlEnabled) {
            val detail = selection.support.detail.toCaString()
            val observation = ChargeObservation.Unsupported(detail)
            // Also drop the standing ready note and any hardware-unconfirmed warning: this branch
            // means the gate just failed on a fresh selection (a capability can vanish between
            // refresh and tap), and both fields' contracts exclude Unsupported states.
            mutableState.value = state.value.copy(
                observation = observation,
                message = detail,
                adapterDetail = null,
                unconfirmedTarget = null,
            )
            return ApplyResult(false, observation, context.getString(selection.support.detail))
        }
        // The licensed set, not the adapter's raw one: a self-qualified tier proves only the policies
        // its run exercised, and the display narrowing above would be cosmetic if a write could still
        // reach the others.
        val writable = selection.support.licensedPolicies ?: adapter.supportedPolicies
        if (policy !in writable) {
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
                // NeedsSetup never warns (detector contract); drop a now-contradictory stale warning.
                unconfirmedTarget = null,
            )
            return ApplyResult(false, observation, "Setup required")
        }

        // Plug-latched adapters: capture plug state BEFORE the write — it decides whether this write
        // can take effect now (unplugged: the very next plug session samples it) or must wait for a
        // replug. Null = plug state unreadable; treated as plugged, never claiming an effect that may
        // not exist. Re-sampled AFTER the write below: a plug landing between this sample and the
        // write's effect latches the OLD value, so "written unplugged" is only claimed when both
        // samples agree.
        val pluggedBeforeWrite: Boolean? = if (adapter.policyLatchesAtPlug) {
            batteryReader.read().plugged?.let { it != 0 }
        } else {
            null
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
            // The old unconfirmed warning goes too: a multi-key write can fail after partially changing
            // configuration, so the previous target is no longer a safe standing claim — the next
            // refresh recomputes from live evidence.
            mutableState.value = state.value.copy(
                busy = false,
                observation = observation,
                pending = null,
                unconfirmedTarget = null,
                message = R.string.charging_message_write_failed.toCaString(),
            )
            return ApplyResult(false, observation, "Write failed")
        }

        // The physical write committed. Record it durably even under cancellation (the setting already
        // changed), and never strand busy=true nor lose the fact that the write landed. Unplugged is
        // only trusted when the samples on BOTH sides of the write agree (see pluggedBeforeWrite).
        val pluggedAtWrite: Boolean? = if (adapter.policyLatchesAtPlug) {
            val after = runCatching { batteryReader.read().plugged?.let { it != 0 } }.getOrNull()
            if (pluggedBeforeWrite == false && after == false) false else true
        } else {
            null
        }
        val now = System.currentTimeMillis()
        withContext(NonCancellable) { preferences.recordRequested(policy, persistent, now, plugged = pluggedAtWrite) }
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
            // Plug-latched adapters are the exception: their readback only proves *configuration* —
            // settled iff written unplugged (the next plug session samples it) or the hardware already
            // reports the exact target (no-op re-apply while enforcing).
            val settled = when {
                adapter.policyLatchesAtPlug ->
                    pluggedAtWrite == false || hardwareConfirms(adapter.readHardware(context), policy)
                else -> when (adapter.verification) {
                    VerificationStrategy.SYNC_READBACK ->
                        (observation as? ChargeObservation.Verified)?.policy == policy
                    VerificationStrategy.ASYNC_HARDWARE ->
                        hardwareConfirms(adapter.readHardware(context), policy)
                }
            }
            val pending = if (settled) {
                null
            } else {
                PendingRequest(policy, now, awaitingReplug = adapter.policyLatchesAtPlug)
            }
            // Timing copy lives solely in the dashboard's settling line; these must stay accurate
            // when nothing is pending (sync-readback adapters, no-op re-applies).
            val messageRes = when {
                pending?.awaitingReplug == true -> R.string.charging_message_applied_replug
                observation is ChargeObservation.Verified -> R.string.charging_message_verified
                else -> R.string.charging_message_requested
            }
            val message = caString { it.getString(messageRes, policy.label.get(it)) }
            mutableState.value = state.value.copy(
                busy = false,
                access = access,
                observation = observation,
                pending = pending,
                // A fresh write voids any prior contradiction: the detector re-arms via refresh once
                // the new request is past its own grace threshold. Stale warnings must never render
                // over a new request's settling phase (all three publication copies clear this).
                unconfirmedTarget = null,
                message = message,
            )
            // Always schedule an eventual surface re-push, even for a settled write (pending == null).
            // SYNC_READBACK adapters (Samsung/Xiaomi/Oplus) verify synchronously and leave pending null,
            // so without this a static widget/tile on a killed process would never be pushed the new
            // state after a tap. Scheduling with `now` fires the worker AFTER the settling window, so its
            // refresh() computes pending == null (no phantom settling is reintroduced) and only re-pushes.
            // Isolate the call locally: a scheduler failure must not fall into the metadata-failure catch
            // below, which would overwrite this just-published settled state with a phantom PendingRequest.
            try {
                settleScheduler.schedule(now)
            } catch (e: Exception) {
                log(TAG, Logging.Priority.WARN) { "Surface re-push scheduling failed: ${e.message}" }
            }
            ApplyResult(true, observation, message.get(context))
                .also { log(TAG, Logging.Priority.INFO) { "Applied ${policy.stableId}: $observation" } }
        } catch (e: CancellationException) {
            // The write committed and is recorded; reflect it so the UI doesn't stay busy and the settle
            // clear still fires, then honour cancellation.
            mutableState.value = state.value.copy(
                busy = false,
                observation = ChargeObservation.LastRequested(policy),
                pending = PendingRequest(policy, now, awaitingReplug = fallbackAwaitsReplug(adapter, pluggedAtWrite)),
                unconfirmedTarget = null,
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
                pending = PendingRequest(policy, now, awaitingReplug = fallbackAwaitsReplug(adapter, pluggedAtWrite)),
                unconfirmedTarget = null,
                message = message,
            )
            settleScheduler.schedule(now)
            ApplyResult(true, observation, message.get(context))
        }
    }

    /**
     * Adapter selection with the real enforcement gate applied — the only form that may decide
     * whether a policy write is allowed. Reads the durable evidence and the verification opt-in, so
     * it is confined to the suspend apply/refresh paths.
     */
    private suspend fun selectGated(device: DeviceInfo = DeviceInfo.current(context)): AdapterSelection =
        registry.select(
            device = device,
            evidenceState = evidenceStore.currentState(),
            qualification = qualificationStore.currentState(),
            verificationStarted = preferences.verificationStartedForNow() == buildIdentity.current(),
        )

    /**
     * Adapter selection for [restorePersistent]: the matched adapter with its OWN probe result, i.e.
     * every capability gate the adapter enforces (system user, provider/key presence, ROM version)
     * but not the enforcement evidence tier. Not usable for a fresh user write — that is exactly what
     * the tier decides.
     */
    private fun selectForRestore(device: DeviceInfo = DeviceInfo.current(context)): AdapterSelection {
        val adapter = registry.select(device, EnforcementEvidenceState.Loading, QualificationEvidenceState.Loading).adapter
            ?: return AdapterSelection(
                adapter = null,
                support = AdapterSupport(
                    matched = false,
                    controlEnabled = false,
                    detail = R.string.adapter_detail_none,
                    contributionWanted = true,
                ),
            )
        return AdapterSelection(adapter = adapter, support = adapter.probe(device))
    }

    /** The gated selection, for callers that must observe the real control decision (the support report). */
    suspend fun currentSelection(): AdapterSelection = selectGated()

    /** Record the user's explicit opt-in to charging control on this exact unconfirmed build. */
    suspend fun startEnforcementVerification() {
        preferences.startVerification(buildIdentity.current())
        refresh()
    }

    private suspend fun refreshLocked(message: CaString?): ChargingState {
        val selection: AdapterSelection = selectGated()
        val access = accessResolver.snapshot()
        val adapter = selection.adapter
        // ONE sticky observation for everything hardware-derived this refresh — plug state, the
        // hardware decode, and the confirmation expectation must join on the same readout
        // (BatteryReader's doc: never pair plug state across two sticky reads). Behavior-identical
        // to the previous per-call adapter.readHardware(context): a missing extra decodes as
        // INVALID(0) and an unreadable broadcast as unplugged, both yielding the same results.
        val battery = batteryReader.read()
        val hardware = adapter?.decodeHardware(battery.chargingStatus ?: 0, battery.onCharger)
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
                    else -> hardware
                        ?: preferences.lastRequestedNow()?.let(ChargeObservation::LastRequested)
                        ?: read
                        ?: ChargeObservation.Unknown(R.string.charging_reason_state_unavailable.toCaString())
                }
            }
        }
        // Confirmation must come from the hardware, not the settings-level `observation` above: with Shizuku
        // or WSS the settings readback is `Verified` while the HAL is still converging, so pending would
        // otherwise never clear until the window expired.
        val latches = adapter?.policyLatchesAtPlug == true
        // Plug-latched adapters: an observed unpowered moment durably resolves an unresolved latched
        // request — the plug session that sampled the old value is over, so the next one samples the
        // new value. Persisted as a watermark so a later *plugged* refresh still knows it happened.
        if (latches && battery.plugged == 0 &&
            preferences.lastRequestedPluggedNow() == true &&
            preferences.unpluggedSeenAtNow() <= preferences.lastRequestedAtNow()
        ) {
            preferences.recordUnpluggedSeen(System.currentTimeMillis())
        }
        val reqPolicy = preferences.lastRequestedNow()
        val reqAt = preferences.lastRequestedAtNow()
        val now = System.currentTimeMillis()
        val pending = computeRefreshPending(
            reqPolicy = reqPolicy,
            reqAt = reqAt,
            now = now,
            observation = observation,
            hardware = hardware,
            verification = adapter?.verification ?: VerificationStrategy.ASYNC_HARDWARE,
            policyLatchesAtPlug = latches,
            reqPlugged = if (latches) preferences.lastRequestedPluggedNow() else null,
            unpluggedSeenAt = if (latches) preferences.unpluggedSeenAtNow() else 0L,
            battery = battery,
            limitPercent = adapter?.latchedLimitPercent() ?: NO_LATCHED_LIMIT,
        )
        val rawUnconfirmed = computeUnconfirmedTarget(
            reqPolicy = reqPolicy,
            reqAt = reqAt,
            now = now,
            observation = observation,
            hardware = hardware,
            confirmationExpected = reqPolicy != null &&
                adapter?.confirmationExpected(reqPolicy, battery.chargingStatus, battery.onCharger) == true,
        )
        val debounce = debounceUnconfirmed(rawUnconfirmed, now, unconfirmedCandidate, unconfirmedSince)
        unconfirmedCandidate = debounce.candidate
        unconfirmedSince = debounce.sinceMillis
        val unconfirmedTarget = debounce.surfaced
        val built = ChargingState(
            device = DeviceInfo.current(context),
            adapterName = adapter?.displayName ?: R.string.adapter_name_unsupported.toCaString(),
            adapterId = adapter?.id,
            // Narrowed by the support decision where a tier licenses only some of them (a guided-run
            // pass covers the two policies it exercised, not the adapter's whole set).
            supportedPolicies = selection.support.licensedPolicies
                ?: adapter?.supportedPolicies.orEmpty(),
            defaultProtectivePolicy = adapter?.defaultProtectivePolicy,
            reconnectSupported = adapter?.reconnectGestureSupported == true,
            syncVerification = adapter?.verification == VerificationStrategy.SYNC_READBACK,
            writeRequiresShizuku = adapter?.preferShizukuForWrites == true,
            controlEnabled = selection.support.controlEnabled,
            enforcement = selection.support.enforcement,
            contributionWanted = selection.support.contributionWanted,
            guidedCaptureUseful = selection.support.guidedCaptureUseful,
            // controlEnabled implies detail is the adapter's *_ready string (every probe's when-cascade
            // pairs them), so this can never carry a gate-failure reason.
            adapterDetail = if (adapter != null && selection.support.controlEnabled) {
                selection.support.detail.toCaString()
            } else {
                null
            },
            adapterResolved = true,
            access = access,
            observation = observation,
            pending = pending,
            unconfirmedTarget = unconfirmedTarget,
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
 * [shizuku] (may be null when not ready) only as a fallback. A direct read of a world-readable key is
 * authoritative and cannot stall, so an *authoritative* direct read — [ChargeObservation.Verified]
 * or a readable-but-unrecognized OEM value — short-circuits without ever binding the Shizuku user service
 * (both backends read the same settings provider, so Shizuku could not report anything stronger). A
 * genuinely unreadable direct result falls back to Shizuku — the routine case for GrapheneOS's @Protected
 * key, which the provider denies to Amply but not to the shell UID.
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
 *
 * Plug-latched adapters ([policyLatchesAtPlug]) are a third case: their readback proves *configuration*, not
 * effect, and there is no clock to run down — the ROM samples the key at the next plug-session start, whenever
 * that is. Such a request resolves only on evidence (any one suffices):
 *  1. it was written while confidently unplugged ([reqPlugged] == false) — the very next plug samples it;
 *  2. an unpowered moment was observed and persisted since the write ([unpluggedSeenAt] > [reqAt]);
 *  3. the [battery] evidence reports unpowered right now — same resolution, observed live;
 *  4. the hardware reports the exact target (the configured limit is demonstrably enforcing);
 *  5. limit-disproof, the only positive signal for a full-charge target: actively charging (or full)
 *     ABOVE the adapter's cap ([limitPercent]) proves no limit session is enforcing. Below the cap the
 *     evidence is genuinely ambiguous — a latched limit also reads "charging" while still climbing.
 * Otherwise it stays pending with [PendingRequest.awaitingReplug] set, without expiry.
 */
internal fun computeRefreshPending(
    reqPolicy: ChargePolicy?,
    reqAt: Long,
    now: Long,
    observation: ChargeObservation,
    hardware: ChargeObservation?,
    verification: VerificationStrategy,
    policyLatchesAtPlug: Boolean = false,
    reqPlugged: Boolean? = null,
    unpluggedSeenAt: Long = 0L,
    battery: BatteryReadout? = null,
    limitPercent: Int = NO_LATCHED_LIMIT,
): PendingRequest? {
    if (reqPolicy == null || reqAt <= 0L) return null
    if (observation is ChargeObservation.Unsupported || observation is ChargeObservation.NeedsSetup) return null
    if (policyLatchesAtPlug) {
        // A configured readback that no longer matches the request is a native/competing change that
        // has already taken effect (the native toggle applies live on these ROMs) — the request is
        // obsolete and must not keep demanding a replug. Same for a readable-but-unrecognized value,
        // which a session start refuses on. A MATCHING readback proves only configuration and never
        // resolves — that is the whole point of this arm.
        if (observation is ChargeObservation.Verified && observation.policy != reqPolicy) return null
        if (observation is ChargeObservation.Unknown && observation.unrecognizedValue) return null
        // No clock guard: a backwards wall-clock jump only disables the watermark comparison (rule 2)
        // until a fresh unplug is observed — clearing here would claim "applied" without evidence,
        // which is the one error this state exists to prevent. Live evidence (rules 3–5) is unaffected.
        val resolved = reqPlugged == false ||
            unpluggedSeenAt > reqAt ||
            battery?.plugged == 0 ||
            hardwareConfirms(hardware, reqPolicy) ||
            (reqPolicy.allowsFullCharge &&
                battery != null && battery.onCharger &&
                (battery.levelPercent ?: 0) > limitPercent &&
                (battery.status == BatteryManager.BATTERY_STATUS_CHARGING ||
                    battery.status == BatteryManager.BATTERY_STATUS_FULL))
        return if (resolved) null else PendingRequest(reqPolicy, reqAt, awaitingReplug = true)
    }
    if (now - reqAt !in 0 until SETTLING_WINDOW_MILLIS) return null
    val confirmed = when (verification) {
        VerificationStrategy.SYNC_READBACK ->
            observation is ChargeObservation.Verified ||
                (observation is ChargeObservation.Unknown && observation.unrecognizedValue)
        VerificationStrategy.ASYNC_HARDWARE -> hardwareConfirms(hardware, reqPolicy)
    }
    if (confirmed) return null
    return PendingRequest(reqPolicy, reqAt)
}

/**
 * Standing contradiction detector behind [ChargingState.unconfirmedTarget]: the last requested policy
 * was EXPECTED to be hardware-confirmed (see ChargingAdapter.confirmationExpected — live channel,
 * reliably-reported policy class, nothing masking) and still is not, well past the settling window.
 *
 * No pending interplay is needed: the threshold exceeds the settling window, so a windowed pending has
 * always expired by the time this can fire, and plug-latched pendings belong to adapters whose
 * expectation is false by default. The threshold's slack (2× the window vs the measured ~11–12s Pixel
 * HAL transition) keeps a merely-slow transition from flickering a warning; `now < reqAt` (backwards
 * clock) falls under the same guard.
 */
internal fun computeUnconfirmedTarget(
    reqPolicy: ChargePolicy?,
    reqAt: Long,
    now: Long,
    observation: ChargeObservation,
    hardware: ChargeObservation?,
    confirmationExpected: Boolean,
): ChargePolicy? {
    if (reqPolicy == null || reqAt <= 0L) return null
    if (observation is ChargeObservation.Unsupported || observation is ChargeObservation.NeedsSetup) return null
    // An authoritative readback of a DIFFERENT configured policy means a competing/native change
    // already replaced the request — warning that the obsolete request "may not be applying" would
    // contradict the very policy the card above verifies. Same for a readable-but-unrecognized
    // value (the state a session start refuses on). A readback verifying the REQUESTED policy while
    // the hardware disagrees is exactly the contradiction this detector exists for.
    if (observation is ChargeObservation.Verified && observation.policy != reqPolicy) return null
    if (observation is ChargeObservation.Unknown && observation.unrecognizedValue) return null
    if (now - reqAt < UNCONFIRMED_THRESHOLD_MILLIS) return null
    if (!confirmationExpected) return null
    if (hardwareConfirms(hardware, reqPolicy)) return null
    return reqPolicy
}

/**
 * Stability debounce over the raw detector output: the contradiction must hold across refreshes for
 * [UNCONFIRMED_STABILITY_MILLIS] before it surfaces. Damps the plug-in transient — a device plugged
 * in with an OLD fixed-limit request legitimately reads state 1 for the ~11–12s HAL transition, and
 * the age threshold alone (measured from the write, not the plug) would warn instantly. Pure:
 * callers thread the previous (candidate, sinceMillis) pair through.
 */
internal fun debounceUnconfirmed(
    candidate: ChargePolicy?,
    now: Long,
    prevCandidate: ChargePolicy?,
    prevSince: Long,
): UnconfirmedDebounce {
    if (candidate == null) return UnconfirmedDebounce(null, 0L, surfaced = null)
    // A changed candidate — or a backwards clock, which voids the stability evidence — restarts.
    val since = if (candidate == prevCandidate && now >= prevSince) prevSince else now
    val surfaced = candidate.takeIf { now - since >= UNCONFIRMED_STABILITY_MILLIS }
    return UnconfirmedDebounce(candidate, since, surfaced)
}

internal data class UnconfirmedDebounce(
    val candidate: ChargePolicy?,
    val sinceMillis: Long,
    val surfaced: ChargePolicy?,
)

internal const val UNCONFIRMED_THRESHOLD_MILLIS = 2 * SETTLING_WINDOW_MILLIS
internal const val UNCONFIRMED_STABILITY_MILLIS = SETTLING_WINDOW_MILLIS

/** Sentinel for "no latched fixed-limit cap": no observable percent can exceed it, disabling limit-disproof. */
internal const val NO_LATCHED_LIMIT = 100

/** The cap a plug-latched adapter's limit-disproof rule compares against, from its protective default. */
internal fun ChargingAdapter.latchedLimitPercent(): Int =
    (defaultProtectivePolicy as? ChargePolicy.FixedLimit)?.percent ?: NO_LATCHED_LIMIT

/** A BATTERY_HARDWARE-verified observation for exactly [target]. */
internal fun hardwareConfirms(hardware: ChargeObservation?, target: ChargePolicy): Boolean =
    hardware is ChargeObservation.Verified &&
        hardware.backend == BackendKind.BATTERY_HARDWARE &&
        hardware.policy == target

/**
 * Whether a degraded post-write fallback [PendingRequest] must carry the replug condition: only on a
 * plug-latched adapter, and not when the write demonstrably happened unplugged (then the next plug
 * samples it and the windowed cue is the honest one).
 */
private fun fallbackAwaitsReplug(adapter: ChargingAdapter, pluggedAtWrite: Boolean?): Boolean =
    adapter.policyLatchesAtPlug && pluggedAtWrite != false
