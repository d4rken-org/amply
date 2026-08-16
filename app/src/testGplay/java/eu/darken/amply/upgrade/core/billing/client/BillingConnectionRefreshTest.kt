package eu.darken.amply.upgrade.core.billing.client

import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClient.BillingResponseCode
import com.android.billingclient.api.Purchase
import eu.darken.amply.upgrade.core.OurSku
import eu.darken.amply.upgrade.core.billing.GplayServiceUnavailableException
import eu.darken.amply.upgrade.core.billing.TestPurchases
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * What a refresh reports about itself. The gates and the grace bookkeeping both decide on this
 * provenance — "Play answered for both types", "this is what it confirmed", "this is why it is
 * incomplete" — so a refresh that mislabels itself silently mislabels every consumer downstream.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BillingConnectionRefreshTest {

    private val iapId = OurSku.Iap.PRO_UPGRADE.id
    private val subId = OurSku.Sub.PRO_UPGRADE.id

    private fun connection(
        client: FakeBillingClient = FakeBillingClient(),
    ): Pair<BillingConnection, FakeBillingClient> = BillingConnection(client) to client

    @Test fun `a complete refresh reports what it confirmed`(): Unit = runBlocking {
        val owned = TestPurchases.purchase(iapId)
        val pending = TestPurchases.purchase(subId, pending = true)
        val (connection, client) = connection()
        client.answers = mapOf(
            BillingClient.ProductType.INAPP to FakeBillingClient.Answer(purchases = listOf(owned)),
            BillingClient.ProductType.SUBS to FakeBillingClient.Answer(purchases = listOf(pending)),
        )

        val refresh = connection.refreshPurchases()

        refresh.isComplete shouldBe true
        refresh.partialError shouldBe null
        // The confirmed set is PURCHASED-only: it feeds the grace stamp, which a payment in progress
        // must never touch. The committed view carries both, because the UI needs the pending one.
        refresh.confirmed.map { it.purchaseToken } shouldBe listOf(owned.purchaseToken)
        refresh.hasConfirmedProPurchase shouldBe true
        refresh.purchases.map { it.purchaseToken }.toSet() shouldBe
            setOf(owned.purchaseToken, pending.purchaseToken)
    }

    @Test fun `a confirmed purchase of an unknown product does not count`(): Unit = runBlocking {
        val (connection, client) = connection()
        client.answers = mapOf(
            BillingClient.ProductType.INAPP to FakeBillingClient.Answer(
                purchases = listOf(TestPurchases.purchase("some.other.product")),
            ),
        )

        // The grace bookkeeping asks this to decide whether a partial refresh confirmed the upgrade;
        // a product this build doesn't sell confirms nothing about it.
        connection.refreshPurchases().hasConfirmedProPurchase shouldBe false
    }

    @Test fun `a partial refresh carries its mapped cause`(): Unit = runBlocking {
        val pending = TestPurchases.purchase(iapId, pending = true)
        val (connection, client) = connection()
        client.answers = mapOf(
            BillingClient.ProductType.INAPP to FakeBillingClient.Answer(purchases = listOf(pending)),
            BillingClient.ProductType.SUBS to FakeBillingClient.Answer(
                responseCode = BillingResponseCode.SERVICE_UNAVAILABLE,
            ),
        )

        // A pending payment for a product we sell IS an answer about this account, so the refresh
        // returns instead of throwing — but it stays incomplete, and the cause travels with it so a
        // gate can fail closed on exactly this.
        val refresh = connection.refreshPurchases()

        refresh.isComplete shouldBe false
        refresh.hasConfirmedProPurchase shouldBe false
        refresh.partialError.shouldBeInstanceOf<GplayServiceUnavailableException>()
    }

    @Test fun `a refresh that learned nothing usable throws`(): Unit = runBlocking {
        val (connection, client) = connection()
        client.answers = mapOf(
            BillingClient.ProductType.INAPP to FakeBillingClient.Answer(),
            BillingClient.ProductType.SUBS to FakeBillingClient.Answer(
                responseCode = BillingResponseCode.SERVICE_UNAVAILABLE,
            ),
        )

        // "Not owned" and "couldn't verify" must stay distinguishable for the caller.
        shouldThrow<GplayServiceUnavailableException> { connection.refreshPurchases() }
    }

    @Test fun `an empty refresh racing a pending event still proves absence`(): Unit = runBlocking {
        val (connection, client) = connection()
        client.answers = mapOf(
            BillingClient.ProductType.INAPP to FakeBillingClient.Answer(),
            BillingClient.ProductType.SUBS to FakeBillingClient.Answer(),
        )
        // A payment in progress arrives while the queries are already running, so it survives their
        // commit as an overlay entry. It owns nothing, so it must not suppress the full-snapshot
        // signal — otherwise the unconfirmed-episode clock would freeze for as long as the payment
        // takes to clear.
        client.emitEventDuringQuery(connection, TestPurchases.purchase(iapId, pending = true))

        connection.refreshPurchases()

        val update = withTimeout(TIMEOUT_MS) { connection.freshUpdates.first() }
        update.isFullSnapshot shouldBe true
        update.purchases.isEmpty() shouldBe true
    }

    @Test fun `an empty refresh racing an owned event does not prove absence`(): Unit = runBlocking {
        val (connection, client) = connection()
        client.answers = mapOf(
            BillingClient.ProductType.INAPP to FakeBillingClient.Answer(),
            BillingClient.ProductType.SUBS to FakeBillingClient.Answer(),
        )
        client.emitEventDuringQuery(connection, TestPurchases.purchase(iapId))

        connection.refreshPurchases()

        // The event's own emission comes first, then the query commit — which must not claim to have
        // proven that nothing is owned while a newer purchase event says otherwise.
        val updates = withTimeout(TIMEOUT_MS) { connection.freshUpdates.take(2).toList() }
        updates.map { it.isFullSnapshot } shouldBe listOf(false, false)
    }

    // A purchase event delivered from inside the first query: only an event NEWER than the query
    // start survives that query's commit, which is what these overlay cases are about.
    private fun FakeBillingClient.emitEventDuringQuery(connection: BillingConnection, purchase: Purchase) {
        var emitted = false
        beforeAnswer = {
            if (!emitted) {
                emitted = true
                connection.onPurchasesUpdated(FakeBillingClient.result(BillingResponseCode.OK), listOf(purchase))
            }
        }
    }

    private companion object {
        const val TIMEOUT_MS = 10_000L
    }
}
