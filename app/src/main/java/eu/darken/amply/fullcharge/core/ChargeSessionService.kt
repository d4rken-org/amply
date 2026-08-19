package eu.darken.amply.fullcharge.core

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.database.ContentObserver
import android.os.BatteryManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import dagger.hilt.android.AndroidEntryPoint
import eu.darken.amply.R
import eu.darken.amply.charging.core.ChargePolicy
import eu.darken.amply.charging.core.ChargingPreferences
import eu.darken.amply.charging.core.ChargingRepository
import eu.darken.amply.charging.core.adapter.AdapterRegistry
import eu.darken.amply.charging.core.adapter.ChargingAdapter
import eu.darken.amply.charging.core.enforcement.EnforcementEvidenceState
import eu.darken.amply.charging.core.qualification.QualificationEvidenceState
import eu.darken.amply.charging.core.qualification.QualificationRunStore
import eu.darken.amply.charging.core.qualification.QualificationRunner
import eu.darken.amply.common.datastore.value
import eu.darken.amply.common.debug.logging.Logging
import eu.darken.amply.common.debug.logging.log
import eu.darken.amply.common.debug.logging.logTag
import eu.darken.amply.main.core.SurfaceUpdater
import eu.darken.amply.monitor.core.ChargeMonitorTick
import eu.darken.amply.monitor.core.ChargeMonitorWatcher
import eu.darken.amply.rules.core.PlugKind
import eu.darken.amply.rules.core.RuleApplier
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID
import javax.inject.Inject

@AndroidEntryPoint
class ChargeSessionService : Service() {
    @Inject lateinit var manager: ChargeSessionManager
    @Inject lateinit var fullChargeStore: FullChargeStore
    @Inject lateinit var adapterRegistry: AdapterRegistry
    @Inject lateinit var repository: ChargingRepository
    @Inject lateinit var preferences: ChargingPreferences
    @Inject lateinit var interruptionAssessor: InterruptionAssessor
    @Inject lateinit var processIdentity: ProcessIdentity
    @Inject lateinit var bootCountProvider: BootCountProvider
    @Inject lateinit var ruleApplier: RuleApplier
    @Inject lateinit var qualificationRunStore: QualificationRunStore
    @Inject lateinit var qualificationRunner: QualificationRunner

