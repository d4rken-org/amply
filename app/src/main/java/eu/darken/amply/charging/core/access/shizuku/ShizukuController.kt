package eu.darken.amply.charging.core.access.shizuku

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import dagger.hilt.android.qualifiers.ApplicationContext
import eu.darken.amply.BuildConfig
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import rikka.shizuku.Shizuku
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ShizukuController @Inject constructor(
    @ApplicationContext private val context: Context,
    private val installationDetector: ShizukuInstallationDetector,
) {
    private val connectionMutex = Mutex()
    @Volatile private var cachedService: IChargingControlService? = null

    fun isAvailable(): Boolean = runCatching { Shizuku.pingBinder() }.getOrDefault(false)

    fun managerPackage(): String? = installationDetector.managerPackage()

    fun isGranted(): Boolean = isAvailable() && runCatching {
        Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    }.getOrDefault(false)

    suspend fun requestPermission(): Boolean {
        if (!isAvailable()) return false
        if (isGranted()) return true
        val result = CompletableDeferred<Boolean>()
        val listener = object : Shizuku.OnRequestPermissionResultListener {
            override fun onRequestPermissionResult(requestCode: Int, grantResult: Int) {
                if (requestCode == REQUEST_CODE) {
                    result.complete(grantResult == PackageManager.PERMISSION_GRANTED)
                }
            }
        }
        Shizuku.addRequestPermissionResultListener(listener)
        return try {
            Shizuku.requestPermission(REQUEST_CODE)
            withTimeout(60_000) { result.await() }
        } finally {
            Shizuku.removeRequestPermissionResultListener(listener)
        }
    }

    suspend fun service(): IChargingControlService = connectionMutex.withLock {
        cachedService?.takeIf { it.asBinder().pingBinder() }?.let { return@withLock it }
        check(isGranted()) { "Shizuku is not running or permission is missing" }

        val connected = CompletableDeferred<IChargingControlService>()
        val callback = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                val service = IChargingControlService.Stub.asInterface(binder)
                cachedService = service
                connected.complete(service)
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                cachedService = null
            }
        }
        val args = Shizuku.UserServiceArgs(
            ComponentName(context.packageName, ChargingControlUserService::class.java.name),
        ).daemon(false)
            .processNameSuffix("charging")
            .debuggable(BuildConfig.DEBUG)
            .version(BuildConfig.VERSION_CODE)
        Shizuku.bindUserService(args, callback)
        withTimeout(15_000) { connected.await() }
    }

    suspend fun grantWriteSecureSettings(): Boolean =
        service().grantWriteSecureSettings(context.packageName)

    companion object {
        private const val REQUEST_CODE = 8841
    }
}
