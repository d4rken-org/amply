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
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import dagger.hilt.android.AndroidEntryPoint
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
import javax.inject.Inject

@AndroidEntryPoint
class ChargeSessionService : Service() {
    @Inject lateinit var manager: ChargeSessionManager
    @Inject lateinit var fullChargeStore: FullChargeStore
    @Inject lateinit var adapterRegistry: AdapterRegistry

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val quickGesture = QuickFullChargeGesture()
    private var monitorJob: Job? = null
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
        scope.launch {
            when (intent?.action) {
                ACTION_RESTORE -> restoreAndContinue()
                ACTION_MONITOR -> continueGestureOrStop()
                ACTION_START -> beginOrResume()
                null -> {
                    if (fullChargeStore.currentSession() != null) beginOrResume() else continueGestureOrStop()
                }
                else -> continueGestureOrStop()
            }
        }
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
            when (SessionDecisionEngine.decide(session, System.currentTimeMillis(), plugged, full)) {
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
    }
}
