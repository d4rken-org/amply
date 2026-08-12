package eu.darken.amply.upgrade.core.billing

import com.android.billingclient.api.Purchase
import com.android.billingclient.api.Purchase.PurchaseState

/**
 * What Play says this account owns.
 *
 * [purchases] holds **only** PURCHASED items — everything that decides entitlement, drives the
 * acknowledgement pass, or stamps the grace window reads this and nothing else.
 *
 * [pendingPurchases] is carried separately rather than dropped. A pending purchase (cash payment,
 * parental approval) grants nothing and must never be acknowledged — acknowledging it would be a
 * protocol error — but the UI still needs to know it exists: without it, the offer row stays enabled
 * and the user is invited to buy something they are already paying for, and a restore that finds
 * nothing looks like a lost purchase rather than one still in flight.
 */
data class BillingData(
    val purchases: Collection<Purchase>,
    val pendingPurchases: Collection<Purchase> = emptyList(),
)

internal fun Collection<Purchase>.purchased(): List<Purchase> =
    filter { it.purchaseState == PurchaseState.PURCHASED }

internal fun Collection<Purchase>.pending(): List<Purchase> =
    filter { it.purchaseState == PurchaseState.PENDING }

/** Splits a raw Play purchase list into the entitlement-bearing and the payment-pending halves. */
internal fun Collection<Purchase>.toBillingData(): BillingData = BillingData(
    purchases = purchased(),
    pendingPurchases = pending(),
)
