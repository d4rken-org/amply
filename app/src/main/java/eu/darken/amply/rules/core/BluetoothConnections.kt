package eu.darken.amply.rules.core

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import eu.darken.amply.common.debug.logging.Logging
import eu.darken.amply.common.debug.logging.log
import eu.darken.amply.common.debug.logging.logTag
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

/** A bonded device, as the rule editor offers it. */
data class BondedDevice(
    val address: String,
    val name: String?,
)

/**
 * The Bluetooth facts the rules layer needs, behind an interface so the applier's permission and
 * boot-count invalidation logic is JVM-testable.
 */
interface BluetoothConnectionSource {

    /** BLUETOOTH_CONNECT (API 31+); install-time below that. */
    fun hasPermission(): Boolean

    /**
     * Best-effort per-address reconciliation of what is connected right now. Null means "could not
     * be resolved" — the caller then keeps the receiver-maintained snapshot rather than concluding
     * that nothing is connected.
     */
    suspend fun connectedAddresses(): Set<String>?

    fun bondedDevices(): List<BondedDevice>
}

/**
 * Reconciliation exists because the ACL connect/disconnect broadcasts are the only *live* signal and
 * they are missed whenever Amply's process is not around to receive them (a fresh boot, a force-stop,
 * a broadcast dropped under load). Android exposes no "everything connected" query, so this asks the
 * profile proxies Amply's rules can plausibly ride on and unions the answers.
 *
 * Everything here is bounded and failure-tolerant: an unavailable adapter, a denied permission or a
 * proxy that never connects yields a partial or null answer, never a stall.
 */
@Singleton
class AndroidBluetoothConnectionSource @Inject constructor(
    @ApplicationContext private val context: Context,
) : BluetoothConnectionSource {

    private val manager: BluetoothManager?
        get() = runCatching { context.getSystemService(BluetoothManager::class.java) }.getOrNull()

    override fun hasPermission(): Boolean = Build.VERSION.SDK_INT < 31 ||
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.BLUETOOTH_CONNECT,
        ) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    override fun bondedDevices(): List<BondedDevice> {
        if (!hasPermission()) return emptyList()
        val adapter = manager?.adapter ?: return emptyList()
        return runCatching {
            adapter.bondedDevices.orEmpty().map {
                BondedDevice(address = normalizeBtAddress(it.address), name = it.name)
            }
        }.getOrElse {
            log(TAG, Logging.Priority.WARN) { "Bonded device listing failed: ${it.message}" }
            emptyList()
        }
    }

    @SuppressLint("MissingPermission")
    override suspend fun connectedAddresses(): Set<String>? {
        if (!hasPermission()) return null
        val manager = manager ?: return null
        val adapter = manager.adapter ?: return null
        // Bluetooth off means nothing is connected — a positive answer, not an unknown one.
        if (!runCatching { adapter.isEnabled }.getOrDefault(false)) return emptySet()

        val connected = mutableSetOf<String>()
        // GATT is answerable straight off the manager, without a proxy round-trip.
        runCatching { manager.getConnectedDevices(BluetoothProfile.GATT) }
            .getOrNull()
            ?.forEach { connected += normalizeBtAddress(it.address) }

        PROXY_PROFILES.forEach { profile ->
            withTimeoutOrNull(PROXY_BUDGET_MILLIS) { proxyConnected(adapter, profile) }
                ?.let { connected += it }
        }
        return connected
    }

    @SuppressLint("MissingPermission")
    private suspend fun proxyConnected(adapter: BluetoothAdapter, profile: Int): Set<String>? =
        suspendCancellableCoroutine { continuation ->
            val listener = object : BluetoothProfile.ServiceListener {
                override fun onServiceConnected(which: Int, proxy: BluetoothProfile) {
                    val addresses = runCatching {
                        proxy.connectedDevices.orEmpty().map { normalizeBtAddress(it.address) }.toSet()
                    }.getOrDefault(emptySet())
                    runCatching { adapter.closeProfileProxy(which, proxy) }
                    if (continuation.isActive) continuation.resume(addresses)
                }

                override fun onServiceDisconnected(which: Int) {
                    if (continuation.isActive) continuation.resume(null)
                }
            }
            val requested = runCatching { adapter.getProfileProxy(context, listener, profile) }
                .getOrDefault(false)
            if (!requested && continuation.isActive) continuation.resume(null)
        }

    companion object {
        private val TAG = logTag("Rules", "Bluetooth")

        /** The profiles a "device is connected" rule realistically rides on. */
        private val PROXY_PROFILES = listOf(BluetoothProfile.A2DP, BluetoothProfile.HEADSET)

        /** Per-profile budget: this runs on a service-start evaluation, never on the hot path. */
        private const val PROXY_BUDGET_MILLIS = 2_000L
    }
}
