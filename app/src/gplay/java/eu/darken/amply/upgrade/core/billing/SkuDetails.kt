package eu.darken.amply.upgrade.core.billing

import com.android.billingclient.api.ProductDetails

data class SkuDetails(
    val sku: Sku,
    val details: ProductDetails,
)
