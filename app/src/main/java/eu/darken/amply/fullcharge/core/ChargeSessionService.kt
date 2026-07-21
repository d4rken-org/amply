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
import eu.darken.amply.common.debug.logging.Logging
import eu.darken.amply.common.debug.logging.log
import eu.darken.amply.common.debug.logging.logTag
import eu.darken.amply.main.core.SurfaceUpdater
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject

@AndroidEntryPoint
class ChargeSessionService : Service() {
    @Inject lateinit var manager: ChargeSessionManager
    @Inject lateinit var fullChargeStore: FullChargeStore
    @Inject lateinit var adapterRegistry: AdapterRegistry
    @Inject lateinit var repository: ChargingRepository
    @Inject lateinit var preferences: ChargingPreferences

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val commandMutex = Mutex()
    // A single-consumer FIFO queue: onStartCommand enqueues on the main thread in arrival order, so rapid
    // taps (e.g. "∞ 80%" then "∞ 100%") can never be reordered by the dispatcher and finish on the wrong one.
    private val commandChannel = Channel<Command>(Channel.UNLIMITED)
    private val quickGesture = QuickFullChargeGesture()
    private var monitorJob: Job? = null
    // Written under commandMutex, but read by the battery receiver/monitor loop outside it.
    @Volatile private var recoveryJob: Job? = null
    @Volatile private var monitorReady = false
    private var settingObserverRegistered = false
    @Volatile private var restoring = false

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (monitorReady) scope.launch { evaluateBattery(intent) }
        }
    }

    private val settingObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) {
            if (restoring) return
            scope.launch {
                commandMutex.withLock {
                    // Re-check under the lock: a persistent-policy command can set `restoring` after the
                    // fast-path check above but before we acquire the lock, so its own write must not be
                    // mistaken for a native change and cancelled.
                    if (restoring) return@withLock
                    // Respect a native Settings change instead of restoring over the user's choice.
                    manager.cancelWithoutRestore()
                    unregisterSettingObserver()
                    SurfaceUpdater.update(this@ChargeSessionService)
                    continueGestureOrStop()
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        log(TAG) { "Creating charge-session service" }
        startAsForeground(SessionNotifications.gesture(this, armed = false))
        ContextCompat.registerReceiver(
            this,
            batteryReceiver,
            IntentFilter(Intent.ACTION_BATTERY_CHANGED),
            ContextCompat.RECEIVER_EXPORTED,
        )
        // Drain commands one at a time, in the order they were enqueued. A failure in one command (e.g. a
        // surface update throwing) must not kill the consumer and strand every command that follows.
        scope.launch {
            for (command in commandChannel) {
                try {
                    commandMutex.withLock { handleCommand(command.action, command.target) }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    log(TAG, Logging.Priority.ERROR) { "Command ${command.action} failed: ${e.message}" }
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        log(TAG) { "Start command: action=${intent?.action ?: "<restart>"}" }
        val target = intent?.getStringExtra(EXTRA_TARGET_POLICY)?.let(ChargePolicy::fromStableId)
        commandChannel.trySend(Command(intent?.action, target))
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        log(TAG) { "Destroying charge-session service" }
        monitorReady = false
        monitorJob?.cancel()
        runCatching { unregisterReceiver(batteryReceiver) }
        unregisterSettingObserver()
        commandChannel.close()
        scope.cancel()
        super.onDestroy()
    }

    private suspend fun beginOrResume() {
        if (fullChargeStore.currentSession() == null) {
            log(TAG) { "Starting a one-time full-charge session" }
            val result = manager.begin()
            if (!result.success) {
                log(TAG, Logging.Priority.WARN) { "Unable to start full-charge session: ${result.message}" }
                if (fullChargeStore.currentSession() != null) SessionNotifications.showRecovery(this)
                continueGestureOrStop()
                SurfaceUpdater.updateNow(this)
                return
            }
        }
        quickGesture.reset()
        registerSettingObserver()
        startMonitoringLoop()
        monitorReady = true
        evaluateBattery()
        SurfaceUpdater.updateNow(this)
    }

    private suspend fun continueGestureOrStop() {
        if (fullChargeStore.currentSession() != null) {
            beginOrResume()
            return
        }
        if (!fullChargeStore.isQuickFullChargeEnabled()) {
            log(TAG) { "Reconnect gesture disabled; stopping monitor" }
            stopMonitoring()
            return
        }
        unregisterSettingObserver()
        startMonitoringLoop()
        monitorReady = true
        evaluateBattery()
        SurfaceUpdater.updateNow(this)
    }

    private fun startMonitoringLoop() {
        monitorJob?.cancel()
        monitorJob = scope.launch {
            while (true) {
                delay(30_000)
                evaluateBattery()
            }
        }
    }

    private suspend fun evaluateBattery(intent: Intent? = null) {
        // An in-flight evaluation can outlive the quiesce in startRecovery; never race recovery.
        if (recoveryJob?.isActive == true) return
        val battery = intent ?: registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val plugged = (battery?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0
        val status = battery?.getIntExtra(
            BatteryManager.EXTRA_STATUS,
            BatteryManager.BATTERY_STATUS_UNKNOWN,
        ) ?: BatteryManager.BATTERY_STATUS_UNKNOWN
        val level = battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = battery?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val percent = if (level >= 0 && scale > 0) level * 100 / scale else -1

        val session = fullChargeStore.currentSession()
        if (session != null) {
            val full = status == BatteryManager.BATTERY_STATUS_FULL || percent >= 100
            val decision = SessionDecisionEngine.decide(
                session = session,
                nowMillis = System.currentTimeMillis(),
                plugged = plugged,
                full = full,
            )
            when (decision) {
                SessionDecision.MARK_CONNECTED -> {
                    manager.markConnected()
                    startAsForeground(SessionNotifications.session(this, connected = true))
                }
                SessionDecision.RESTORE_FULL,
                SessionDecision.RESTORE_DISCONNECTED,
                SessionDecision.RESTORE_ARM_TIMEOUT,
                SessionDecision.RESTORE_SAFETY_TIMEOUT -> restoreAndContinue()
                SessionDecision.CONTINUE -> startAsForeground(
                    SessionNotifications.session(this, connected = plugged || session.connectedSeen),
                )
            }
            return
        }

        if (!fullChargeStore.isQuickFullChargeEnabled()) {
            stopMonitoring()
            return
        }

        val decision = quickGesture.update(
            nowMillis = System.currentTimeMillis(),
            plugged = plugged,
            percent = percent,
            batteryStatus = status,
            chargingStatus = battery?.getIntExtra(BatteryManager.EXTRA_CHARGING_STATUS, 0) ?: 0,
        )
        if (decision != QuickFullChargeDecision.IDLE) {
            log(TAG) {
                "Reconnect gesture: decision=$decision plugged=$plugged percent=$percent status=$status"
            }
        }
        if (decision == QuickFullChargeDecision.TRIGGER) {
            log(TAG) { "Reconnect gesture triggered one-time full charging" }
            beginOrResume()
        } else {
            startAsForeground(
                SessionNotifications.gesture(
                    this,
                    armed = decision == QuickFullChargeDecision.ARMED ||
                        decision == QuickFullChargeDecision.WAITING_FOR_RECONNECT,
                ),
            )
        }
    }

    // All command handling is serialized by commandMutex; recoveryJob is only touched
    // while holding it, except for the recovery job's own tail, which re-acquires the
    // mutex (a cancelled job aborts at that acquisition instead of blocking a canceller).
    private suspend fun handleCommand(action: String?, target: ChargePolicy?) {
        when (action) {
            ACTION_RESTORE -> if (recoveryJob?.isActive != true) restoreAndContinue()
            ACTION_MONITOR -> if (recoveryJob?.isActive != true) continueGestureOrStop()
            ACTION_START -> {
                // A user-initiated session supersedes boot recovery; the new session
                // overwrites the policy anyway. Join so a cancelled re-write cannot
                // land after the session's own policy write.
                recoveryJob?.let { it.cancel(); it.join() }
                fullChargeStore.clearPendingRecoveryTarget()
                beginOrResume()
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

    private fun startRecovery() {
        if (recoveryJob?.isActive == true) return
        // Quiesce monitoring before recovery writes: with ACTION_CHECK the service can already be
        // alive in gesture-monitor mode, and the monitor loop / battery receiver run outside the
        // command mutex — they could replace the recovering notification or begin a session that
        // races the recovery re-writes.
        monitorReady = false
        monitorJob?.cancel()
        unregisterSettingObserver()
        startAsForeground(SessionNotifications.recovering(this))
        recoveryJob = scope.launch {
            val outcome = BootRecoveryFlow(recoveryHooks).run()
            log(TAG) { "Boot recovery outcome: $outcome" }
            commandMutex.withLock {
                SurfaceUpdater.update(this@ChargeSessionService)
                continueGestureOrStop()
            }
        }
    }

    private val recoveryHooks = object : BootRecoveryFlow.Hooks {
        override suspend fun currentSessionTarget() = fullChargeStore.currentSession()?.restorePolicy
        override suspend fun pendingTarget() = fullChargeStore.pendingRecoveryTarget()
        override suspend fun setPendingTarget(policy: ChargePolicy) =
            fullChargeStore.setPendingRecoveryTarget(policy)

        override suspend fun clearPendingTarget() = fullChargeStore.clearPendingRecoveryTarget()
        override suspend fun restoreSession() = manager.restore().success
        override suspend fun dropStaleSession() = manager.cancelWithoutRestore()
        override suspend fun rewrite(policy: ChargePolicy) =
            repository.reapplyPersistent(policy).success

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
            adapterRegistry.select().adapter?.decodeHardware(snapshot.chargingState, snapshot.plugged)

        override fun notifyFailure(writeFailed: Boolean) = SessionNotifications.showRecovery(
            this@ChargeSessionService,
            if (writeFailed) R.string.recovery_notification_body
            else R.string.recovery_notification_body_convergence,
        )

        override suspend fun tick() = delay(BootRecoveryEngine.TICK_MILLIS)
        override fun elapsedRealtime() = SystemClock.elapsedRealtime()
    }

    private suspend fun restoreAndContinue() {
        log(TAG) { "Restoring the saved charging policy" }
        restoring = true
        monitorReady = false
        unregisterSettingObserver()
        val result = try {
            manager.restore()
        } finally {
            // Always release suppression, even if the restore throws, so native changes aren't
            // ignored forever.
            restoring = false
        }
        if (!result.success) {
            log(TAG, Logging.Priority.ERROR) { "Charging-policy restoration failed: ${result.message}" }
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
        quickGesture.reset()
        SurfaceUpdater.updateNow(this)
        continueGestureOrStop()
    }

    private suspend fun setPersistentPolicy(policy: ChargePolicy) {
        log(TAG) { "Setting persistent policy: ${policy.stableId}" }
        // Persist the intended end state as the recovery target BEFORE the risky write and before dropping
        // the session, so a failed write or a mid-write process death still converges here on next boot
        // instead of leaving charging in whatever transient state the session had.
        fullChargeStore.setPendingRecoveryTarget(policy)
        restoring = true
        monitorReady = false
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

    private fun stopMonitoring() {
        monitorReady = false
        monitorJob?.cancel()
        unregisterSettingObserver()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
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
        val uris = adapterRegistry.select().adapter?.observedSettingUris.orEmpty()
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

    companion object {
        private val TAG = logTag("ChargeSessionService")
        const val ACTION_START = "eu.darken.amply.action.START_FULL_CHARGE"
        const val ACTION_RESTORE = "eu.darken.amply.action.RESTORE_CHARGE_LIMIT"
        const val ACTION_MONITOR = "eu.darken.amply.action.MONITOR_QUICK_FULL_CHARGE"
        const val ACTION_RECOVER = "eu.darken.amply.action.RECOVER_CHARGE_LIMIT"
        const val ACTION_CHECK = "eu.darken.amply.action.CHECK_CHARGE_STATE"
        const val ACTION_SET_PERSISTENT_POLICY = "eu.darken.amply.action.SET_PERSISTENT_POLICY"
        const val EXTRA_TARGET_POLICY = "eu.darken.amply.extra.TARGET_POLICY"
    }
}
