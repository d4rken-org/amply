package eu.darken.amply.upgrade.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import eu.darken.amply.R
import eu.darken.amply.upgrade.core.billing.GplayServiceUnavailableException
import eu.darken.amply.upgrade.core.billing.InternalBillingException
import eu.darken.amply.upgrade.core.billing.ItemAlreadyOwnedBillingException
import eu.darken.amply.upgrade.core.billing.NetworkBillingException
import eu.darken.amply.upgrade.core.billing.OfferUnavailableBillingException
import eu.darken.amply.upgrade.core.billing.UserCanceledBillingException

/**
 * Billing failures stay *typed* all the way down — the core layer knows nothing about resources. This
 * is the single place that turns a type into copy, so a new exception can't silently fall through to
 * a raw stack-trace message.
 */
internal data class UpgradeErrorCopy(
    val title: String,
    val message: String,
)

/** null when the failure needs no dialog at all: the user closing Play's sheet is not an error. */
@Composable
internal fun upgradeErrorCopy(error: Throwable): UpgradeErrorCopy? = when (error) {
    is UserCanceledBillingException -> null
    is GplayServiceUnavailableException -> UpgradeErrorCopy(
        title = stringResource(R.string.upgrade_error_unavailable_title),
        message = stringResource(R.string.upgrade_error_unavailable_message),
    )
    is InternalBillingException -> UpgradeErrorCopy(
        title = stringResource(R.string.upgrade_error_internal_title),
        message = stringResource(R.string.upgrade_error_internal_message),
    )
    is NetworkBillingException -> UpgradeErrorCopy(
        title = stringResource(R.string.upgrade_error_network_title),
        message = stringResource(R.string.upgrade_error_network_message),
    )
    is OfferUnavailableBillingException -> UpgradeErrorCopy(
        title = stringResource(R.string.upgrade_error_offer_unavailable_title),
        message = stringResource(R.string.upgrade_error_offer_unavailable_message),
    )
    is ItemAlreadyOwnedBillingException -> UpgradeErrorCopy(
        title = stringResource(R.string.upgrade_error_already_owned_title),
        message = stringResource(R.string.upgrade_error_already_owned_message),
    )
    else -> UpgradeErrorCopy(
        title = stringResource(R.string.upgrade_error_generic_title),
        message = stringResource(
            R.string.upgrade_error_generic_message,
            error.message ?: error::class.simpleName.orEmpty(),
        ),
    )
}
