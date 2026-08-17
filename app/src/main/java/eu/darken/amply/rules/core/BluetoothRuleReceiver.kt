package eu.darken.amply.rules.core

import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import dagger.hilt.android.AndroidEntryPoint
import eu.darken.amply.common.debug.logging.Logging
import eu.darken.amply.common.debug.logging.log
import eu.darken.amply.common.debug.logging.logTag
import eu.darken.amply.fullcharge.core.ChargeSessionService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * The only live signal for "is this device connected". Registered in the manifest so a connection
 * that happens while Amply is not running still lands — the snapshot it writes is what the next
 * evaluation reads, even if the service start below is refused.
 *
 * Deliberately limited to the two ACL actions: bond-state and adapter-state broadcasts say nothing
 * about connectivity, and a wider filter would only cost wake-ups.
 */
@AndroidEntryPoint
class BluetoothRuleReceiver : BroadcastReceiver() {

    @Inject lateinit var applier: RuleApplier

    override fun onReceive(context: Context, intent: Intent) {
        val update = parse(intent) ?: return
        val appContext = context.applicationContext
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            try {
                handleUpdate(appContext, update)
            } catch (e: Exception) {
                log(TAG, Logging.Priority.ERROR) { "Bluetooth connection update failed: ${e.message}" }
            } finally {
                pending.finish()
            }
        }
    }

    internal suspend fun handleUpdate(context: Context, update: ConnectionUpdate) {
        log(TAG) { "Bluetooth ${update.address} connected=${update.connected}" }
        // Persisted first, unconditionally: the snapshot is the durable part, the nudge is best effort.
        val relevant = applier.onBluetoothConnectionChanged(update.address, update.connected)
        // Only wake the service when a rule actually rides on Bluetooth.
        if (relevant) nudgeService(context)
    }

    /**
     * A plain [Context.startService], not a foreground start: this fires from the background, where a
     * foreground start is refused outright. The snapshot is already persisted, so a refused start
     * costs latency only — the next service start reconciles from it.
     */
    private fun nudgeService(context: Context) {
        try {
            context.startService(
                Intent(context, ChargeSessionService::class.java)
                    .setAction(ChargeSessionService.ACTION_EVALUATE_RULES),
            )
        } catch (e: IllegalStateException) {
            log(TAG) { "Rule evaluation nudge refused from the background: ${e.message}" }
        } catch (e: Exception) {
            log(TAG, Logging.Priority.WARN) { "Rule evaluation nudge failed: ${e.message}" }
        }
    }

    internal data class ConnectionUpdate(val address: String, val connected: Boolean)

    internal companion object {
        private val TAG = logTag("Rules", "BtReceiver")

        internal fun parse(intent: Intent): ConnectionUpdate? {
            val connected = when (intent.action) {
                BluetoothDevice.ACTION_ACL_CONNECTED -> true
                BluetoothDevice.ACTION_ACL_DISCONNECTED -> false
                else -> return null
            }
            val address = intent.deviceAddress() ?: return null
            return ConnectionUpdate(address = address, connected = connected)
        }

        @Suppress("DEPRECATION")
        private fun Intent.deviceAddress(): String? = runCatching {
            if (Build.VERSION.SDK_INT >= 33) {
                getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
            } else {
                getParcelableExtra(BluetoothDevice.EXTRA_DEVICE) as BluetoothDevice?
            }?.address
        }.getOrNull()
    }
}
