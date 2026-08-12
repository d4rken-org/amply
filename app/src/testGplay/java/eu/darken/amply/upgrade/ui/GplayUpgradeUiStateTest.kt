package eu.darken.amply.upgrade.ui

import com.android.billingclient.api.TestProductDetails
import eu.darken.amply.upgrade.core.OurSku
import eu.darken.amply.upgrade.core.UpgradeRepoGplay
import eu.darken.amply.upgrade.core.billing.SkuDetails
import eu.darken.amply.upgrade.core.billing.TestPurchases
import eu.darken.amply.upgrade.core.billing.toBillingData
import io.kotest.matchers.shouldBe
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The mapping that decides which purchase buttons a user is offered. Getting it wrong either hides a
 * sale or — worse — lets someone stack a one-time purchase on a renewing subscription.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GplayUpgradeUiStateTest {

    private fun subDetails(vararg offers: TestProductDetails.Offer) = SkuDetails(
        sku = OurSku.Sub.PRO_UPGRADE,
        details = TestProductDetails.subscription(OurSku.Sub.PRO_UPGRADE.id, offers.toList()),
    )

    private fun iapDetails(price: String = "$9.99") = SkuDetails(
        sku = OurSku.Iap.PRO_UPGRADE,
        details = TestProductDetails.oneTimePurchase(OurSku.Iap.PRO_UPGRADE.id, price),
    )

    private val baseOffer = TestProductDetails.Offer("upgrade-pro-baseplan", null, "$4.99")
    private val trialOffer =
        TestProductDetails.Offer("upgrade-pro-baseplan", "upgrade-pro-baseplan-trial", "$4.99")

    // region offers

    @Test fun `a returned trial offer is what promises the trial`() {
        toLoadedState(
            iap = iapDetails(),
            sub = subDetails(baseOffer, trialOffer),
            ownership = Ownership(),
        ).apply {
            subscriptionAction shouldBe SubscriptionAction.TRIAL
            subscriptionEnabled shouldBe true
            // The recurring price comes from the base plan, never from the trial phase.
            subscriptionPrice shouldBe "$4.99"
            iapEnabled shouldBe true
            iapPrice shouldBe "$9.99"
        }
    }

    @Test fun `without the trial offer the subscription is offered plainly`() {
        toLoadedState(
            iap = iapDetails(),
            sub = subDetails(baseOffer),
            ownership = Ownership(),
        ).subscriptionAction shouldBe SubscriptionAction.STANDARD
    }

    @Test fun `no usable offer disables the subscription action`() {
        toLoadedState(iap = iapDetails(), sub = null, ownership = Ownership()).apply {
            subscriptionAction shouldBe SubscriptionAction.UNAVAILABLE
            subscriptionEnabled shouldBe false
            subscriptionPrice shouldBe null
        }
    }

    @Test fun `an ambiguous duplicate offer is refused rather than guessed`() {
        // Two rows matching the same base plan: picking either could bill the user at a price we
        // never showed them.
        toLoadedState(
            iap = iapDetails(),
            sub = subDetails(baseOffer, TestProductDetails.Offer("upgrade-pro-baseplan", null, "$9.99")),
            ownership = Ownership(),
        ).apply {
            subscriptionAction shouldBe SubscriptionAction.UNAVAILABLE
            subscriptionEnabled shouldBe false
        }
    }

    // endregion

    // region ownership and locks

    @Test fun `an owner is not offered what they already have`() {
        toLoadedState(
            iap = iapDetails(),
            sub = subDetails(baseOffer, trialOffer),
            ownership = Ownership(hasIap = true, subscription = SubscriptionOwnership(isAutoRenewing = true)),
        ).apply {
            iapEnabled shouldBe false
            subscriptionEnabled shouldBe false
        }
    }

    @Test fun `any running entitlement action pauses every purchase button`() {
        // They all reconcile the same Play account state; starting a purchase mid-restore just races
        // Play into ITEM_ALREADY_OWNED.
        BusyOp.entries.forEach { op ->
            toLoadedState(
                iap = iapDetails(),
                sub = subDetails(baseOffer),
                ownership = Ownership(),
                busy = op,
            ).apply {
                iapEnabled shouldBe false
                subscriptionEnabled shouldBe false
            }
        }
    }

    @Test fun `a payment play is still processing blocks its own offer only`() {
        toLoadedState(
            iap = iapDetails(),
            sub = subDetails(baseOffer),
            ownership = Ownership(),
            iapPending = true,
        ).apply {
            // The money is already committed — inviting a second payment would be the defect.
            iapEnabled shouldBe false
            subscriptionEnabled shouldBe true
            anyPending shouldBe true
        }

        toLoadedState(
            iap = iapDetails(),
            sub = subDetails(baseOffer),
            ownership = Ownership(),
            subscriptionPending = true,
        ).apply {
            subscriptionEnabled shouldBe false
            iapEnabled shouldBe true
        }
    }

    @Test fun `ownership of anything is what switches the screen to the status view`() {
        Ownership().ownsAnything shouldBe false
        Ownership(hasIap = true).ownsAnything shouldBe true
        Ownership(subscription = SubscriptionOwnership(isAutoRenewing = false)).ownsAnything shouldBe true
    }

    // endregion

    // region toOwnership

    @Test fun `ownership is read off the purchases themselves`() {
        val info = UpgradeRepoGplay.Info(
            billingData = listOf(
                TestPurchases.purchase(OurSku.Iap.PRO_UPGRADE.id, token = "a"),
                TestPurchases.purchase(OurSku.Sub.PRO_UPGRADE.id, token = "b", autoRenewing = true),
            ).toBillingData(),
        )

        info.toOwnership() shouldBe Ownership(
            hasIap = true,
            subscription = SubscriptionOwnership(isAutoRenewing = true),
        )
    }

    @Test fun `any record still claiming auto-renew counts as renewing`() {
        // Conservative on purpose: this can only under-offer the one-time purchase, never enable it
        // wrongly. The actual purchase gate re-verifies against a fresh SUBS query.
        val info = UpgradeRepoGplay.Info(
            billingData = listOf(
                TestPurchases.purchase(OurSku.Sub.PRO_UPGRADE.id, token = "stale", autoRenewing = true),
                TestPurchases.purchase(OurSku.Sub.PRO_UPGRADE.id, token = "fresh", autoRenewing = false),
            ).toBillingData(),
        )

        info.toOwnership().subscription shouldBe SubscriptionOwnership(isAutoRenewing = true)
    }

    @Test fun `grace ownership owns nothing`() {
        UpgradeRepoGplay.Info(gracePeriod = true, billingData = null).toOwnership() shouldBe Ownership()
    }

    // endregion
}
