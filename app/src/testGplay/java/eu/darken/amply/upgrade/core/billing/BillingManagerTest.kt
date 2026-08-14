package eu.darken.amply.upgrade.core.billing

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import eu.darken.amply.upgrade.core.billing.client.BillingConnection
import eu.darken.amply.upgrade.core.billing.client.BillingConnectionProvider
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The connect loop owns every retry decision in the billing stack, so what it does when Play is
 * unreachable is what decides whether the entitlement gates ever resolve during an outage.
 *
 * Real time, not virtual: the loop runs on its own dispatcher, which a test scheduler cannot advance.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BillingManagerTest {

    private val testScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    @After fun teardown() {
        testScope.cancel()
    }

    /** A provider whose connection attempts are handed to the test one at a time. */
    private class FakeProvider(
        context: Context,
        private val attempts: Channel<Unit>,
    ) : BillingConnectionProvider(context) {
        // Completes immediately without ever emitting a connection: to the loop that reads as a
        // connection that ended without an error, which it must treat as a failure and retry.
        override val connection: Flow<BillingConnection> = kotlinx.coroutines.flow.flow {
            attempts.trySend(Unit)
            emptyFlow<BillingConnection>().collect { emit(it) }
        }
    }

    private fun manager(attempts: Channel<Unit>): BillingManager = BillingManager(
        FakeProvider(ApplicationProvider.getApplicationContext(), attempts),
    )

    @Test fun `an unreachable play settles the failure signal instead of staying silent`(): Unit = runBlocking {
        val attempts = Channel<Unit>(Channel.UNLIMITED)
        val manager = manager(attempts)

        // Without this signal a consumer could not tell "Play is down" from "still connecting", and
        // every entitlement gate would wait out its timeout on every check during an outage.
        withTimeout(TIMEOUT_MS) { manager.isFailureSettled.first { it } } shouldBe true
    }

    @Test fun `each failed attempt is reported with its own occurrence time`(): Unit = runBlocking {
        val attempts = Channel<Unit>(Channel.UNLIMITED)
        val manager = manager(attempts)

        val before = System.currentTimeMillis()
        val failedAt = withTimeout(TIMEOUT_MS) { manager.connectionFailures.first() }

        // The timestamp is the point: the grace bookkeeping orders failures against confirmations,
        // and a bare signal could reopen an episode a later success already closed.
        (failedAt >= before) shouldBe true
        (failedAt <= System.currentTimeMillis()) shouldBe true
    }

    @Test fun `a retry waits out the backoff rather than spinning`(): Unit = runBlocking {
        val attempts = Channel<Unit>(Channel.UNLIMITED)
        manager(attempts)

        withTimeout(TIMEOUT_MS) { attempts.receive() }
        // The first backoff is two seconds. A loop without one would burn the CPU (and Play's rate
        // limits) reconnecting as fast as the failures arrive.
        withTimeoutOrNull(BACKOFF_FLOOR_MS) { attempts.receive() } shouldBe null
        withTimeout(TIMEOUT_MS) { attempts.receive() }
    }

    @Test fun `active demand cuts the backoff short`(): Unit = runBlocking {
        val attempts = Channel<Unit>(Channel.UNLIMITED)
        val manager = manager(attempts)

        withTimeout(TIMEOUT_MS) { attempts.receive() }

        // Someone actually wants billing now — a user who just fixed their Play situation should not
        // have to wait out a timer that was armed before they did.
        val refreshStarted = CompletableDeferred<Unit>()
        testScope.launch {
            refreshStarted.complete(Unit)
            runCatching { manager.refresh() }
        }
        refreshStarted.await()

        withTimeout(BACKOFF_FLOOR_MS) { attempts.receive() }
    }

    private companion object {
        const val TIMEOUT_MS = 15_000L
        // Comfortably inside the loop's 2s first backoff, so "did not retry yet" and "retried early"
        // are both decidable without racing the timer.
        const val BACKOFF_FLOOR_MS = 1_500L
    }
}
