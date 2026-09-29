package eu.darken.amply.charging.core.access.shizuku

import android.content.Context
import android.os.IBinder
import androidx.test.core.app.ApplicationProvider
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.longs.shouldBeLessThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeSameInstanceAs
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException

/**
 * Robolectric only because the controller builds the user service's `ComponentName`, which the plain JVM
 * android.jar stubs out. The collector runs in the test's `backgroundScope`, so virtual time drives every
 * timeout, and an exception escaping the collector would fail the test.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ShizukuControllerTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun TestScope.controller(gateway: PorterGateway) = ShizukuController(context, gateway, backgroundScope)

    private fun holdingFlow(binder: IBinder = FakeBinder()): Flow<IBinder> = flow {
        emit(binder)
        awaitCancellation()
    }

    @Test
    fun `concurrent callers share one user service collection`() = runTest {
        val link = FakeLink()
        val controller = controller(FakePorterGateway(link))

        val services = List(3) { async { controller.service() } }.awaitAll()

        link.userServiceCollections shouldBe 1
        services.map { it.asBinder() }.distinct().size shouldBe 1
    }

    @Test
    fun `a flow that completes before its first binder fails the caller at once`() = runTest {
        val link = FakeLink().apply { serviceFlow = { emptyFlow() } }
        val controller = controller(FakePorterGateway(link))

        val error = shouldThrow<IllegalStateException> { controller.service() }

        error.message shouldContain "ended before it connected"
        currentTime shouldBeLessThan 1_000L
    }

    @Test
    fun `a flow that throws before its first binder fails the caller at once`() = runTest {
        val link = FakeLink().apply { serviceFlow = { flow { throw IOException("bind refused") } } }
        val controller = controller(FakePorterGateway(link))

        val error = shouldThrow<IOException> { controller.service() }

        error.message shouldContain "bind refused"
        currentTime shouldBeLessThan 1_000L
    }

    @Test
    fun `a failure after binding clears the record and the next call rebinds`() = runTest {
        val serviceDies = CompletableDeferred<Unit>()
        val link = FakeLink().apply {
            serviceFlow = {
                flow {
                    emit(FakeBinder())
                    serviceDies.await()
                    throw IOException("service died")
                }
            }
        }
        val controller = controller(FakePorterGateway(link))
        val first = controller.service()

        serviceDies.complete(Unit)
        runCurrent()
        link.serviceFlow = { holdingFlow() }
        val second = controller.service()

        link.userServiceCollections shouldBe 2
        first.asBinder().pingBinder() shouldBe true
        second.asBinder() shouldNotBe first.asBinder()
    }

    @Test
    fun `the bind timeout cancels the collector and the next call rebinds`() = runTest {
        var collectorCancelled = false
        val link = FakeLink().apply {
            serviceFlow = {
                flow {
                    try {
                        awaitCancellation()
                    } finally {
                        collectorCancelled = true
                    }
                }
            }
        }
        val controller = controller(FakePorterGateway(link))

        shouldThrow<TimeoutCancellationException> { controller.service() }
        currentTime shouldBe 15_000L
        runCurrent()
        collectorCancelled shouldBe true

        val binder = FakeBinder()
        link.serviceFlow = { holdingFlow(binder) }
        controller.service().asBinder() shouldBeSameInstanceAs binder
        link.userServiceCollections shouldBe 2
    }

    @Test
    fun `caller cancellation cancels the collector so a late binder is never published`() = runTest {
        val bindReady = CompletableDeferred<Unit>()
        val lateBinder = FakeBinder()
        var collectorCancelled = false
        val link = FakeLink().apply {
            serviceFlow = {
                flow {
                    try {
                        bindReady.await()
                        emit(lateBinder)
                        awaitCancellation()
                    } finally {
                        collectorCancelled = true
                    }
                }
            }
        }
        val controller = controller(FakePorterGateway(link))

        val caller = launch { controller.service() }
        runCurrent()
        caller.cancelAndJoin()
        bindReady.complete(Unit)
        runCurrent()
        collectorCancelled shouldBe true

        val binder = FakeBinder()
        link.serviceFlow = { holdingFlow(binder) }
        controller.service().asBinder() shouldBeSameInstanceAs binder
        link.userServiceCollections shouldBe 2
    }

    @Test
    fun `a replaced link rebinds even while the old service still pings`() = runTest {
        val first = FakeLink()
        val gateway = FakePorterGateway(first)
        val controller = controller(gateway)
        val old = controller.service()

        val second = FakeLink()
        gateway.link.value = second
        val new = controller.service()

        old.asBinder().pingBinder() shouldBe true
        new.asBinder() shouldNotBe old.asBinder()
        first.userServiceCollections shouldBe 1
        second.userServiceCollections shouldBe 1
    }

    @Test
    fun `an old collector finishing after a new binding does not clear it`() = runTest {
        val first = FakeLink().apply {
            serviceFlow = {
                flow {
                    emit(FakeBinder())
                    try {
                        awaitCancellation()
                    } finally {
                        // The old collection ends well after the new binding is in place.
                        withContext(NonCancellable) { delay(1_000) }
                    }
                }
            }
        }
        val gateway = FakePorterGateway(first)
        val controller = controller(gateway)
        controller.service()

        val second = FakeLink()
        gateway.link.value = second
        val new = controller.service()
        advanceTimeBy(2_000)
        runCurrent()

        controller.service() shouldBeSameInstanceAs new
        second.userServiceCollections shouldBe 1
    }

    @Test
    fun `service refuses without a link or a grant`() = runTest {
        shouldThrow<IllegalStateException> { controller(FakePorterGateway()).service() }

        val denied = FakeLink().apply { permissionCheck = { false } }
        shouldThrow<IllegalStateException> { controller(FakePorterGateway(denied)).service() }
        denied.userServiceCollections shouldBe 0
    }

    @Test
    fun `isGranted is false when the permission check hangs past five seconds`() = runTest {
        val link = FakeLink().apply { permissionCheck = { awaitCancellation() } }

        controller(FakePorterGateway(link)).isGranted() shouldBe false
        currentTime shouldBe 5_000L
    }

    @Test
    fun `isGranted is false on a dead link or a failed check`() = runTest {
        controller(FakePorterGateway(FakeLink().apply { alive = false })).isGranted() shouldBe false

        val failing = FakeLink().apply { permissionCheck = { throw PrivilegedAccessException(IOException("refused")) } }
        controller(FakePorterGateway(failing)).isGranted() shouldBe false
    }

    /**
     * The SDK's `PorterConnectionLostException` has an internal constructor, so this throws the seam's
     * [PrivilegedAccessException], which is what the link wrapper turns every SDK failure into.
     */
    @Test
    fun `requestPermission is false when the connection is lost mid-request`() = runTest {
        var requested = false
        val link = FakeLink().apply {
            permissionCheck = { false }
            permissionRequest = {
                requested = true
                throw PrivilegedAccessException(IllegalStateException("the Porter connection was lost"))
            }
        }

        controller(FakePorterGateway(link)).requestPermission() shouldBe false
        requested shouldBe true
    }

    @Test
    fun `requestPermission is false when the prompt is never answered`() = runTest {
        val link = FakeLink().apply {
            permissionCheck = { false }
            permissionRequest = { awaitCancellation() }
        }

        controller(FakePorterGateway(link)).requestPermission() shouldBe false
        currentTime shouldBe 60_000L
    }

    @Test
    fun `requestPermission short-circuits without a link and when already granted`() = runTest {
        controller(FakePorterGateway()).requestPermission() shouldBe false

        var requested = false
        val granted = FakeLink().apply { permissionRequest = { requested = true; true } }
        controller(FakePorterGateway(granted)).requestPermission() shouldBe true
        requested shouldBe false
    }

    @Test
    fun `accessEvents emits the current link and every replacement`() = runTest {
        val gateway = FakePorterGateway(FakeLink())
        val controller = controller(gateway)
        val events = mutableListOf<Unit>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { controller.accessEvents.toList(events) }

        gateway.link.value = null
        runCurrent()
        gateway.link.value = FakeLink()
        runCurrent()

        events.size shouldBe 3
    }
}
