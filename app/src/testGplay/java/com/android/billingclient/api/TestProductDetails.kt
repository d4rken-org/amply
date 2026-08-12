package com.android.billingclient.api

import org.json.JSONArray
import org.json.JSONObject

/**
 * Builds real [ProductDetails] objects from the response JSON Play would send.
 *
 * It lives in Play's own package because [ProductDetails]' only constructor is package-private —
 * there is no public way to build one. That is worth the intrusion: the offer-matching and
 * price-selection logic under test navigates half a dozen nested accessors, and a mocking framework
 * would both stub away the very structure being exercised and, in this project, break the Robolectric
 * tests that share its JVM (its inline agent strips Robolectric's native bindings).
 */
object TestProductDetails {

    fun oneTimePurchase(
        productId: String,
        formattedPrice: String,
    ): ProductDetails = ProductDetails(
        JSONObject().apply {
            put("productId", productId)
            put("type", BillingClient.ProductType.INAPP)
            put("title", productId)
            put("name", productId)
            put("description", productId)
            put(
                "oneTimePurchaseOfferDetails",
                JSONObject().apply {
                    put("formattedPrice", formattedPrice)
                    put("priceAmountMicros", 9_990_000L)
                    put("priceCurrencyCode", "USD")
                },
            )
        }.toString(),
    )

    /** One offer row: a base plan, optionally an offer id, and the price of its final phase. */
    data class Offer(
        val basePlanId: String,
        val offerId: String?,
        val formattedPrice: String,
    )

    fun subscription(
        productId: String,
        offers: List<Offer>,
    ): ProductDetails = ProductDetails(
        JSONObject().apply {
            put("productId", productId)
            put("type", BillingClient.ProductType.SUBS)
            put("title", productId)
            put("name", productId)
            put("description", productId)
            put(
                "subscriptionOfferDetails",
                JSONArray(
                    offers.map { offer ->
                        JSONObject().apply {
                            put("basePlanId", offer.basePlanId)
                            offer.offerId?.let { put("offerId", it) }
                            put("offerIdToken", "token-${offer.basePlanId}-${offer.offerId}")
                            put(
                                "pricingPhases",
                                JSONArray(
                                    listOf(
                                        JSONObject().apply {
                                            put("billingPeriod", "P1Y")
                                            put("formattedPrice", offer.formattedPrice)
                                            put("priceAmountMicros", 4_990_000L)
                                            put("priceCurrencyCode", "USD")
                                            put("recurrenceMode", 1)
                                        },
                                    ),
                                ),
                            )
                        }
                    },
                ),
            )
        }.toString(),
    )
}
