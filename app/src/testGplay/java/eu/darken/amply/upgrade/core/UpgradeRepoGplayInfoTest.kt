package eu.darken.amply.upgrade.core

import eu.darken.amply.upgrade.core.billing.BillingData
import eu.darken.amply.upgrade.core.billing.PurchasedSku
import eu.darken.amply.upgrade.core.billing.Sku
import eu.darken.amply.upgrade.core.billing.TestPurchases
import eu.darken.amply.upgrade.core.billing.toBillingData
import io.kotest.matchers.shouldBe
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant

/**
 * The pure half of the gplay entitlement: how a Play purchase list becomes an [UpgradeRepo.Info], and
 * the grace/window arithmetic that decides how long a lapsed confirmation still counts.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class UpgradeRepoGplayInfoTest {

    private val iapId = OurSku.Iap.PRO_UPGRADE.id
    private val subId = OurSku.Sub.PRO_UPGRADE.id
    private val week = UpgradeRepoGplay.GRACE_PERIOD_MS
    private val month = UpgradeRepoGplay.GRACE_PERIOD_IAP_MS

    // region purchase mapping

    @Test fun `a known purchase makes the info pro and dates it`() {
        val info = UpgradeRepoGplay.Info(
            billingData = listOf(TestPurchases.purchase(iapId, purchaseTime = 5_000L)).toBillingData(),
        )

        info.type shouldBe UpgradeRepo.Type.GPLAY
        info.isPro shouldBe true
        info.upgrades.map { it.sku } shouldBe listOf(OurSku.Iap.PRO_UPGRADE)
        info.upgradedAt shouldBe Instant.ofEpochMilli(5_000L)
    }

    @Test fun `the newest purchase supplies the upgrade date`() {
        val info = UpgradeRepoGplay.Info(
            billingData = listOf(
                TestPurchases.purchase(iapId, token = "a", purchaseTime = 1_000L),
                TestPurchases.purchase(subId, token = "b", purchaseTime = 9_000L),
            ).toBillingData(),
        )

        info.upgradedAt shouldBe Instant.ofEpochMilli(9_000L)
    }

    @Test fun `a product this app doesn't sell is skipped, not trusted`() {
        val info = UpgradeRepoGplay.Info(
            billingData = listOf(TestPurchases.purchase("com.example.other")).toBillingData(),
        )

        info.upgrades.isEmpty() shouldBe true
        info.isPro shouldBe false
    }

    @Test fun `a pending payment grants nothing but stays visible`() {
        val info = UpgradeRepoGplay.Info(
            billingData = listOf(TestPurchases.purchase(subId, pending = true)).toBillingData(),
        )

        info.isPro shouldBe false
        info.upgrades.isEmpty() shouldBe true
        // The UI needs it: an offer row for something already being paid for must not invite a
        // second purchase.
        info.pending shouldBe listOf(OurSku.Sub.PRO_UPGRADE)
    }

    @Test fun `a pending payment for a product this app doesn't sell is dropped`() {
        UpgradeRepoGplay.Info(
            billingData = listOf(TestPurchases.purchase("com.example.other", pending = true)).toBillingData(),
        ).pending.isEmpty() shouldBe true
    }

    @Test fun `a renewing subscription is seen even when its product is unknown`() {
        // The pre-purchase gate asks this, and it reads the RAW purchases on purpose: a subscription
        // whose product ID this build doesn't know (legacy SKU, renamed product) still renews and
        // still bills, so it has to keep blocking a second purchase.
        UpgradeRepoGplay.Info(
            billingData = listOf(
                TestPurchases.purchase("com.example.legacy.sub", autoRenewing = true),
            ).toBillingData(),
        ).apply {
            upgrades.isEmpty() shouldBe true
            hasAutoRenewingSubscription shouldBe true
        }
    }

    @Test fun `a one-time purchase never reads as renewing`() {
        // Which is what makes scanning both product types safe: the wider input cannot produce a
        // false positive.
        UpgradeRepoGplay.Info(
            billingData = listOf(TestPurchases.purchase(iapId)).toBillingData(),
        ).hasAutoRenewingSubscription shouldBe false
    }

    @Test fun `a pending subscription does not read as renewing`() {
        // Nothing is being billed yet: the payment hasn't gone through, and the pending gate is what
        // blocks this purchase, not the renewal one.
        UpgradeRepoGplay.Info(
            billingData = listOf(TestPurchases.purchase(subId, autoRenewing = true, pending = true)).toBillingData(),
        ).hasAutoRenewingSubscription shouldBe false
    }

    @Test fun `grace alone is enough to stay pro`() {
        val info = UpgradeRepoGplay.Info(gracePeriod = true, billingData = null)

        info.isPro shouldBe true
        info.upgrades.isEmpty() shouldBe true
        // Nothing is owned, so there is no purchase date to show.
        info.upgradedAt shouldBe null
    }

    @Test fun `settledness defaults to false`() {
        // The fail-safe direction: a forgotten stamp shows up as "never settles" (loud), never as a
        // settled pre-reconciliation flash that would deny a paying user.
        UpgradeRepoGplay.Info(billingData = BillingData(emptyList())).isSettled shouldBe false
    }

    // endregion

    // region grace window

    @Test fun `a recent confirmation keeps the upgrade alive`() {
        UpgradeRepoGplay.isWithinGrace(lastProAt = 1_000L, windowMs = week, now = 1_000L + week - 1) shouldBe true
    }

    @Test fun `an expired confirmation does not`() {
        UpgradeRepoGplay.isWithinGrace(lastProAt = 1_000L, windowMs = week, now = 1_000L + week) shouldBe false
    }

    @Test fun `an install that was never confirmed gets no grace`() {
        UpgradeRepoGplay.isWithinGrace(lastProAt = 0L, windowMs = week, now = 1_000L) shouldBe false
    }

    @Test fun `a clock moved backwards does not grant an open-ended upgrade`() {
        // A bare `age < window` check accepts a negative age, which would hand the upgrade to anyone
        // who sets their device clock back. The next successful Play round-trip re-stamps a sane
        // anchor, so denying here costs a legitimate user nothing durable.
        UpgradeRepoGplay.isWithinGrace(lastProAt = 10_000L, windowMs = week, now = 9_999L) shouldBe false
        UpgradeRepoGplay.isWithinGrace(lastProAt = 10_000L, windowMs = week, now = 0L) shouldBe false
    }

    @Test fun `the one-time purchase gets the long window and the subscription the short one`() {
        // A permanent purchase should almost never be dropped on a Play hiccup; a subscription
        // legitimately lapses.
        (month > week) shouldBe true
        UpgradeRepoGplay.isWithinGrace(lastProAt = 1_000L, windowMs = month, now = 1_000L + week + 1) shouldBe true
        UpgradeRepoGplay.isWithinGrace(lastProAt = 1_000L, windowMs = week, now = 1_000L + week + 1) shouldBe false
    }

    // endregion

    // region preferred sku

    private fun purchased(vararg skus: Sku) = skus.map {
        PurchasedSku(it, TestPurchases.purchase(it.id, token = "token-${it.id}"))
    }

    @Test fun `the permanent purchase decides the grace class when both are owned`() {
        // Purchases are time-sorted, so a plain firstOrNull would pick whichever was bought last and
        // silently downgrade an owner's window from 30 days to 7.
        UpgradeRepoGplay.preferredProSku(
            purchased(OurSku.Sub.PRO_UPGRADE, OurSku.Iap.PRO_UPGRADE),
        ) shouldBe OurSku.Iap.PRO_UPGRADE
    }

    @Test fun `a lone subscription is its own preferred sku`() {
        UpgradeRepoGplay.preferredProSku(purchased(OurSku.Sub.PRO_UPGRADE)) shouldBe OurSku.Sub.PRO_UPGRADE
    }

    @Test fun `nothing owned has no preferred sku`() {
        UpgradeRepoGplay.preferredProSku(emptyList()) shouldBe null
    }

    // endregion

    @Test fun `the local-failure retry backs off and then caps`() {
        // Integer math on purpose: a Double-pow formula sleeps for hours and can overflow into a hot
        // loop at extreme attempt counts.
        UpgradeRepoGplay.retryDelayMs(0) shouldBe 30_000L
        UpgradeRepoGplay.retryDelayMs(1) shouldBe 60_000L
        UpgradeRepoGplay.retryDelayMs(2) shouldBe 120_000L
        UpgradeRepoGplay.retryDelayMs(3) shouldBe 240_000L
        UpgradeRepoGplay.retryDelayMs(4) shouldBe 300_000L
        UpgradeRepoGplay.retryDelayMs(50) shouldBe 300_000L
    }
}
