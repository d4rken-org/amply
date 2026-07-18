package eu.darken.amply.fullcharge.core

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class BootReceiver : BroadcastReceiver() {
    @Inject lateinit var sessionStore: FullChargeStore
    @Inject lateinit var manager: ChargeSessionManager

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                if (sessionStore.currentSession() != null) {
                    val result = manager.restore()
                    if (!result.success) SessionNotifications.showRecovery(context)
                }
                if (sessionStore.isQuickFullChargeEnabled()) {
                    ContextCompat.startForegroundService(
                        context,
                        Intent(context, ChargeSessionService::class.java)
                            .setAction(ChargeSessionService.ACTION_MONITOR),
                    )
                }
            } finally {
                pending.finish()
            }
        }
    }
}
