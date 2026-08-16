package eu.darken.amply.upgrade.core.billing.client

import android.app.Activity
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.AcknowledgePurchaseResponseListener
import com.android.billingclient.api.AlternativeBillingOnlyAvailabilityListener
import com.android.billingclient.api.AlternativeBillingOnlyInformationDialogListener
import com.android.billingclient.api.AlternativeBillingOnlyReportingDetailsListener
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClient.BillingResponseCode
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingConfigResponseListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingProgramAvailabilityListener
import com.android.billingclient.api.BillingProgramReportingDetailsListener
import com.android.billingclient.api.BillingProgramReportingDetailsParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.ConsumeParams
import com.android.billingclient.api.ConsumeResponseListener
import com.android.billingclient.api.ExternalOfferAvailabilityListener
import com.android.billingclient.api.ExternalOfferInformationDialogListener
import com.android.billingclient.api.ExternalOfferReportingDetailsListener
import com.android.billingclient.api.GetBillingConfigParams
import com.android.billingclient.api.InAppMessageParams
import com.android.billingclient.api.InAppMessageResponseListener
import com.android.billingclient.api.LaunchExternalLinkParams
import com.android.billingclient.api.LaunchExternalLinkResponseListener
import com.android.billingclient.api.ProductDetailsResponseListener
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PurchasesResponseListener
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams

/**
 * A scriptable stand-in for Play's client, so tests can drive the REAL [BillingConnection] (its
 * reducer, its full-snapshot decision and its partial-failure provenance) instead of asserting
 * against a hand-written imitation of it.
 *
 * Only the calls the billing stack actually makes are implemented; everything else fails loudly, so
 * a future code path that starts using one can't be answered with a silent default.
 */
internal class FakeBillingClient : BillingClient() {

    /** What Play answers per product type ([ProductType.INAPP] / [ProductType.SUBS]). */
    data class Answer(
        val responseCode: Int = BillingResponseCode.OK,
        val purchases: List<Purchase> = emptyList(),
    )

    var answers: Map<String, Answer> = emptyMap()

    /** Purchase tokens this client was asked to acknowledge, in call order. */
    val acknowledged = mutableListOf<String>()

    var acknowledgeResponseCode: Int = BillingResponseCode.OK

    /**
     * Runs with the queried product type just before Play answers it. The seam for the races that
     * decide the reducer's ordering rules: a purchase event delivered from here lands AFTER the query
     * started, which is the only way it survives that query's commit.
     */
    var beforeAnswer: ((String) -> Unit)? = null

    override fun queryPurchasesAsync(params: QueryPurchasesParams, listener: PurchasesResponseListener) {
        // zza() is the only accessor Play exposes for the requested product type; without it a fake
        // could not answer the two concurrent per-type queries differently, which is exactly what
        // the partial-refresh behaviour is about.
        val type = params.zza()
        beforeAnswer?.invoke(type)
        val answer = answers[type] ?: Answer()
        listener.onQueryPurchasesResponse(result(answer.responseCode), answer.purchases)
    }

    override fun acknowledgePurchase(
        params: AcknowledgePurchaseParams,
        listener: AcknowledgePurchaseResponseListener,
    ) {
        acknowledged.add(params.purchaseToken)
        listener.onAcknowledgePurchaseResponse(result(acknowledgeResponseCode))
    }

    override fun isReady(): Boolean = true

    override fun getConnectionState(): Int = ConnectionState.CONNECTED

    override fun endConnection() = Unit

    override fun startConnection(listener: BillingClientStateListener) =
        listener.onBillingSetupFinished(result(BillingResponseCode.OK))

    override fun isFeatureSupported(feature: String): BillingResult = result(BillingResponseCode.OK)

    override fun launchBillingFlow(activity: Activity, params: BillingFlowParams): BillingResult =
        unsupported("launchBillingFlow")

    override fun queryProductDetailsAsync(
        params: QueryProductDetailsParams,
        listener: ProductDetailsResponseListener,
    ): Unit = unsupported("queryProductDetailsAsync")

    override fun consumeAsync(params: ConsumeParams, listener: ConsumeResponseListener): Unit =
        unsupported("consumeAsync")

    override fun getBillingConfigAsync(
        params: GetBillingConfigParams,
        listener: BillingConfigResponseListener,
    ): Unit = unsupported("getBillingConfigAsync")

    override fun createAlternativeBillingOnlyReportingDetailsAsync(
        listener: AlternativeBillingOnlyReportingDetailsListener,
    ): Unit = unsupported("createAlternativeBillingOnlyReportingDetailsAsync")

    override fun isAlternativeBillingOnlyAvailableAsync(
        listener: AlternativeBillingOnlyAvailabilityListener,
    ): Unit = unsupported("isAlternativeBillingOnlyAvailableAsync")

    override fun showAlternativeBillingOnlyInformationDialog(
        activity: Activity,
        listener: AlternativeBillingOnlyInformationDialogListener,
    ): BillingResult = unsupported("showAlternativeBillingOnlyInformationDialog")

    override fun createExternalOfferReportingDetailsAsync(
        listener: ExternalOfferReportingDetailsListener,
    ): Unit = unsupported("createExternalOfferReportingDetailsAsync")

    override fun isExternalOfferAvailableAsync(listener: ExternalOfferAvailabilityListener): Unit =
        unsupported("isExternalOfferAvailableAsync")

    override fun showExternalOfferInformationDialog(
        activity: Activity,
        listener: ExternalOfferInformationDialogListener,
    ): BillingResult = unsupported("showExternalOfferInformationDialog")

    override fun createBillingProgramReportingDetailsAsync(
        params: BillingProgramReportingDetailsParams,
        listener: BillingProgramReportingDetailsListener,
    ): Unit = unsupported("createBillingProgramReportingDetailsAsync")

    override fun isBillingProgramAvailableAsync(
        billingProgram: Int,
        listener: BillingProgramAvailabilityListener,
    ): Unit = unsupported("isBillingProgramAvailableAsync")

    override fun launchExternalLink(
        activity: Activity,
        params: LaunchExternalLinkParams,
        listener: LaunchExternalLinkResponseListener,
    ): Unit = unsupported("launchExternalLink")

    override fun showInAppMessages(
        activity: Activity,
        params: InAppMessageParams,
        listener: InAppMessageResponseListener,
    ): BillingResult = unsupported("showInAppMessages")

    private fun unsupported(call: String): Nothing = error("FakeBillingClient.$call() is not scripted")

    companion object {
        fun result(responseCode: Int): BillingResult = BillingResult.newBuilder().apply {
            setResponseCode(responseCode)
            setDebugMessage("fake")
        }.build()
    }
}
