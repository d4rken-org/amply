package eu.darken.amply.charging.core.access.shizuku

import android.content.ComponentName
import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import eu.darken.amply.BuildConfig
import eu.darken.amply.common.debug.logging.Logging
import eu.darken.amply.common.debug.logging.asLog
import eu.darken.amply.common.debug.logging.log
import eu.darken.amply.common.debug.logging.logTag
import eu.darken.porter.sdk.UserServiceArgs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Fronts the Porter SDK's privileged connection, served by either the Porter or the Shizuku manager:
 * permission, and the [IChargingControlService] user service bound on exactly one connection.
 */
@Singleton
class ShizukuController internal constructor(
    private val context: Context,
    private val gateway: PorterGateway,
    private val scope: CoroutineScope,
) {
    @Inject constructor(
        @ApplicationContext context: Context,
        gateway: PorterGateway,
    ) : this(context, gateway, CoroutineScope(SupervisorJob() + Dispatchers.Default))

    private val connectionMutex = Mutex()

    /**
     * Guards [binding]. Kept separate from [connectionMutex] because [service] holds that mutex while it
     * awaits the collector that publishes here.
     */
    private val bindingLock = Any()
    private var binding: Binding? = null

    private class Binding(val link: PrivilegedLink, val token: Any, val job: Job) {
        var service: IChargingControlService? = null
    }

    fun isAvailable(): Boolean = gateway.currentLink()?.isAlive() == true

    suspend fun manager(): PrivilegedManager = gateway.availability()

    /** The manager serving the current connection, or null without one. */
    fun connectedBackend(): ManagerBackend? = gateway.currentLink()?.backend

    suspend fun isGranted(): Boolean = gateway.currentLink()?.let { isGrantedOn(it) } == true

    /**
     * Emits on every connection change (a server delivering its binder, its death, its replacement),
     * including the current connection on subscription, so a foreground watcher can re-probe access without
     * waiting for the next battery broadcast. Deliberately does not follow the permission state: the in-app
     * request already refreshes with its own grant/deny message, which a second refresh would clear, and a
     * grant made in the manager isn't guaranteed to reach this process (the caller's poll covers that case).
     * The collector conflates: a restart/death burst collapses to at most one pending re-check.
     */
    val accessEvents: Flow<Unit> = gateway.links.map { }

    suspend fun requestPermission(): Boolean {
        val link = gateway.currentLink()?.takeIf { it.isAlive() } ?: return false
        if (isGrantedOn(link)) return true
        return try {
            withTimeout(PERMISSION_REQUEST_TIMEOUT_MS) { link.requestPermission() }
        } catch (e: TimeoutCancellationException) {
            log(TAG, Logging.Priority.WARN) { "Permission request timed out" }
            false
        } catch (e: PrivilegedAccessException) {
            log(TAG, Logging.Priority.WARN) { "Permission request failed: $e" }
            false
        }
    }

    suspend fun service(): IChargingControlService = connectionMutex.withLock {
        val link = gateway.currentLink()
        synchronized(bindingLock) { binding?.takeIf { it.link == link }?.service }
            ?.takeIf { it.asBinder().pingBinder() }
            ?.let { return@withLock it }

        val live = checkNotNull(link?.takeIf { isGrantedOn(it) }) {
            "Shizuku is not running or permission is missing"
        }

        val token = Any()
        val connected = CompletableDeferred<IChargingControlService>()
        // Lazy, so the record is installed before the collector can try to publish into it.
        val job = scope.launch(start = CoroutineStart.LAZY) { collectUserService(live, token, connected) }
        val previous = synchronized(bindingLock) { binding.also { binding = Binding(live, token, job) } }
        previous?.job?.cancel()
        job.start()

        try {
            withTimeout(BIND_TIMEOUT_MS) { connected.await() }
        } catch (e: Throwable) {
            // Timeout, caller cancellation or a failed bind: a binder arriving after this is dropped.
            invalidate(token)
            job.cancel()
            throw e
        }
    }

    suspend fun grantWriteSecureSettings(): Boolean =
        service().grantWriteSecureSettings(context.packageName)

    private suspend fun isGrantedOn(link: PrivilegedLink): Boolean {
        if (!link.isAlive()) return false
        return try {
            withTimeoutOrNull(PERMISSION_CHECK_TIMEOUT_MS) { link.checkPermission() } == true
        } catch (e: PrivilegedAccessException) {
            log(TAG, Logging.Priority.WARN) { "Permission check failed: $e" }
            false
        }
    }

    /**
     * Never rethrows a failure: this runs in [scope], where an uncaught exception would still reach the
     * process's handler despite the SupervisorJob.
     */
    private suspend fun collectUserService(
        link: PrivilegedLink,
        token: Any,
        connected: CompletableDeferred<IChargingControlService>,
    ) {
        try {
            link.userService(userServiceArgs()).collect { binder ->
                val service = IChargingControlService.Stub.asInterface(binder)
                check(publish(link, token, service)) { "Shizuku connection changed while binding" }
                connected.complete(service)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            log(TAG, Logging.Priority.WARN) { "User service binding failed: ${e.asLog()}" }
            connected.completeExceptionally(e)
        } finally {
            invalidate(token)
            connected.completeExceptionally(IllegalStateException("Shizuku user service ended before it connected"))
        }
    }

    private fun publish(link: PrivilegedLink, token: Any, service: IChargingControlService): Boolean =
        synchronized(bindingLock) {
            val record = binding
            if (record == null || record.token !== token || link != gateway.currentLink()) {
                false
            } else {
                record.service = service
                true
            }
        }

    private fun invalidate(token: Any) {
        synchronized(bindingLock) {
            if (binding?.token === token) binding = null
        }
    }

    private fun userServiceArgs() = UserServiceArgs(
        componentName = ComponentName(context.packageName, ChargingControlUserService::class.java.name),
        processNameSuffix = "charging",
        version = BuildConfig.VERSION_CODE,
        debuggable = BuildConfig.DEBUG,
        daemon = false,
    )

    private companion object {
        const val PERMISSION_CHECK_TIMEOUT_MS = 5_000L
        const val PERMISSION_REQUEST_TIMEOUT_MS = 60_000L
        const val BIND_TIMEOUT_MS = 15_000L
        val TAG = logTag("Shizuku", "Controller")
    }
}
