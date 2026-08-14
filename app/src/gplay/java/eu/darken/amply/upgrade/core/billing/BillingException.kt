package eu.darken.amply.upgrade.core.billing

/**
 * Base for every billing failure we surface. The types stay meaningful all the way up; the mapping
 * to user-facing copy happens in the gplay UI layer, so nothing down here needs to know about
 * resources or localization.
 */
open class BillingException(
    override val message: String? = null,
    override val cause: Throwable? = null,
) : Exception()

/** The user closed the Play sheet. Not an error — dismissed silently. */
class UserCanceledBillingException(cause: Throwable) :
    BillingException("User canceled billing flow.", cause)

/** Google Play is not reachable: not installed, disabled, signed out, or simply not answering. */
class GplayServiceUnavailableException(cause: Throwable) :
    BillingException("Google Play services are unavailable.", cause)

class InternalBillingException(cause: Throwable) :
    BillingException("An internal Google Play error occurred.", cause)

class NetworkBillingException(cause: Throwable) :
    BillingException("Unable to connect to Google Play.", cause)

/** Play refuses the purchase because the account already owns it — the cue to restore instead. */
class ItemAlreadyOwnedBillingException(cause: Throwable) :
    BillingException("Item is already owned.", cause)

/**
 * Play can't sell us this product/offer right now: it was omitted from the product-details response,
 * came back ambiguous (duplicate rows), or is reported as unavailable.
 *
 * This is a merchandising state (region, account eligibility, a withheld or revoked offer), NOT a
 * defect on our side — so it gets its own type and its own user-facing copy instead of the raw
 * NoSuchElementException a strict `single` lookup would produce.
 */
class OfferUnavailableBillingException(
    val sku: Sku,
    val offer: Sku.Subscription.Offer?,
) : BillingException(
    "Google Play has no usable offer for ${sku.print()} (offer=${offer?.let { "${it.basePlanId}/${it.offerId}" }})",
)
