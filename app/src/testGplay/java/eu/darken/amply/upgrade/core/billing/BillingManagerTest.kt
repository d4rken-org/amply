package eu.darken.amply.upgrade.core.billing

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClient.BillingResponseCode
import eu.darken.amply.upgrade.core.OurSku
import eu.darken.amply.upgrade.core.billing.client.BillingConnection
import eu.darken.amply.upgrade.core.billing.client.BillingConnectionProvider
import eu.darken.amply.upgrade.core.billing.client.FakeBillingClient
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
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
import java.util.concurrent.atomic.AtomicInteger

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

    private val iapId = OurSku.Iap.PRO_UPGRADE.id
    private val subId = OurSku.Sub.PRO_UPGRADE.id

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

    /**
     * A provider that hands out real [BillingConnection]s over scripted Play answers, one per
     * connection attempt (the last one repeats). Each stays open for its lifetime, like the real one.
     */
    private class ConnectingProvider(
        context: Context,
        private val clients: List<FakeBillingClient>,
        private val attempts: Channel<Unit>,
    ) : BillingConnectionProvider(context) {
        private val attemptCount = AtomicInteger(0)

        override val connection: Flow<BillingConnection> = flow {
            val client = clients[minOf(attemptCount.getAndIncrement(), clients.size - 1)]
            attempts.trySend(Unit)
            emit(BillingConnection(client))
            awaitCancellation()
        }
    }

    private fun connectedManager(
        vararg clients: FakeBillingClient,
        attempts: Channel<Unit> = Channel(Channel.UNLIMITED),
    ): BillingManager = BillingManager(
        ConnectingProvider(ApplicationProvider.getApplicationContext(), clients.toList(), attempts),
    )

    private fun client(
        iap: FakeBillingClient.Answer = FakeBillingClient.Answer(),
        subs: FakeBillingClient.Answer = FakeBillingClient.Answer(),
    ) = FakeBillingClient().apply {
        answers = mapOf(
            BillingClient.ProductType.INAPP to iap,
            BillingClient.ProductType.SUBS to subs,
        )
    }

    private fun failing(responseCode: Int) = FakeBillingClient.Answer(responseCode = responseCode)

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

    // region reconciliation outcomes

    @Test fun `refreshStrict fails closed on an incomplete refresh`(): Unit = runBlocking {
        val manager = connectedManager(
            client(
                iap = FakeBillingClient.Answer(purchases = listOf(TestPurchases.purchase(iapId))),
                subs = failing(BillingResponseCode.SERVICE_UNAVAILABLE),
            ),
        )

        // A gate must not treat "one product type couldn't be checked" as "nothing else is owned":
        // that is exactly how a double purchase gets through.
        withTimeout(TIMEOUT_MS) {
            shouldThrow<GplayServiceUnavailableException> { manager.refreshStrict() }
        }
    }

    @Test fun `refreshStrict returns the split data on a complete refresh`(): Unit = runBlocking {
        val owned = TestPurchases.purchase(iapId, purchaseTime = 2_000L)
        val pending = TestPurchases.purchase(subId, purchaseTime = 1_000L, pending = true)
        val manager = connectedManager(
            client(
                iap = FakeBillingClient.Answer(purchases = listOf(owned)),
                subs = FakeBillingClient.Answer(purchases = listOf(pending)),
            ),
        )

        withTimeout(TIMEOUT_MS) { manager.refreshStrict() } shouldBe BillingData(
            purchases = listOf(owned),
            pendingPurchases = listOf(pending),
        )
    }

    @Test fun `a partial reconciliation without a confirmed purchase signals its commit time`(): Unit = runBlocking {
        // The pending-only cold start: Play answered for one type with a payment in progress, the
        // other failed. Nothing confirms the upgrade, so the grace episode clock must advance.
        val pending = TestPurchases.purchase(iapId, pending = true)
        val before = System.currentTimeMillis()
        val manager = connectedManager(
            client(
                iap = FakeBillingClient.Answer(purchases = listOf(pending)),
                subs = failing(BillingResponseCode.SERVICE_UNAVAILABLE),
            ),
        )

        val failedAt = withTimeout(TIMEOUT_MS) { manager.connectionFailures.first() }

        (failedAt >= before) shouldBe true
        (failedAt <= System.currentTimeMillis()) shouldBe true
        // Still published: a partial refresh is a usable connection, and starving billingData would
        // leave the screen at Loading forever.
        withTimeout(TIMEOUT_MS) { manager.billingData.first() } shouldBe BillingData(
            purchases = emptyList(),
            pendingPurchases = listOf(pending),
        )
    }

    @Test fun `a partial refresh that confirmed a purchase does not signal`(): Unit = runBlocking {
        val manager = connectedManager(
            client(
                iap = FakeBillingClient.Answer(purchases = listOf(TestPurchases.purchase(iapId))),
                subs = failing(BillingResponseCode.SERVICE_UNAVAILABLE),
            ),
        )

        // The upgrade WAS confirmed by this round-trip; the failed sibling type proves nothing
        // against it, so the episode clock must stay untouched.
        withTimeout(TIMEOUT_MS) { manager.billingData.first() }.purchases.size shouldBe 1
        withTimeoutOrNull(QUIET_MS) { manager.connectionFailures.first() } shouldBe null
    }

    @Test fun `an invalidating partial refresh tears the connection down`(): Unit = runBlocking {
        // The dead-binder teardown used to ride refreshPurchases' throw path through useConnection.
        // A partial refresh returns instead of throwing, so without the explicit invalidation the
        // dead connection would stay installed for every later caller.
        val attempts = Channel<Unit>(Channel.UNLIMITED)
        connectedManager(
            client(
                iap = FakeBillingClient.Answer(purchases = listOf(TestPurchases.purchase(iapId))),
                subs = failing(BillingResponseCode.SERVICE_DISCONNECTED),
            ),
            client(),
            attempts = attempts,
        )

        withTimeout(TIMEOUT_MS) { attempts.receive() }
        withTimeout(TIMEOUT_MS) { attempts.receive() }
    }

    // endregion

    // region pending payments

    @Test fun `billing data splits owned purchases from pending payments`(): Unit = runBlocking {
        val owned = TestPurchases.purchase(iapId, purchaseTime = 2_000L)
        val pending = TestPurchases.purchase(subId, purchaseTime = 1_000L, pending = true)
        val manager = connectedManager(
            client(
                iap = FakeBillingClient.Answer(purchases = listOf(owned)),
                subs = FakeBillingClient.Answer(purchases = listOf(pending)),
            ),
        )

        // The split is what keeps a payment in progress out of every entitlement decision while still
        // letting the UI show it.
        withTimeout(TIMEOUT_MS) { manager.billingData.first() } shouldBe BillingData(
            purchases = listOf(owned),
            pendingPurchases = listOf(pending),
        )
    }

    @Test fun `an unacknowledged purchase is acknowledged`(): Unit = runBlocking {
        val owned = TestPurchases.purchase(iapId, acknowledged = false)
        val playClient = client(iap = FakeBillingClient.Answer(purchases = listOf(owned)))
        connectedManager(playClient)

        withTimeout(TIMEOUT_MS) {
            while (playClient.acknowledged.isEmpty()) delay(50)
        }
        // distinct(): the ack is deliberately unconditional and re-fires per emission until a fresh
        // query supersedes the purchase snapshot, so the count is not part of the contract — which
        // token gets acknowledged is.
        playClient.acknowledged.distinct() shouldBe listOf(owned.purchaseToken)
    }

    @Test fun `a pending purchase is never acknowledged`(): Unit = runBlocking {
        val pending = TestPurchases.purchase(iapId, acknowledged = false, pending = true)
        val playClient = client(iap = FakeBillingClient.Answer(purchases = listOf(pending)))
        val manager = connectedManager(playClient)

        // The purchase reached the pipeline…
        withTimeout(TIMEOUT_MS) { manager.billingData.first() }.pendingPurchases shouldBe listOf(pending)
        delay(QUIET_MS)
        // …and the ack pass left it alone. Play rejects acknowledging a pending purchase permanently:
        // an unfiltered pass would report a failure on every pass, forever, for a purchase that has
        // nothing to acknowledge yet.
        playClient.acknowledged shouldBe emptyList()
    }

    // endregion

    private companion object {
        const val TIMEOUT_MS = 15_000L
        // Long enough for the (synchronous) fake round-trips and their flow plumbing to have run.
        const val QUIET_MS = 500L
        // Comfortably inside the loop's 2s first backoff, so "did not retry yet" and "retried early"
        // are both decidable without racing the timer.
        const val BACKOFF_FLOOR_MS = 1_500L
    }
}
