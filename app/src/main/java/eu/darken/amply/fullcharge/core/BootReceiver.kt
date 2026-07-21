package eu.darken.amply.fullcharge.core

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import eu.darken.amply.charging.core.adapter.AdapterRegistry
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
                // A single intent: the service sequences recovery before gesture monitoring,
                // so their policy writes cannot interleave.
                val action = ServiceDispatch.startAction(
                    trigger = ServiceDispatch.Trigger.BOOT,
                    sessionExists = sessionStore.currentSession() != null,
                    pendingRecovery = sessionStore.pendingRecoveryTarget() != null,
                    gestureEnabled = sessionStore.isQuickFullChargeEnabled() &&
                        adapterRegistry.select().adapter?.reconnectGestureSupported == true,
                )
                if (action != null) {
                    ContextCompat.startForegroundService(
                        context,
                        ServiceDispatch.startIntent(context, action),
                    )
                }
            } finally {
                pending.finish()
            }
        }
    }
}
