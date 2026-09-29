package eu.darken.amply.charging.core.access.shizuku

import android.content.Context
import android.os.IBinder
import dagger.hilt.android.qualifiers.ApplicationContext
import eu.darken.amply.common.debug.logging.Logging
import eu.darken.amply.common.debug.logging.log
import eu.darken.amply.common.debug.logging.logTag
import eu.darken.porter.sdk.Porter
import eu.darken.porter.sdk.PorterAvailability
import eu.darken.porter.sdk.PorterBackend
import eu.darken.porter.sdk.PorterConnection
import eu.darken.porter.sdk.PorterException
import eu.darken.porter.sdk.UserServiceArgs
import eu.darken.porter.sdk.isGranted
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The only way into the Porter SDK, which serves both the Porter and the Shizuku manager, so everything
 * above it can be tested without a server.
 */
interface PorterGateway {

    /** The link to the server that delivered its binder, null before one did and after it died. */
    fun currentLink(): PrivilegedLink?

    /** [currentLink] on subscription, then every replacement. A restarting server usually publishes null first. */
    val links: Flow<PrivilegedLink?>

    /** Never throws: a lookup that times out or fails reads [PrivilegedManager.Unreachable]. */
    suspend fun availability(): PrivilegedManager
}

/**
 * One connection to a server. A connection pins its backend, and two links are equal only when they wrap
 * the same connection, so a replaced server is never mistaken for the one a binding was made on.
 * Failed calls throw [PrivilegedAccessException].
 */
interface PrivilegedLink {
    val backend: ManagerBackend

    /** A local ping of the server binder. */
    fun isAlive(): Boolean

    suspend fun checkPermission(): Boolean

    /** Suspends until the user answers the manager's prompt; throws if the connection is lost meanwhile. */
    suspend fun requestPermission(): Boolean

    /** Cold: collecting binds and emits the service binder; completes when the service or the connection ends. */
    fun userService(args: UserServiceArgs): Flow<IBinder>
}

/** A call the server refused, failed, or lost its connection during. */
class PrivilegedAccessException(cause: Throwable) : RuntimeException(cause.message, cause)

@Singleton
class DefaultPorterGateway @Inject constructor(
    @ApplicationContext private val context: Context,
) : PorterGateway {

    override fun currentLink(): PrivilegedLink? = Porter.connection.value?.let(::PorterLink)

    override val links: Flow<PrivilegedLink?> = Porter.connection.map { connection -> connection?.let(::PorterLink) }

    override suspend fun availability(): PrivilegedManager {
        val manager = try {
            withTimeoutOrNull(AVAILABILITY_TIMEOUT_MS) { Porter.availability(context).toManager() }
                ?: PrivilegedManager.Unreachable.also { log(TAG, Logging.Priority.WARN) { "Manager lookup timed out" } }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log(TAG, Logging.Priority.WARN) { "Manager lookup failed: $e" }
            PrivilegedManager.Unreachable
        }
        log(TAG, Logging.Priority.VERBOSE) { "availability=$manager" }
        return manager
    }

    private companion object {
        const val AVAILABILITY_TIMEOUT_MS = 5_000L
        val TAG = logTag("Shizuku", "Gateway")
    }
}

private data class PorterLink(private val connection: PorterConnection) : PrivilegedLink {
    override val backend: ManagerBackend get() = connection.backend.toManagerBackend()

    override fun isAlive(): Boolean = connection.binder.pingBinder()

    override suspend fun checkPermission(): Boolean = sdkCall { connection.checkPermission().isGranted }

    override suspend fun requestPermission(): Boolean = sdkCall { connection.requestPermission().isGranted }

    override fun userService(args: UserServiceArgs): Flow<IBinder> = connection.userService(args).catch { e ->
        throw if (e is PorterException) PrivilegedAccessException(e) else e
    }
}

private inline fun <T> sdkCall(block: () -> T): T = try {
    block()
} catch (e: PorterException) {
    throw PrivilegedAccessException(e)
}

private fun PorterBackend.toManagerBackend(): ManagerBackend = when (this) {
    PorterBackend.PORTER -> ManagerBackend.PORTER
    PorterBackend.SHIZUKU -> ManagerBackend.SHIZUKU
}

internal fun PorterAvailability.toManager(): PrivilegedManager = when (this) {
    PorterAvailability.NotInstalled -> PrivilegedManager.NotInstalled
    is PorterAvailability.InstalledNotConnected -> PrivilegedManager.Installed(
        backend = backend.toManagerBackend(),
        packageName = packageName,
        connected = false,
    )
    // A package the SDK does not know as the manager, e.g. a renamed fork: still the manager to us.
    is PorterAvailability.InstalledUnrecognized -> PrivilegedManager.Installed(
        backend = backend.toManagerBackend(),
        packageName = packageName,
        connected = false,
    )
    is PorterAvailability.Connected -> PrivilegedManager.Installed(
        backend = backend.toManagerBackend(),
        packageName = packageName,
        connected = true,
    )
    is PorterAvailability.Incompatible -> PrivilegedManager.Incompatible(
        backend = incompatibility.backend.toManagerBackend(),
        packageName = packageName,
        serverTooOld = incompatibility.serverTooOld,
        clientTooOld = incompatibility.clientTooOld,
    )
}