    // Optional, permission-free battery observers (charge alarm, …), contributed via @IntoSet.
    @Inject lateinit var watchers: Set<@JvmSuppressWildcards ChargeMonitorWatcher>

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    // Commands are a single-consumer FIFO queue: onStartCommand enqueues on the main thread in arrival
    // order, so rapid taps (e.g. "∞ 80%" then "∞ 100%") can never be reordered by the dispatcher and
    // finish on the wrong one.
    // Battery evaluations are serialized the same way: the receiver, the 30s poll, and the gesture
    // expiry nudge all enqueue here and a single consumer drains under the same shared lock. Concurrent
    // evaluations could otherwise observe plug edges out of order and corrupt the gesture state
    // machine (its per-tick preference reads suspend, widening the reorder window). Each entry
    // carries the time it was OBSERVED — queue latency under a busy lock must not distort the
    // gesture's 2-10s reconnect window — and the coordinator stamps the monitoring generation it
    // belongs to, so events queued before a monitor stop/restart can never replay into freshly reset
    // gesture state.
    private val coordinator = DispatchCoordinator<Command, Evaluation>()
    private val quickGesture = QuickFullChargeGesture()
    private var monitorJob: Job? = null
    private var gestureExpiryJob: Job? = null
    private var graceExpiryJob: Job? = null
    // Written under the dispatch lock, but read by the battery receiver/monitor loop outside it.
    @Volatile private var recoveryJob: Job? = null
    private var settingObserverRegistered = false
    // Whether this service instance has already swept the Bluetooth profile proxies (see evaluateRules).
    private var bluetoothReconciled = false
    @Volatile private var restoring = false
    // One-shot interruption assessment for a freshly resumed persisted session: set when
    // beginOrResume picks up an existing session, consumed by the first battery evaluation, and
    // cleared on any session start/clear so a stale assessment can never leak into a later session.
    @Volatile private var pendingSessionAssessment: InterruptionAssessor.PickupAssessment? = null

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            coordinator.submitEvaluationIfOpen(Evaluation(intent, SystemClock.elapsedRealtime()))
        }
    }

    private val settingObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) {
            if (restoring) return
            scope.launch {
                coordinator.withExclusive {
                    // Re-check under the lock: a persistent-policy command can set `restoring` after the
                    // fast-path check above but before we acquire the lock, so its own write must not be
                    // mistaken for a native change and cancelled.
                    if (restoring) return@withExclusive
                    // Only cancel on a REAL native change. Notifications also arrive for the
                    // session's own override write (async dispatch can outrun the observer
                    // registration in beginOrResume) and, on some OEM providers, without any value
                    // change at all — both fatal here, because cancelling ends the session with the
                    // protective policy never restored (observed on HyperOS 3, issue #48). Where
                    // the configuration is synchronously readable, a readback still matching the
                    // override is that noise; without readback (Pixel) cancel as before.
                    if (fullChargeStore.currentSession() != null) {
                        val overridePolicy = repository.currentAdapter()?.sessionOverridePolicy
                            ?: ChargePolicy.Unrestricted
                        // Holding the dispatch lock across the readback is bounded by the same
                        // backend a restore would need anyway (worst case one cold Shizuku bind on
                        // GrapheneOS; every other adapter reads direct).
                        val readback = try {
                            repository.syncReadback()
                        } catch (e: TimeoutCancellationException) {
                            // A Shizuku bind/command timeout is a failed READBACK, not this
                            // observer being cancelled — rethrowing would silently drop the
                            // notification and keep a session alive past a genuine native change.
                            log(TAG, Logging.Priority.WARN) {
                                "Readback for the native-change check timed out"
                            }
                            null
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            // Unverifiable counts as a change: same conservative end state as the
                            // pre-guard blanket cancel.
                            log(TAG, Logging.Priority.WARN) {
                                "Readback for the native-change check failed: ${e.message}"
                            }
                            null
                        }
                        if (!NativeChangeGuard.shouldCancel(readback, overridePolicy)) {
                            log(TAG) {
                                "Ignoring settings notification; readback still matches the " +
                                    "session override: $readback"
                            }
                            return@withExclusive
                        }
                        log(TAG, Logging.Priority.INFO) {
                            "Native settings change during session (readback=$readback); " +
                                "cancelling without restore"
                        }
                    }
                    // Respect a native Settings change instead of restoring over the user's choice.
                    manager.cancelWithoutRestore()
                    unregisterSettingObserver()
                    // continueGestureOrStop() awaits a surface update on every terminal branch, so no path
                    // here leaves the widget/tile un-pushed (some paths push more than once — updateAll is idempotent).
                    continueGestureOrStop()
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        log(TAG) { "Creating charge-session service" }
        // Neutral bootstrap notification: onCreate runs before the command dispatch decides why the
        // service started, so posting the gesture notification here would flash the wrong copy (and
        // its DEFAULT-importance channel) on an alarm-only start. The dispatch replaces it.
        startAsForeground(SessionNotifications.monitoring(this))
        ContextCompat.registerReceiver(
            this,
            batteryReceiver,
            IntentFilter(Intent.ACTION_BATTERY_CHANGED),
            ContextCompat.RECEIVER_EXPORTED,
        )
        // Drain commands and evaluations one at a time, in the order they were enqueued and never
        // concurrently with each other. A failure in one item (e.g. a surface update throwing) must not
        // kill its consumer and strand everything that follows.
        coordinator.launch(
            scope = scope,
            onCommand = { handleCommand(it.action, it.target) },
            onEvaluation = { evaluateBattery(it.intent, it.observedAtElapsed) },
            onError = { label, e -> log(TAG, Logging.Priority.ERROR) { "$label failed: ${e.message}" } },
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        log(TAG) { "Start command: action=${intent?.action ?: "<restart>"}" }
        val target = intent?.getStringExtra(EXTRA_TARGET_POLICY)?.let(ChargePolicy::fromStableId)
        coordinator.submitCommand(Command(intent?.action, target))
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        log(TAG) { "Destroying charge-session service" }
        coordinator.close()
        monitorJob?.cancel()
        runCatching { unregisterReceiver(batteryReceiver) }
        unregisterSettingObserver()
        // Closing the queues lets an in-flight handler finish; only cancelling the scope stops the
        // consumers, so these two must stay adjacent — otherwise a waiter parked on the shared lock
        // could acquire it and resume monitoring on a dying service.
        coordinator.shutdown()
        scope.cancel()
        super.onDestroy()
    }

    private suspend fun beginOrResume() {
        val existing = fullChargeStore.currentSession()
        if (existing == null) {
            log(TAG) { "Starting a one-time full-charge session" }
            // A brand-new session in this process is not an interruption; drop any stale assessment.
            pendingSessionAssessment = null
            // A conditional rule may currently own the policy. Hand its baseline to the session: what
            // is configured right now is the rule's temporary override, so the session must restore
            // the user's real policy, not the override.
            val ruleBaseline = ruleApplier.readActiveBaseline()
            val result = manager.begin(
                pluggedAtStart = currentPlugged(),
                restoreOverride = ruleBaseline,
                // Handed over inside begin(), in the window between the session record being
                // persisted and the override write: from that moment the session owes the restore,
                // and clearing any later would leave both layers claiming the baseline across a
                // write that can fail or die with the process.
                //
                // Contained, because begin() runs this between persisting the session and writing
                // the override: letting a DataStore failure escape would abort the start after the
                // record exists, stranding a session whose override write never ran. Stale rule
                // bookkeeping is the far cheaper failure — the next evaluation clears it against the
                // live session anyway.
                afterPersisted = {
                    try {
                        ruleApplier.clearActiveAfterSessionPersist()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        log(TAG, Logging.Priority.WARN) { "Rule ownership handoff failed: ${e.message}" }
                    }
                },
            )
            if (!result.success) {
                log(TAG, Logging.Priority.WARN) { "Unable to start full-charge session: ${result.message}" }
                if (fullChargeStore.currentSession() != null) SessionNotifications.showRecovery(this)
                continueGestureOrStop()
                SurfaceUpdater.updateNow(this)
                return
            }
        } else {
            // Resuming a persisted session: assess whether this process is picking up work a dead
            // process left behind. Threaded (one-shot) into the first battery evaluation below.
            pendingSessionAssessment = interruptionAssessor.captureSessionPickup(existing)
        }
        quickGesture.reset()
        registerSettingObserver()
        startMonitoringLoop()
        coordinator.open()
        evaluateBattery()
        SurfaceUpdater.updateNow(this)
    }

    private suspend fun continueGestureOrStop() {
        if (fullChargeStore.currentSession() != null) {
            beginOrResume()
            return
        }
        val gestureActive = fullChargeStore.isQuickFullChargeEnabled() && reconnectGestureAvailable()
        if (!gestureActive && !anyWatcherEnabled()) {
            // Nothing wants this service any more EXCEPT an owed restore nobody else will make. A
            // recovery target no live run owns keeps this instance alive by itself instead of relying
            // on a dispatch that has to win a race against this very stop decision: a qualification
            // close-out clears its run record, which is exactly what turns the last watcher off, so
            // its ACTION_RECOVER can arrive after stopSelf and be lost.
            //
            // Gated on there being no recovery in flight, because this is ALSO the recovery job's own
            // tail (see beginRecovery): BootRecoveryFlow deliberately KEEPS the pending target when a
            // re-write fails, so the next service start retries it. Without the gate a persistently
            // failing restore would restart itself here forever, at the flow's own 25s/75s cadence,
            // with the service never stopping. The other two stop sites need no gate for their own
            // reasons: evaluateBattery already returns early while a recovery job is active, and
            // restoreAndContinue's stop after a failed restore is deliberately left alone — a pending
            // target is exactly what is present there, and recovering from it would loop on the same
            // failing write.
            if (recoveryJob?.isActive != true && unownedRecoveryPending()) {
                log(TAG, Logging.Priority.INFO) {
                    "Nothing left to monitor, but a recovery target is still owed; recovering instead of stopping"
                }
                beginRecovery()
                return
            }
            log(TAG) { "Reconnect gesture disabled/unsupported and no watcher enabled; stopping monitor" }
            // The stop branch used to be the one terminal path through continueGestureOrStop that pushed no
            // surface update — a session/native change ending here (e.g. via the setting observer) left the
            // widget and tile stale until some later refresh. Push here, before stopping, so no branch leaves
            // the surfaces un-pushed (nested/recursive paths may legitimately push more than once — updateAll
            // is idempotent). A surface failure must not skip stopMonitoring()'s required cleanup
            // (stopForeground/stopSelf), and cancellation must still propagate.
            try {
                SurfaceUpdater.updateNow(this)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log(TAG, Logging.Priority.WARN) { "Surface update before stop failed: ${e.message}" }
            } finally {
                stopMonitoring()
            }
            return
        }
        unregisterSettingObserver()
        startMonitoringLoop()
        coordinator.open()
        // A watcher-only monitor gets the quiet notification; the gesture path re-posts its own in
        // evaluateBattery. Post here so an alarm-only start replaces the bootstrap notification.
        if (!gestureActive) startAsForeground(watcherNotification())
        evaluateBattery()
        SurfaceUpdater.updateNow(this)
    }

    /** A watcher's isEnabled must never throw the service into stopping; treat failure as false. */
    private suspend fun anyWatcherEnabled(): Boolean = watchers.any { watcher ->
        try {
            watcher.isEnabled()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log(TAG, Logging.Priority.WARN) { "Watcher ${watcher.id} isEnabled failed: ${e.message}" }
            false
        }
    }

    /**
     * Run one conditional-charge-rule evaluation.
     *
     * A first-class step of the evaluation path, NOT watcher work: watcher ticks are optional and
     * bounded by a per-watcher budget, while a rule write changes the charging policy and owes a
     * restore — it must never be cut short. It runs *after* the safety-critical session decisions
     * above (a restore must never queue behind it) and before the optional watchers.
     *
     * Failure is contained the same way a watcher's is: the rules layer must not be able to stop a
     * battery evaluation.
     */
    private suspend fun evaluateRules(
        plugged: Boolean,
        plugKind: PlugKind?,
        sessionActive: Boolean,
        reconcileBluetooth: Boolean = false,
    ) {
        try {
            ruleApplier.evaluate(
                plugged = plugged,
                plugKind = plugKind,
                sessionActive = sessionActive,
                // Always on this instance's first pass, whichever command brought the service up: a
                // process that was not running missed every ACL broadcast in the meantime, and the
                // stored snapshot is only as good as the last one it received. After that the
                // receiver keeps it current and the sweep would just cost Binder round-trips.
                reconcileBluetooth = reconcileBluetooth || !bluetoothReconciled,
            )
            bluetoothReconciled = true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log(TAG, Logging.Priority.ERROR) { "Rule evaluation failed: ${e.message}" }
        }
    }

    /** Current plug state and charger class from the sticky broadcast, for a command-driven pass. */
    private fun currentPlug(): Pair<Boolean, PlugKind?> {
        val raw = runCatching {
            registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
                ?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)
        }.getOrNull() ?: 0
        return (raw != 0) to PlugKind.fromExtraPlugged(raw)
    }

    /**
     * Deliver a battery tick to every optional watcher. Evaluations are already serialized (single
     * evaluation consumer under the [coordinator]'s lock), so no extra lock is needed. Each watcher is bounded
     * by [WATCHER_TICK_BUDGET_MILLIS] and fully isolated: a hung or throwing watcher can neither
     * strand this evaluation nor, since restore already ran before this point, delay policy recovery.
     */
    private suspend fun dispatchWatchers(
        plugged: Boolean,
        percent: Int,
        status: Int,
        sessionOwned: Boolean,
        battery: Intent?,
        observedAtElapsed: Long,
    ) {
        if (watchers.isEmpty()) return
        // Pass the exact evaluated intent through; watchers parse it and read live properties off the
        // evaluation thread. Building the readout here would put Binder calls under the dispatch lock and
        // could delay a queued restore.
        val tick = ChargeMonitorTick(
            plugged = plugged,
            percent = percent,
            batteryStatus = status,
            sessionActive = sessionOwned,
            // Read here so every watcher sees the same answer for this observation. Free to read (a
            // volatile flag on the runner), unlike the store, which must not be touched under the
            // dispatch lock.
            runActive = qualificationRunner.runActiveNow,
            batteryIntent = battery,
            observedElapsedRealtimeMillis = observedAtElapsed,
            wallClockMillis = System.currentTimeMillis(),
        )
        watchers.forEach { watcher ->
            try {
                withTimeoutOrNull(WATCHER_TICK_BUDGET_MILLIS) { watcher.onBatteryTick(tick) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log(TAG, Logging.Priority.WARN) { "Watcher ${watcher.id} tick failed: ${e.message}" }
            }
        }
    }

    private fun startMonitoringLoop() {
        // New monitoring run: evaluations queued for the previous run are stale and must be dropped
        // (the gesture state machine was or will be reset relative to them).
        coordinator.newRun()
        monitorJob?.cancel()
        monitorJob = scope.launch {
            while (true) {
                delay(30_000)
                coordinator.submitEvaluation(Evaluation(null, SystemClock.elapsedRealtime()))
            }
        }
    }

    /**
     * Grace expiry isn't broadcast-driven: while unplugged nothing evaluates between the 30s polls,
     * so without a nudge an expired window could keep the device unprotected for most of a poll
     * period. Mirrors the gesture-expiry nudge: scheduled once when the window opens, generation-
     * bound so a stale nudge can't leak into a later monitoring run.
     */
    private fun scheduleGraceExpiry() {
        if (graceExpiryJob?.isActive == true) return
        val generation = coordinator.currentGeneration
        graceExpiryJob = scope.launch {
            delay(SessionDecisionEngine.REPLUG_GRACE_MILLIS + 500)
            coordinator.submitEvaluationForGeneration(
                Evaluation(null, SystemClock.elapsedRealtime()),
                generation,
            )
        }
    }

    /** Plug state from the sticky battery broadcast; null when it cannot be read right now. */
    private fun currentPlugged(): Boolean? = runCatching {
        registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            ?.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1)
    }.getOrNull()?.let { if (it < 0) null else it != 0 }

    // Callers must hold the dispatch lock — either via the evaluation consumer or a command handler.
    // observedAtElapsed is when the underlying battery state was seen, not when we process it.
    private suspend fun evaluateBattery(
        intent: Intent? = null,
        observedAtElapsed: Long = SystemClock.elapsedRealtime(),
    ) {
        // An in-flight evaluation can outlive the quiesce in startRecovery; never race recovery.
        if (recoveryJob?.isActive == true) return
        val battery = intent ?: registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val pluggedRaw = battery?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0
        val plugged = pluggedRaw != 0
        val plugKind = PlugKind.fromExtraPlugged(pluggedRaw)
        val status = battery?.getIntExtra(
            BatteryManager.EXTRA_STATUS,
            BatteryManager.BATTERY_STATUS_UNKNOWN,
        ) ?: BatteryManager.BATTERY_STATUS_UNKNOWN
        val level = battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = battery?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val percent = if (level >= 0 && scale > 0) level * 100 / scale else -1
        val chargingStatus = battery?.getIntExtra(BatteryManager.EXTRA_CHARGING_STATUS, 0) ?: 0

        val session = fullChargeStore.currentSession()

        if (session != null) {
            // One-shot: only the first evaluation after a pickup carries an interruption assessment.
            val assessment = pendingSessionAssessment
            pendingSessionAssessment = null
            val full = status == BatteryManager.BATTERY_STATUS_FULL || percent >= 100
            val decision = SessionDecisionEngine.decide(
                session = session,
                nowMillis = System.currentTimeMillis(),
                plugged = plugged,
                full = full,
                replugGraceMillis = if (policyLatchesAtPlug) {
                    SessionDecisionEngine.REPLUG_GRACE_MILLIS
                } else {
                    0L
                },
            )
            when (decision) {
                SessionDecision.MARK_CONNECTED -> {
                    // markConnectedAndAdopt folds CONNECTED + owner adoption into one edit when this
                    // is an assessed pickup; otherwise the plain markConnected path applies.
                    if (assessment != null) {
                        interruptionAssessor.onSessionDecision(assessment, decision)
                    } else {
                        manager.markConnected()
                    }
                    startAsForeground(
                        SessionNotifications.session(
                            this,
                            connected = true,
                            // Started while already plugged on a plug-latched adapter: this plug
                            // session still runs the old policy — instruct the replug.
                            awaitingReplug = session.overrideAwaitingReplug,
                        ),
                    )
                }
                SessionDecision.MARK_DISCONNECTED -> {
                    if (assessment != null) interruptionAssessor.onSessionDecision(assessment, decision)
                    // Watermark FIRST: the plug session that ran the old policy just ended, so the
                    // override's pending-until-replug resolves now. If the process dies between
                    // these two writes, a re-delivered MARK_DISCONNECTED re-runs both; the reverse
                    // order could lose the unplug evidence to a replug-side clear.
                    preferences.recordUnpluggedSeen()
                    manager.markDisconnected(System.currentTimeMillis())
                    startAsForeground(
                        SessionNotifications.session(this, connected = true, graceWindow = true),
                    )
                    scheduleGraceExpiry()
                }
                SessionDecision.MARK_REPLUGGED -> {
                    if (assessment != null) interruptionAssessor.onSessionDecision(assessment, decision)
                    manager.markReplugged()
                    graceExpiryJob?.cancel()
                    startAsForeground(SessionNotifications.session(this, connected = true))
                }
                // Restore is safety-critical and holds the dispatch lock: run it BEFORE any optional
                // watcher work so a slow/hung watcher can never delay restoring the protective
                // policy. The suppression latch was already set on the session's earlier
                // (non-restore) ticks below; the post-restore re-evaluation sees it as fired.
                SessionDecision.RESTORE_FULL,
                SessionDecision.RESTORE_DISCONNECTED,
                SessionDecision.RESTORE_ARM_TIMEOUT,
                SessionDecision.RESTORE_SAFETY_TIMEOUT -> {
                    restoreAndContinue(assessment)
                    return
                }
                SessionDecision.CONTINUE -> {
                    if (assessment != null) interruptionAssessor.onSessionDecision(assessment, decision)
                    // A restarted process resuming mid-grace has no expiry nudge yet; without one an
                    // expired window would wait for the next 30s poll before restoring.
                    if (session.disconnectedAtMillis != null && !plugged) scheduleGraceExpiry()
                    startAsForeground(
                        SessionNotifications.session(
                            this,
                            connected = plugged || session.connectedSeen,
                            awaitingReplug = session.overrideAwaitingReplug && plugged,
                            graceWindow = session.disconnectedAtMillis != null && !plugged,
                        ),
                    )
                }
            }
            // A live session outranks the rules layer; this pass only reconciles its bookkeeping.
            evaluateRules(plugged, plugKind, sessionActive = true)
            // Non-restore session tick: let watchers observe it (the alarm claims the cycle here).
            dispatchWatchers(plugged, percent, status, sessionOwned = true, battery, observedAtElapsed)
            return
        }

        // Resolved once, here: DeviceInfo.current() resolves activities/providers, reads Samsung
        // settings and queries UserManager, so it must stay behind the session early-return and the
        // cheap enabled check — hoisting it would newly charge active sessions, disabled gestures
        // and watcher-only ticks for it under the dispatch lock. Net cost is unchanged: the availability
        // check already resolved a selection at exactly this point, and the hardware decode below
        // reuses this one.
        val gestureEnabled = fullChargeStore.isQuickFullChargeEnabled()
        val adapter = if (gestureEnabled) capabilityAdapter() else null
        if (!gestureEnabled || adapter?.reconnectGestureSupported != true) {
            evaluateRules(plugged, plugKind, sessionActive = false)
            dispatchWatchers(plugged, percent, status, sessionOwned = false, battery, observedAtElapsed)
            // Gesture inactive: keep running only if a watcher still wants the service, showing the
            // quiet monitoring notification instead of the gesture cue.
            if (anyWatcherEnabled()) {
                // Only stopMonitoring() resets the engine, so a watcher keeping this instance alive
                // across a disable/re-enable cycle would otherwise resume on stale gesture state
                // (a latched basis or an open reconnect window from before the disable).
                quickGesture.reset()
                startAsForeground(watcherNotification())
            } else if (unownedRecoveryPending()) {
                // The same owed-restore rule as continueGestureOrStop's stop branch, for the same
                // reason. No recovery-in-flight gate here: this method returns early while a recovery
                // job is active, so this branch can never be a recovery tail.
                log(TAG, Logging.Priority.INFO) {
                    "Nothing left to monitor, but a recovery target is still owed; recovering instead of stopping"
                }
                beginRecovery()
            } else {
                stopMonitoring()
            }
            return
        }

        val anyLevel = fullChargeStore.isQuickFullChargeAnyLevel()
        // The live charging-policy hardware state, freshly decoded on every tick. It is the
        // authoritative source for the any-level basis: a limit set natively (or by a previous
        // install) leaves Amply's own journal empty, and gating on the journal alone meant the
        // basis never armed on such a device. The hardware signal is only reported while powered,
        // so unplugged ticks yield inconclusive evidence, which the engine treats as "no change".
        val hardware = adapter.decodeHardware(chargingStatus, plugged)
        val lastPersistent = preferences.lastPersistentPolicyNow()
        val policyEvidence = if (anyLevel) {
            GestureBasis.evidence(hardware, lastPersistent)
        } else {
            PolicyEvidence.UNKNOWN
        }
        // Verified readback only, deliberately not GestureBasis.evidence()'s journal fallback: this
        // feeds the *default* limit-hold basis, which must never arm off a limit Amply merely
        // remembers writing. The same value names the limit in the notification below.
        val verifiedLimitPercent = GestureBasis.limitPercent(hardware)
        val output = quickGesture.update(
            QuickFullChargeGesture.Input(
                nowMillis = observedAtElapsed,
                plugged = plugged,
                percent = percent,
                batteryStatus = status,
                chargingStatus = chargingStatus,
                anyLevelEnabled = anyLevel,
                policyEvidence = policyEvidence,
                verifiedLimitPercent = verifiedLimitPercent,
            ),
        )
        val decision = output.decision
        // Every gesture tick, not just the interesting ones: a gesture that never arms leaves no
        // other trace, and diagnosing that from a debug log must not require a DataStore teardown.
        log(TAG, Logging.Priority.VERBOSE) {
            "Reconnect gesture tick: plugged=$plugged percent=$percent batteryStatus=$status " +
                "chargingStatus=$chargingStatus anyLevel=$anyLevel policyEvidence=$policyEvidence " +
                "decision=$decision"
        }
        if (decision != QuickFullChargeDecision.IDLE) {
            log(TAG) {
                "Reconnect gesture: decision=$decision anyLevelBasis=${output.anyLevelBasis} " +
                    "plugged=$plugged percent=$percent status=$status"
            }
        }
        evaluateRules(plugged, plugKind, sessionActive = false)
        // A triggering tick is a deliberate full charge about to begin, so the alarm must treat it
        // as session-owned and NOT fire "unplug now" on the very reconnect that started the charge.
        dispatchWatchers(
            plugged,
            percent,
            status,
            sessionOwned = decision == QuickFullChargeDecision.TRIGGER,
            battery,
            observedAtElapsed,
        )
        if (decision == QuickFullChargeDecision.TRIGGER) {
            gestureExpiryJob?.cancel()
            log(TAG) { "Reconnect gesture triggered one-time full charging" }
            beginOrResume()
        } else {
            startAsForeground(
                SessionNotifications.gesture(
                    this,
                    decision = decision,
                    // The notification's mode actions write persistently, so they must offer
                    // policies this adapter actually supports — the user's picked set where one
                    // exists, this adapter's default pair otherwise.
                    actionPolicies = resolveQuickActionPolicies(
                        fullChargeStore.gestureNotificationPolicies.value(),
                        adapter.supportedPolicies,
                        adapter.defaultProtectivePolicy,
                    ),
                    anyLevel = when (decision) {
                        // The armed copy states the condition the gesture will fire under. The
                        // latched basis is not that condition: at the limit, LIMIT_HOLD wins the
                        // latch even while any-level is on and qualifying, and naming the limit
                        // there would understate a gesture that will in fact fire at any level.
                        // Requiring PROTECTIVE evidence keeps it from overstating in the opposite
                        // direction, when the option is on but nothing protective is detected.
                        QuickFullChargeDecision.ARMED,
                        QuickFullChargeDecision.WAITING_FOR_RECONNECT,
                        -> anyLevel && policyEvidence == PolicyEvidence.PROTECTIVE
                        // Idle copy explains the enabled mode rather than a live basis.
                        else -> anyLevel
                    },
                    // Verified evidence only, never Amply's write journal: naming a number is a
                    // user-facing claim, and a limit removed natively must not keep being claimed.
                    // Unverified state falls back to the generic "charge limit is holding" copy.
                    limitPercent = verifiedLimitPercent,
                ),
            )
            // Expiry isn't broadcast-driven: without a nudge the "reconnect now" copy could linger
            // up to a 30s poll past the window. Scheduled once when the window opens — repeated
            // waiting evaluations must not push the deadline out — and dropped when it resolves.
            if (decision == QuickFullChargeDecision.WAITING_FOR_RECONNECT) {
                if (gestureExpiryJob?.isActive != true) {
                    val generation = coordinator.currentGeneration
                    gestureExpiryJob = scope.launch {
                        delay(QuickFullChargeGesture.MAX_RECONNECT_MILLIS + 500)
                        coordinator.submitEvaluationForGeneration(
                            Evaluation(null, SystemClock.elapsedRealtime()),
                            generation,
                        )
                    }
                }
            } else {
                gestureExpiryJob?.cancel()
            }
        }
    }

    // All command handling is serialized by the dispatch lock; recoveryJob is only touched
    // while holding it, except for the recovery job's own tail, which re-acquires the
    // lock (a cancelled job aborts at that acquisition instead of blocking a canceller).
    private suspend fun handleCommand(action: String?, target: ChargePolicy?) {
        when (action) {
            ACTION_RESTORE -> if (recoveryJob?.isActive != true) restoreAndContinue()
            ACTION_MONITOR -> if (recoveryJob?.isActive != true) continueGestureOrStop()
            // A rule edit or a Bluetooth connection change. Gated on recovery like ACTION_MONITOR: a
            // rule write must never race the boot-recovery convergence loop. No forced Bluetooth
            // sweep here — the once-per-service-instance one in evaluateRules already covers the
            // missed-broadcast case, and sweeping on every ACL event risks a lagging profile proxy
            // writing a just-disconnected address back over the receiver's fresher snapshot.
            ACTION_EVALUATE_RULES -> if (recoveryJob?.isActive != true) {
                val (plugged, plugKind) = currentPlug()
                evaluateRules(
                    plugged = plugged,
                    plugKind = plugKind,
                    sessionActive = fullChargeStore.currentSession() != null,
                )
                continueGestureOrStop()
            }
            // The run's own notification action. Only flags the record; the runner turns that into an
            // abort and the restore on its next tick, so cancellation takes the same path as every
            // other terminal outcome rather than a second one that could skip the restore.
            ACTION_QUALIFICATION_CANCEL -> qualificationRunStore.requestCancel()
            // Run start belongs in this queue for the same reason session start does: both claim the
            // charge policy, and draining single-file is what stops the two from each observing it
            // free and taking it. Started here, the run's own service nudge arrives as a later
            // command rather than re-entering this one.
            ACTION_QUALIFICATION_START -> {
                qualificationRunner.startRequested()
                continueGestureOrStop()
            }
            ACTION_START -> {
                // A qualification run owns the charge policy, and a session started under it would
                // capture the run's temporary policy as the user's own and restore THAT at the end,
                // losing the real setting for good. The whole branch returns: falling through reaches
                // the pending-recovery arm below, which would see the run's own recovery target and
                // start repaying it while the run keeps writing.
                if (qualificationRunStore.currentRun() != null) {
                    log(TAG, Logging.Priority.INFO) {
                        "Ignoring a full-charge start: a qualification run owns the charge policy"
                    }
                    continueGestureOrStop()
                    return
                }
                // A user-initiated session supersedes boot recovery; the new session
                // overwrites the policy anyway. Join so a cancelled re-write cannot
                // land after the session's own policy write.
                recoveryJob?.let { it.cancel(); it.join() }
                beginOrResume()
                // Drop the pending recovery target only once a session durably replaces it. A
                // refused/failed start must keep converging on the previous protective target
                // instead of silently discarding it.
                if (fullChargeStore.currentSession() != null) {
                    fullChargeStore.clearPendingRecoveryTarget()
                } else if (fullChargeStore.pendingRecoveryTarget() != null) {
                    startRecovery()
                }
            }
            ACTION_SET_PERSISTENT_POLICY -> {
                // An explicit persistent-policy choice (widget ∞80% / ∞100%) supersedes both any running
                // one-time session and boot recovery; it is the desired end state. setPersistentPolicy
                // manages the recovery target itself, so do not pre-clear it here.
                recoveryJob?.let { it.cancel(); it.join() }
                target?.let { setPersistentPolicy(it) } ?: continueGestureOrStop()
            }
            ACTION_RECOVER -> startRecovery()
            // ACTION_CHECK (a foreground-launch nudge) shares the sticky-restart reconciliation:
            // unlike ACTION_RECOVER it must never restore over a live, healthy session.
            ACTION_CHECK, null -> when (
                ServiceDispatch.resolveCheck(
                    recoveryActive = recoveryJob?.isActive == true,
                    pendingRecovery = fullChargeStore.pendingRecoveryTarget() != null,
                    sessionExists = fullChargeStore.currentSession() != null,
                )
            ) {
                ServiceDispatch.CheckResolution.ALREADY_RECOVERING -> Unit
                ServiceDispatch.CheckResolution.START_RECOVERY -> startRecovery()
                ServiceDispatch.CheckResolution.RESUME_SESSION -> beginOrResume()
                ServiceDispatch.CheckResolution.MONITOR_OR_STOP -> continueGestureOrStop()
            }
            else -> continueGestureOrStop()
        }
    }

    private suspend fun startRecovery() {
        if (recoveryJob?.isActive == true) return
        // The same ownership rule ACTION_START enforces one screen up, applied to the other writer of
        // the charge policy. A live run registers its own recovery target before its first write, and
        // ACTION_CHECK (the app being reopened) resolves to START_RECOVERY on any pending target —
        // boot recovery would then repay and CLEAR that target while the run keeps commanding
        // experimental policies, and the run's own finalization would find nothing owed and skip the
        // restore, stranding the device on an experimental cap.
        //
        // Refusing is safe rather than a lost restore, in two steps. While the record exists it keeps
        // QualificationWatcher enabled, which is what holds this foreground service up, so the run's
        // own close-out — ordinary finalization, startup repair, or onTick's close-out — still
        // performs the restore. And a close-out whose restore FAILS, the common case at boot, does not
        // end there either: it leaves the recovery target behind and re-dispatches ACTION_RECOVER
        // right after clearing the record, so this guard no longer refuses and BootRecoveryFlow's
        // bounded rewrite loop repays it. Without that hand-off the clear would strand the owed
        // baseline — the record is gone, so nothing keeps this service alive or re-asks for recovery.
        //
        // Scoped to a target this run owns. Anything else pending is somebody else's obligation and
        // must still be recovered normally, run or no run.
        val liveRun = qualificationRunStore.currentRun()
        val recovery = fullChargeStore.currentRecovery()
        if (liveRun != null && recovery != null && recovery.workId == liveRun.runId) {
            log(TAG, Logging.Priority.INFO) {
                "Ignoring recovery of ${recovery.workId}: a qualification run owns the charge policy"
            }
            continueGestureOrStop()
            return
        }
        beginRecovery()
    }

    /**
     * A recovery target that is owed and that no live qualification run owns.
     *
     * A live run's own target is not this: the run still owes it and closes it out on its own terms
     * (that is [startRecovery]'s ownership guard), and repaying it while the run keeps writing
     * experimental policies is exactly what that guard prevents.
     */
    private suspend fun unownedRecoveryPending(): Boolean {
        val recovery = fullChargeStore.currentRecovery() ?: return false
        val liveRun = qualificationRunStore.currentRun()
        return liveRun == null || liveRun.runId != recovery.workId
    }

    /**
     * Launch boot recovery. Split out of [startRecovery] so the stop decisions can start recovery for
     * an unowned pending target without re-entering the ownership guard: they have already established
     * that no live run owns it, and the guard's refusal branch calls back into [continueGestureOrStop],
     * which would be mutual recursion. They keep [startRecovery]'s other precondition — no recovery job
     * in flight — where it matters (see the gate in [continueGestureOrStop]).
     */
    private suspend fun beginRecovery() {
        // Quiesce monitoring before recovery writes: with ACTION_CHECK the service can already be
        // alive in gesture-monitor mode, and the monitor loop / battery receiver run outside the
        // dispatch lock — they could replace the recovering notification or begin a session that
        // races the recovery re-writes.
        coordinator.close()
        monitorJob?.cancel()
        gestureExpiryJob?.cancel()
        graceExpiryJob?.cancel()
        unregisterSettingObserver()
        startAsForeground(SessionNotifications.recovering(this))
        recoveryJob = scope.launch {
            // Assess whether this recovery is picking up work a dead process left behind, BEFORE the
            // flow mutates the pending target.
            val pickup = interruptionAssessor.captureRecoveryPickup()
            // A persisted session already carries the baseline as its restore target, so rule
            // bookkeeping left ACTIVE beside it is stale: recovery is about to write policies, and
            // the rules layer must not come back afterwards claiming to own the result.
            if (fullChargeStore.currentSession() != null) {
                try {
                    ruleApplier.clearActiveAfterSessionPersist()
                } catch (e: CancellationException) {
                    // A cancelled recovery job must actually stop here, not carry on into the flow.
                    throw e
                } catch (e: Exception) {
                    log(TAG, Logging.Priority.WARN) { "Rule ownership clear failed: ${e.message}" }
                }
            }
            val result = BootRecoveryFlow(recoveryHooks).run()
            log(TAG) { "Boot recovery outcome: ${result.outcome}" }
            // A converged recovery restored the protective policy, so clear any lingering alarm.
            if (result.outcome == BootRecoveryFlow.Outcome.CONVERGED) {
                SessionNotifications.cancelRecovery(this@ChargeSessionService)
            }
            try {
                interruptionAssessor.onRecoveryFinished(pickup, result)
            } finally {
                // In finally so an interruption-bookkeeping failure can never strand the recovering
                // foreground state.
                coordinator.withExclusive {
                    // continueGestureOrStop() awaits a surface update on every terminal branch, so no path here
                    // leaves the widget/tile un-pushed (some paths push more than once — updateAll is idempotent).
                    continueGestureOrStop()
                }
            }
        }
    }

    private val recoveryHooks = object : BootRecoveryFlow.Hooks {
        override suspend fun currentSessionTarget() = fullChargeStore.currentSession()?.restorePolicy

        // One record read, not target-then-origin: the two must never be paired across a concurrent
        // write (see FullChargeStore.currentRecovery).
        override suspend fun pendingTarget() = fullChargeStore.currentRecovery()?.let {
            BootRecoveryFlow.PendingRecovery(target = it.target, origin = it.origin)
        }

        override suspend fun setPendingTarget(policy: ChargePolicy) {
            // A session-only recovery seeds the pending target from the session it is recovering, so it
            // continues the SAME owed work — inherit the session's work id; otherwise mint a fresh one.
            val workId = fullChargeStore.currentSession()?.workId ?: UUID.randomUUID().toString()
            fullChargeStore.setPendingRecoveryTarget(
                policy = policy,
                workId = workId,
                provenance = currentWorkProvenance(),
                origin = RecoveryOrigin.SESSION_RESTORE,
            )
        }

        override suspend fun clearPendingTarget() = fullChargeStore.clearPendingRecoveryTarget()
        override suspend fun restoreSession() = manager.restore().success
        override suspend fun dropStaleSession() = manager.cancelWithoutRestore()

        // The origin decides the write path: an owed restore bypasses the enforcement evidence tier,
        // a pending user request does not. See writeRecoveryTarget.
        override suspend fun rewrite(policy: ChargePolicy, origin: RecoveryOrigin) =
            repository.writeRecoveryTarget(policy, origin).success

        override suspend fun intendedTarget() = preferences.lastRequestedNow()

        override fun batterySnapshot(): BatterySnapshot? {
            val battery = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
                ?: return null
            val level = battery.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = battery.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
            return BatterySnapshot(
                plugged = battery.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0,
                percent = if (level >= 0 && scale > 0) level * 100 / scale else -1,
                chargingState = battery.getIntExtra(BatteryManager.EXTRA_CHARGING_STATUS, 0),
            )
        }

        override fun hardwareObservation(snapshot: BatterySnapshot) =
            capabilityAdapter()?.decodeHardware(snapshot.chargingState, snapshot.plugged)

        override suspend fun settingsObservation() = repository.syncReadback()

        override fun notifyFailure(writeFailed: Boolean) = SessionNotifications.showRecovery(
            this@ChargeSessionService,
            if (writeFailed) R.string.recovery_notification_body
            else R.string.recovery_notification_body_convergence,
        )

        override suspend fun tick() = delay(BootRecoveryEngine.TICK_MILLIS)
        override fun elapsedRealtime() = SystemClock.elapsedRealtime()
    }

    private suspend fun restoreAndContinue(
        assessment: InterruptionAssessor.PickupAssessment? = null,
    ) {
        log(TAG) { "Restoring the saved charging policy" }
        restoring = true
        coordinator.close()
        graceExpiryJob?.cancel()
        unregisterSettingObserver()
        // Read the stable work id before the restore clears the session record, so a later upgrade of
        // a still-pending interruption event can be matched to it (the owner token is not stable).
        val restoreWorkId = fullChargeStore.currentSession()?.workId
        val result = try {
            manager.restore()
        } finally {
            // Always release suppression, even if the restore throws, so native changes aren't
            // ignored forever.
            restoring = false
        }
        if (!result.success) {
            log(TAG, Logging.Priority.ERROR) { "Charging-policy restoration failed: ${result.message}" }
            interruptionAssessor.onSessionRestoreFinished(assessment, success = false)
            // The session stays persisted for a retry on the next start (foreground nudge, manual
            // restore, boot). Resuming monitoring here would immediately re-evaluate the same
            // restore condition and loop on the failing write, so stop instead — in a finally, so
            // a throwing surface update can't leave the 30s monitor loop retrying the failed write.
            try {
                SessionNotifications.showRecovery(this)
                SurfaceUpdater.updateNow(this)
            } finally {
                stopMonitoring()
            }
            return
        }
        // Restore succeeded: cancel any lingering "needs attention" notification, upgrade a prior
        // still-pending interruption event for this work, and record the catch-up outcome.
        SessionNotifications.cancelRecovery(this)
        interruptionAssessor.onRestoreSucceeded(restoreWorkId)
        interruptionAssessor.onSessionRestoreFinished(assessment, success = true)
        quickGesture.reset()
        SurfaceUpdater.updateNow(this)
        continueGestureOrStop()
    }

    private suspend fun setPersistentPolicy(policy: ChargePolicy) {
        log(TAG) { "Setting persistent policy: ${policy.stableId}" }
        // Central guard: if no backend can write (e.g. a Shizuku-only adapter with Shizuku not
        // connected), refuse before persisting a recovery target — otherwise a widget/tile tap
        // would strand a pending target that never converges. The app's controls guide setup.
        val state = repository.refresh()
        if (!state.canApply) {
            log(TAG, Logging.Priority.WARN) { "Persistent policy skipped: charging control not writable" }
            // Tell the user why nothing happened. The widget/tile pre-check writability and open the
            // app instead of dispatching, but a notification action cannot pre-check, so without this
            // its tap is a silent no-op (it also covers a surface racing a lost write capability).
            SessionNotifications.showRecovery(this, R.string.recovery_notification_body_unavailable)
            SurfaceUpdater.updateNow(this)
            continueGestureOrStop()
            return
        }
        // Same refusal for a target this adapter cannot apply: a notification action (or a widget
        // button) built for a previous adapter selection outlives the render that produced it, and
        // persisting its recovery target would leave the device converging on something the
        // repository rejects on every attempt.
        if (policy !in state.supportedPolicies) {
            log(TAG, Logging.Priority.WARN) {
                "Persistent policy skipped: ${policy.stableId} is not supported by the current adapter"
            }
            SessionNotifications.showRecovery(this, R.string.recovery_notification_body_unavailable)
            SurfaceUpdater.updateNow(this)
            continueGestureOrStop()
            return
        }
        // Persist the intended end state as the recovery target BEFORE the risky write and before dropping
        // the session, so a failed write or a mid-write process death still converges here on next boot
        // instead of leaving charging in whatever transient state the session had. An explicit persistent
        // choice is new owed work, so it gets a fresh work id — and USER_REQUEST, so that a boot recovery
        // resuming it writes it through the enforcement gate exactly like this call does, rather than
        // through the ungated restore path (the build can be a candidate or refuted by then).
        fullChargeStore.setPendingRecoveryTarget(
            policy = policy,
            workId = UUID.randomUUID().toString(),
            provenance = currentWorkProvenance(),
            origin = RecoveryOrigin.USER_REQUEST,
        )
        // Suspend the rules layer here, in the same persisted-intent step and BEFORE the write: a
        // process death between the write and a post-success suspension would leave the explicit
        // policy configured with every rule still armed to overwrite it on the next tick.
        val (pluggedNow, plugKindNow) = currentPlug()
        try {
            ruleApplier.suspendMatchingCohort(pluggedNow, plugKindNow)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log(TAG, Logging.Priority.WARN) { "Rule suspension failed: ${e.message}" }
        }
        restoring = true
        coordinator.close()
        try {
            // Suppress our own settings write from tripping the native-change observer, and end any one-time
            // session without restoring — the new persistent policy IS the intended end state.
            unregisterSettingObserver()
            manager.cancelWithoutRestore()
            // Force a real settings mutation so a same-value write (e.g. ∞100% while a session already lifted
            // the limit) still re-triggers the charging HAL — see PixelChargingAdapter.reapply().
            val result = repository.reapplyPersistent(policy)
            if (result.success) {
                fullChargeStore.clearPendingRecoveryTarget()
                // An explicit persistent choice is the desired end state; clear any non-successful
                // interruption warning it supersedes and drop the recovery notification.
                SessionNotifications.cancelRecovery(this)
                interruptionAssessor.onExplicitPolicyWrite()
            } else {
                log(TAG, Logging.Priority.ERROR) { "Persistent policy write failed: ${result.message}" }
                SessionNotifications.showRecovery(this)
            }
        } finally {
            // Always release suppression, even on cancellation, so native changes aren't ignored forever.
            restoring = false
        }
        quickGesture.reset()
        SurfaceUpdater.updateNow(this)
        continueGestureOrStop()
    }

    /** Stamp persisted recovery work with this process's identity + the current boot count. */
    private fun currentWorkProvenance() = WorkProvenance(
        token = processIdentity.token,
        pid = processIdentity.pid,
        bootCount = bootCountProvider.current(),
        createdAtMillis = System.currentTimeMillis(),
    )

    /**
     * The matched adapter's *capabilities* (gesture support, hardware decode, observed URIs, plug
     * latching) — never a control decision, so the enforcement gate is deliberately fed the
     * fail-closed [EnforcementEvidenceState.Loading] instead of a durable store read on the service's
     * dispatch path. Every write this service performs goes through the repository, which applies the
     * real gate.
     */
    private fun capabilityAdapter(): ChargingAdapter? =
        adapterRegistry.select(
            evidenceState = EnforcementEvidenceState.Loading,
            qualification = QualificationEvidenceState.Loading,
        ).adapter

    // The gesture's arming preconditions (hardware charging-state 4) are Pixel-specific; on
    // adapters without that signal the monitor would never arm and must not run.
    private fun reconnectGestureAvailable() =
        capabilityAdapter()?.reconnectGestureSupported == true

    // Resolved once per service lifetime: adapter selection is immutable device information, and
    // per-tick selection is deliberately avoided in the session branch (see the note in
    // evaluateBattery about DeviceInfo.current()'s cost under the dispatch lock).
    private val policyLatchesAtPlug by lazy {
        capabilityAdapter()?.policyLatchesAtPlug == true
    }

    private fun stopMonitoring() {
        // Drop any un-consumed pickup assessment so it can never leak into a later session/monitor run.
        pendingSessionAssessment = null
        // A rapid disable/re-enable can reuse this service instance; neither stale gesture state
        // (an old reconnect window) nor already-queued evaluations from this run may survive into
        // the next monitoring run.
        coordinator.closeAndInvalidate()
        monitorJob?.cancel()
        gestureExpiryJob?.cancel()
        graceExpiryJob?.cancel()
        quickGesture.reset()
        unregisterSettingObserver()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    /**
     * The notification for a watcher-only monitor. A qualification run gets its own, naming the phase
     * and offering a way to stop: it runs for up to 90 minutes with the screen off, on the explicit
     * promise that the user can walk away, and the generic monitoring text would make the one thing
     * they are waiting on invisible.
     */
    private suspend fun watcherNotification(): android.app.Notification {
        val run = runCatching { qualificationRunStore.currentRun() }.getOrNull()
        return if (run != null) {
            SessionNotifications.qualification(this, run.phase.messageRes, run.lowCap)
        } else {
            SessionNotifications.monitoring(this)
        }
    }

    private fun startAsForeground(notification: android.app.Notification) {
        ServiceCompat.startForeground(
            this,
            SessionNotifications.SESSION_ID,
            notification,
            if (android.os.Build.VERSION.SDK_INT >= 34) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            } else {
                0
            },
        )
    }

    private fun registerSettingObserver() {
        if (settingObserverRegistered) return
        val uris = capabilityAdapter()?.observedSettingUris.orEmpty()
        if (uris.isEmpty()) return
        uris.forEach { contentResolver.registerContentObserver(it, false, settingObserver) }
        settingObserverRegistered = true
    }

    private fun unregisterSettingObserver() {
        if (!settingObserverRegistered) return
        runCatching { contentResolver.unregisterContentObserver(settingObserver) }
        settingObserverRegistered = false
    }

    private data class Command(val action: String?, val target: ChargePolicy?)

    private data class Evaluation(
        val intent: Intent?,
        val observedAtElapsed: Long,
    )

    companion object {
        private val TAG = logTag("ChargeSessionService")
        // Upper bound on a single optional watcher's tick so a misbehaving one can't hold the
        // command pipeline. Well above the DataStore-read + notify work a real watcher performs.
        private const val WATCHER_TICK_BUDGET_MILLIS = 5_000L
        const val ACTION_START = "eu.darken.amply.action.START_FULL_CHARGE"
        const val ACTION_RESTORE = "eu.darken.amply.action.RESTORE_CHARGE_LIMIT"
        const val ACTION_MONITOR = "eu.darken.amply.action.MONITOR_QUICK_FULL_CHARGE"
        const val ACTION_RECOVER = "eu.darken.amply.action.RECOVER_CHARGE_LIMIT"
        const val ACTION_CHECK = "eu.darken.amply.action.CHECK_CHARGE_STATE"
        const val ACTION_SET_PERSISTENT_POLICY = "eu.darken.amply.action.SET_PERSISTENT_POLICY"
        const val ACTION_EVALUATE_RULES = "eu.darken.amply.action.EVALUATE_CHARGE_RULES"
        const val ACTION_QUALIFICATION_CANCEL = "eu.darken.amply.action.CANCEL_QUALIFICATION_RUN"
        const val ACTION_QUALIFICATION_START = "eu.darken.amply.action.START_QUALIFICATION_RUN"
        const val EXTRA_TARGET_POLICY = "eu.darken.amply.extra.TARGET_POLICY"
    }
}
