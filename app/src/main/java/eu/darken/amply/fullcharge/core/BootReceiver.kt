package eu.darken.amply.fullcharge.core

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import eu.darken.amply.charging.core.adapter.AdapterRegistry
import eu.darken.amply.common.debug.logging.Logging
import eu.darken.amply.common.debug.logging.log
import eu.darken.amply.common.debug.logging.logTag
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class BootReceiver : BroadcastReceiver() {
    @Inject lateinit var sessionStore: FullChargeStore
    @Inject lateinit var adapterRegistry: AdapterRegistry

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
                // so their policy writes cannot interleave.
                val action = ServiceDispatch.startAction(
                    trigger = trigger,
                    sessionExists = sessionStore.currentSession() != null,
                    pendingRecovery = sessionStore.pendingRecoveryTarget() != null,
                    gestureEnabled = sessionStore.isQuickFullChargeEnabled() &&
                        adapterRegistry.select().adapter?.reconnectGestureSupported == true,
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

    companion object {
        private val TAG = logTag("BootReceiver")
    }
}
