package eu.darken.amply.fullcharge.core

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import eu.darken.amply.charging.core.adapter.AdapterRegistry
import eu.darken.amply.common.debug.logging.Logging
import eu.darken.amply.common.debug.logging.log
import eu.darken.amply.common.debug.logging.logTag
import eu.darken.amply.monitor.core.ChargeMonitorWatcher
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject

@AndroidEntryPoint
class BootReceiver : BroadcastReceiver() {
    @Inject lateinit var sessionStore: FullChargeStore
    @Inject lateinit var adapterRegistry: AdapterRegistry
    @Inject lateinit var watchers: Set<@JvmSuppressWildcards ChargeMonitorWatcher>

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                // Android re-delivers a deferred BOOT_COMPLETED when a force-stopped app next
                // starts; within an already-seen boot that must reconcile (CHECK), not restore.
                val bootCount = ServiceDispatch.currentBootCount(context)
                val trigger = ServiceDispatch.bootTrigger(
                    currentBootCount = bootCount,
                    lastSeenBootCount = sessionStore.lastSeenBootCount(),
                )
                // A single intent: the service sequences recovery before gesture monitoring,
                // so their policy writes cannot interleave. Session/recovery/gesture are computed
                // first and independently; the optional watcher state is consulted ONLY when none of
                // them already mandates a start, so a slow/hung watcher can never delay a restore.
                val sessionExists = sessionStore.currentSession() != null
                val pendingRecovery = sessionStore.pendingRecoveryTarget() != null
                val gestureEnabled = sessionStore.isQuickFullChargeEnabled() &&
                    adapterRegistry.select().adapter?.reconnectGestureSupported == true
                val mandatory = sessionExists || pendingRecovery || gestureEnabled
                val action = ServiceDispatch.startAction(
                    trigger = trigger,
                    sessionExists = sessionExists,
                    pendingRecovery = pendingRecovery,
                    gestureEnabled = gestureEnabled,
                    watcherEnabled = if (mandatory) false else anyWatcherEnabled(),
                )
                log(TAG) { "BOOT_COMPLETED: bootCount=$bootCount trigger=$trigger action=$action" }
                val started = action == null || runCatching {
                    ContextCompat.startForegroundService(
                        context,
                        ServiceDispatch.startIntent(context, action),
                    )
                }.onFailure {
                    log(TAG, Logging.Priority.WARN) { "Boot service start failed: ${it.message}" }
                }.isSuccess
                // Mark the boot handled only after a successful (or unnecessary) dispatch, so a
                // failed start keeps its BOOT semantics for the next delivery or nudge. A failed
                // marker write must never block the recovery dispatch above.
                if (started) {
                    bootCount?.let { runCatching { sessionStore.setLastSeenBootCount(it) } }
                }
            } finally {
                pending.finish()
            }
        }
    }

    /**
     * A throwing/slow opt-in watcher must never block boot recovery, so each query is bounded and
     * failure (or timeout) reads as false. Only reached when no session/recovery/gesture already
     * requires the service.
     */
    private suspend fun anyWatcherEnabled(): Boolean = watchers.any { watcher ->
        runCatching {
            withTimeoutOrNull(WATCHER_QUERY_BUDGET_MILLIS) { watcher.isEnabled() } ?: false
        }.getOrElse {
            log(TAG, Logging.Priority.WARN) { "Watcher ${watcher.id} isEnabled failed at boot: ${it.message}" }
            false
        }
    }

    companion object {
        private val TAG = logTag("BootReceiver")
        private const val WATCHER_QUERY_BUDGET_MILLIS = 2_000L
    }
}
