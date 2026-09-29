package eu.darken.amply.charging.core.access.shizuku

import android.os.IBinder
import android.os.IInterface
import android.os.Parcel
import eu.darken.porter.sdk.UserServiceArgs
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import java.io.FileDescriptor

/** Defaults to no server and no manager, which is what a Robolectric run without a binder sees. */
internal class FakePorterGateway(
    initialLink: PrivilegedLink? = null,
    var manager: PrivilegedManager = PrivilegedManager.NotInstalled,
) : PorterGateway {
    val link = MutableStateFlow(initialLink)

    override fun currentLink(): PrivilegedLink? = link.value

    override val links: Flow<PrivilegedLink?> = link

    override suspend fun availability(): PrivilegedManager = manager
}

/** Identity equality, like the real link: two instances are two connections. */
internal class FakeLink(
    override val backend: ManagerBackend = ManagerBackend.SHIZUKU,
) : PrivilegedLink {
    var alive = true
    var permissionCheck: suspend () -> Boolean = { true }
    var permissionRequest: suspend () -> Boolean = { true }

    /** Each collection of [userService] runs this; the default binds and then holds the binding. */
    var serviceFlow: () -> Flow<IBinder> = {
        flow {
            emit(FakeBinder())
            awaitCancellation()
        }
    }

    var userServiceCollections = 0
        private set

    override fun isAlive(): Boolean = alive

    override suspend fun checkPermission(): Boolean = permissionCheck()

    override suspend fun requestPermission(): Boolean = permissionRequest()

    override fun userService(args: UserServiceArgs): Flow<IBinder> = flow {
        userServiceCollections++
        emitAll(serviceFlow())
    }
}

/** A remote binder whose liveness the test controls; `Stub.asInterface` wraps it in the generated proxy. */
internal class FakeBinder : IBinder {
    var alive = true

    override fun getInterfaceDescriptor(): String = "eu.darken.amply.charging.core.access.shizuku.IChargingControlService"

    override fun pingBinder(): Boolean = alive

    override fun isBinderAlive(): Boolean = alive

    override fun queryLocalInterface(descriptor: String): IInterface? = null

    override fun dump(fd: FileDescriptor, args: Array<out String>?) = Unit

    override fun dumpAsync(fd: FileDescriptor, args: Array<out String>?) = Unit

    override fun transact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean = alive

    override fun linkToDeath(recipient: IBinder.DeathRecipient, flags: Int) = Unit

    override fun unlinkToDeath(recipient: IBinder.DeathRecipient, flags: Int): Boolean = true
}
