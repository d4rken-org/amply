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
import eu.darken.amply.BuildConfig
import eu.darken.amply.R
import eu.darken.amply.charging.core.ChargePolicy
import eu.darken.amply.charging.core.ChargingPreferences
import eu.darken.amply.charging.core.ChargingRepository
import eu.darken.amply.charging.core.adapter.AdapterRegistry
import eu.darken.amply.common.debug.logging.Logging
import eu.darken.amply.common.debug.logging.log
import eu.darken.amply.common.debug.logging.logTag
import eu.darken.amply.main.core.SurfaceUpdater
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
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
    private val quickGesture = QuickFullChargeGesture()
    private var monitorJob: Job? = null
    private var recoveryJob: Job? = null
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
                // Respect a native Settings change instead of restoring over the user's choice.
                manager.cancelWithoutRestore()
                unregisterSettingObserver()
                SurfaceUpdater.update(this@ChargeSessionService)
                continueGestureOrStop()
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
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        log(TAG) { "Start command: action=${intent?.action ?: "<restart>"}" }
        scope.launch { commandMutex.withLock { handleCommand(intent?.action) } }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        log(TAG) { "Destroying charge-session service" }
        monitorReady = false
        monitorJob?.cancel()
        runCatching { unregisterReceiver(batteryReceiver) }
        unregisterSettingObserver()
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
                SurfaceUpdater.update(this)
                return
            }
        }
        quickGesture.reset()
        registerSettingObserver()
        startMonitoringLoop()
        monitorReady = true
        evaluateBattery()
        SurfaceUpdater.update(this)
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
        SurfaceUpdater.update(this)
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
            val shortTimeouts = BuildConfig.DEBUG && fullChargeStore.isTestShortTimeouts()
            val decision = SessionDecisionEngine.decide(
                session = session,
                nowMillis = System.currentTimeMillis(),
                plugged = plugged,
                full = full,
                armTimeoutMillis = if (shortTimeouts) DEBUG_ARM_TIMEOUT_MILLIS
                else SessionDecisionEngine.ARM_TIMEOUT_MILLIS,
                safetyTimeoutMillis = if (shortTimeouts) DEBUG_SAFETY_TIMEOUT_MILLIS
                else SessionDecisionEngine.SAFETY_TIMEOUT_MILLIS,
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
    private suspend fun handleCommand(action: String?) {
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
            ACTION_RECOVER -> startRecovery()
            null -> when {
                recoveryJob?.isActive == true -> Unit
                fullChargeStore.pendingRecoveryTarget() != null -> startRecovery()
                fullChargeStore.currentSession() != null -> beginOrResume()
                else -> continueGestureOrStop()
            }
            else -> continueGestureOrStop()
        }
    }

    private fun startRecovery() {
        if (recoveryJob?.isActive == true) return
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
        val result = manager.restore()
        if (!result.success) {
            log(TAG, Logging.Priority.ERROR) { "Charging-policy restoration failed: ${result.message}" }
            SessionNotifications.showRecovery(this)
        }
        restoring = false
        quickGesture.reset()
        SurfaceUpdater.update(this)
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

    companion object {
        private val TAG = logTag("ChargeSessionService")
        const val ACTION_START = "eu.darken.amply.action.START_FULL_CHARGE"
        const val ACTION_RESTORE = "eu.darken.amply.action.RESTORE_CHARGE_LIMIT"
        const val ACTION_MONITOR = "eu.darken.amply.action.MONITOR_QUICK_FULL_CHARGE"
        const val ACTION_RECOVER = "eu.darken.amply.action.RECOVER_CHARGE_LIMIT"

        // Debug-only shortened timeouts (see FullChargeStore.testShortTimeouts); never used in release.
        private const val DEBUG_ARM_TIMEOUT_MILLIS = 60_000L
        private const val DEBUG_SAFETY_TIMEOUT_MILLIS = 120_000L
    }
}
