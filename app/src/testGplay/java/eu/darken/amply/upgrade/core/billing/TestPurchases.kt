package eu.darken.amply.upgrade.core.billing

import com.android.billingclient.api.Purchase
import org.json.JSONArray
import org.json.JSONObject

/**
 * Real [Purchase] objects built from the response JSON Play would send. Preferred over mocks: the
 * reducer and the entitlement mapping read a dozen derived properties, and a mock that stubs the
 * three a test happens to touch would keep passing after a property's meaning changed.
 *
 * Needs an Android runtime because [Purchase] parses its JSON with `org.json`.
 */
internal object TestPurchases {

    /** Play's raw purchase-state code for a payment it has not completed yet. */
    private const val RAW_STATE_PENDING = 4
    private const val RAW_STATE_PURCHASED = 0

    fun purchase(
        productId: String,
        token: String = "token-$productId",
        purchaseTime: Long = 1_000L,
        acknowledged: Boolean = true,
        autoRenewing: Boolean = false,
        pending: Boolean = false,
    ): Purchase = Purchase(
        JSONObject().apply {
            put("productIds", JSONArray(listOf(productId)))
            put("purchaseToken", token)
            put("purchaseTime", purchaseTime)
            put("purchaseState", if (pending) RAW_STATE_PENDING else RAW_STATE_PURCHASED)
            put("acknowledged", acknowledged)
            put("autoRenewing", autoRenewing)
            put("orderId", "order-$token")
        }.toString(),
        "test-signature",
    )
}
