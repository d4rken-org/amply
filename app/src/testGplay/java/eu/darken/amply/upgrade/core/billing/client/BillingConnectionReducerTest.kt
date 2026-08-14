package eu.darken.amply.upgrade.core.billing.client

import com.android.billingclient.api.Purchase
import eu.darken.amply.upgrade.core.OurSku
import eu.darken.amply.upgrade.core.billing.Sku
import eu.darken.amply.upgrade.core.billing.TestPurchases
import eu.darken.amply.upgrade.core.billing.pending
import eu.darken.amply.upgrade.core.billing.purchased
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The reducer is where ownership actually gets decided, and its whole job is ordering: a purchase
 * event proves presence but never absence, a per-type query is authoritative only for its own type,
 * and neither may resurrect what the other superseded.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BillingConnectionReducerTest {

    private val iapId = OurSku.Iap.PRO_UPGRADE.id
    private val subId = OurSku.Sub.PRO_UPGRADE.id
    private val typeOf = BillingConnection.DEFAULT_SKU_TYPE_RESOLVER

    private fun state() = BillingConnection.ReducerState()

    @Test fun `nothing queried yet is not the same as owning nothing`() {
        state().isSettled shouldBe false
        state().withEvent(listOf(TestPurchases.purchase(iapId)), typeOf).isSettled shouldBe false
        state().withQueryResults(iap = emptyList(), sub = null, genAtQueryStart = 0).isSettled shouldBe true
    }

    @Test fun `a query supersedes only the overlay entries it could have seen`() {
        val early = TestPurchases.purchase(iapId, token = "early")
        val late = TestPurchases.purchase(iapId, token = "late")

        val withEarly = state().withEvent(listOf(early), typeOf)
        val genAtQueryStart = withEarly.eventGen
        // The event arrives after the query started: the query's empty result can't have covered it.
        val withLate = withEarly.withEvent(listOf(late), typeOf)

        val committed = withLate.withQueryResults(iap = emptyList(), sub = emptyList(), genAtQueryStart)

        committed.merged().map { it.purchaseToken } shouldBe listOf("late")
    }

    @Test fun `a failed product type keeps its overlay entries`() {
        val sub = TestPurchases.purchase(subId)
        val withEvent = state().withEvent(listOf(sub), typeOf)

        // Only the IAP query succeeded; the SUBS one failed, so the sub event must survive.
        val committed = withEvent.withQueryResults(
            iap = emptyList(),
            sub = null,
            genAtQueryStart = withEvent.eventGen,
        )

        committed.merged().map { it.purchaseToken } shouldBe listOf(sub.purchaseToken)
    }

    @Test fun `an unknown product only falls to a complete refresh`() {
        val unknown = TestPurchases.purchase("some.other.product")
        val withEvent = state().withEvent(listOf(unknown), typeOf)
        val gen = withEvent.eventGen

        // A single-type query can't classify it, so it stays.
        withEvent
            .withQueryResults(iap = emptyList(), sub = null, genAtQueryStart = gen)
            .merged().size shouldBe 1

        // Both types answered: nothing is left that could own it.
        withEvent
            .withQueryResults(iap = emptyList(), sub = emptyList(), genAtQueryStart = gen)
            .merged().shouldBeEmpty()
    }

    @Test fun `the newer instance of a purchase wins over the older one`() {
        // Same token, different ack state: the snapshot is stale, the overlay event is fresh.
        val stale = TestPurchases.purchase(iapId, token = "same", acknowledged = false)
        val fresh = TestPurchases.purchase(iapId, token = "same", acknowledged = true)

        val committed = state()
            .withQueryResults(iap = listOf(stale), sub = emptyList(), genAtQueryStart = 0)
            .withEvent(listOf(fresh), typeOf)

        val merged = committed.merged()
        merged.size shouldBe 1
        merged.single().isAcknowledged shouldBe true
    }

    @Test fun `a pending purchase is carried but never counts as owned`() {
        val pendingIap = TestPurchases.purchase(iapId, pending = true)

        val committed = state().withQueryResults(iap = listOf(pendingIap), sub = emptyList(), genAtQueryStart = 0)

        // It is visible…
        committed.merged().size shouldBe 1
        // …but the entitlement split leaves it out, and the pending half picks it up.
        committed.merged().purchased().shouldBeEmpty()
        committed.merged().pending().map { it.purchaseToken } shouldBe listOf(pendingIap.purchaseToken)
    }

    @Test fun `a pending purchase that completes replaces its pending record`() {
        val token = "same-token"
        val whilePending = TestPurchases.purchase(iapId, token = token, pending = true)
        val onceCompleted = TestPurchases.purchase(iapId, token = token, pending = false)

        val committed = state()
            .withQueryResults(iap = listOf(whilePending), sub = emptyList(), genAtQueryStart = 0)
            .withEvent(listOf(onceCompleted), typeOf)

        committed.merged().size shouldBe 1
        committed.merged().pending().shouldBeEmpty()
        committed.merged().purchased().size shouldBe 1
    }

    @Test fun `merged orders newest purchase first`() {
        val old = TestPurchases.purchase(iapId, token = "old", purchaseTime = 1_000L)
        val new = TestPurchases.purchase(subId, token = "new", purchaseTime = 9_000L)

        state()
            .withQueryResults(iap = listOf(old), sub = listOf(new), genAtQueryStart = 0)
            .merged().map { it.purchaseToken } shouldBe listOf("new", "old")
    }

    @Test fun `the sku type resolver classifies our products and nothing else`() {
        typeOf(iapId) shouldBe Sku.Type.IAP
        typeOf(subId) shouldBe Sku.Type.SUBSCRIPTION
        typeOf("some.other.product") shouldBe null
    }

    // region combinePurchaseResults

    @Test fun `a purchase found by either type is authoritative`() {
        val found = TestPurchases.purchase(iapId)

        BillingConnection.combinePurchaseResults(
            iap = Result.success(listOf(found)),
            sub = Result.failure(IllegalStateException("subs query broke")),
        ).map { it.purchaseToken } shouldBe listOf(found.purchaseToken)
    }

    @Test fun `an error only propagates when nothing was found`() {
        // "Not owned" and "couldn't verify" must stay distinguishable for the caller.
        shouldThrow<IllegalStateException> {
            BillingConnection.combinePurchaseResults(
                iap = Result.success(emptyList()),
                sub = Result.failure(IllegalStateException("subs query broke")),
            )
        }

        BillingConnection.combinePurchaseResults(
            iap = Result.success(emptyList()),
            sub = Result.success(emptyList()),
        ).shouldBeEmpty()
    }

    @Test fun `a pending purchase owns nothing, so it must not swallow a failed query`() {
        // Otherwise a pending IAP next to a broken SUBS query would read as "verified, nothing
        // owned" — and a real subscription behind that failure would look lapsed.
        shouldThrow<IllegalStateException> {
            BillingConnection.combinePurchaseResults(
                iap = Result.success(listOf(TestPurchases.purchase(iapId, pending = true))),
                sub = Result.failure(IllegalStateException("subs query broke")),
            )
        }

        // Nothing failed: the pending purchase is still reported, because the UI has to show it.
        BillingConnection.combinePurchaseResults(
            iap = Result.success(listOf(TestPurchases.purchase(iapId, pending = true))),
            sub = Result.success(emptyList()),
        ).map { it.purchaseToken } shouldBe listOf("token-$iapId")
    }

    // endregion

    private fun Collection<Purchase>.shouldBeEmpty() {
        isEmpty() shouldBe true
    }
}
