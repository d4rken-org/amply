package eu.darken.amply.upgrade.core.billing

import com.android.billingclient.api.Purchase
import eu.darken.amply.upgrade.core.billing.client.redacted

data class PurchasedSku(val sku: Sku, val purchase: Purchase) {
    // Purchase.toString() dumps the original response JSON, which carries the purchase token and the
    // order ID; redacted() keeps those out of debug recordings while retaining the diagnostic fields.
    override fun toString(): String = "PurchasedSku(sku=$sku, purchase=${purchase.redacted()})"
}
