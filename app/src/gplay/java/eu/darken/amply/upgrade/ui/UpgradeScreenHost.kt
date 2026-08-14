package eu.darken.amply.upgrade.ui

import android.app.Activity
import android.widget.Toast
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import eu.darken.amply.R

/**
 * The Google Play upgrade destination. Same fully-qualified name as the FOSS host, so the composition
 * root in `src/main` can render "the upgrade screen" without knowing which flavor it was built for.
 *
 * @param manage true for the settings "upgrade status" entry, whose audience is existing purchasers —
 *   it must never auto-dismiss under them.
 */
@Composable
fun UpgradeScreenHost(
    manage: Boolean,
    onBack: () -> Unit,
) {
    val vm: UpgradeViewModel = viewModel()
    val context = LocalContext.current
    val activity = context as? Activity

    LaunchedEffect(Unit) { vm.onVisitStart(manage) }

    // The app-level resume refresh only covers the entitlement, never this screen's own SKU query —
    // so a transient Play outage would leave the retry card up until it's tapped by hand.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.onResume() }

    // rememberSaveable, not remember: these are driven by one-shot events that are already consumed
    // from the flow, so a rotation while a dialog is up would drop it for good.
    var showRestoreFailed by rememberSaveable { mutableStateOf(false) }
    var showRestoreInconclusive by rememberSaveable { mutableStateOf(false) }
    var showStillRenewing by rememberSaveable { mutableStateOf(false) }
    var showCheckFailed by rememberSaveable { mutableStateOf(false) }
    // Plain remember, unlike its siblings: a Throwable is only nominally Serializable — our billing
    // exceptions carry non-serializable payloads (a Sku, a BillingResult), so saving one would crash
    // on the very rotation it is meant to survive. Losing an error dialog is the cheaper failure.
    var error by remember { mutableStateOf<Throwable?>(null) }

    val uiState by vm.state.collectAsStateWithLifecycle()
    val isPro by vm.isPro.collectAsStateWithLifecycle()

    // The acquisition pitch is a request to upgrade: once the upgrade lands, the request is answered
    // and the user goes back to what they were doing. The manage entry is the destination itself.
    LaunchedEffect(isPro, manage) {
        if (isPro && !manage) onBack()
    }

    LaunchedEffect(vm) {
        vm.events.collect { event ->
            when (event) {
                UpgradeEvents.RestoreSucceeded -> Toast.makeText(
                    context,
                    context.getString(R.string.upgrade_screen_restore_success_message),
                    Toast.LENGTH_LONG,
                ).show()

                UpgradeEvents.RestoreFailed -> showRestoreFailed = true
                UpgradeEvents.RestoreInconclusive -> showRestoreInconclusive = true
                UpgradeEvents.SubscriptionStillRenewing -> showStillRenewing = true
                UpgradeEvents.SubscriptionCheckFailed -> showCheckFailed = true
                is UpgradeEvents.Error -> error = event.error
            }
        }
    }

    if (showRestoreFailed) {
        RestoreFailedDialog(
            // A payment Play is still processing is the single most likely explanation for an empty
            // restore, and it is the one where the user should do nothing at all.
            hasPendingPayment = (uiState as? GplayUpgradeUiState.Loaded)?.anyPending == true,
            onDismiss = { showRestoreFailed = false },
        )
    }

    if (showRestoreInconclusive) {
        RestoreInconclusiveDialog(
            onRetry = {
                showRestoreInconclusive = false
                vm.restorePurchase()
            },
            onDismiss = { showRestoreInconclusive = false },
        )
    }

    if (showStillRenewing) {
        AlertDialog(
            onDismissRequest = { showStillRenewing = false },
            title = { Text(stringResource(R.string.upgrade_screen_sub_still_renewing_title)) },
            text = { Text(stringResource(R.string.upgrade_screen_sub_still_renewing_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showStillRenewing = false
                        vm.onManageSubscription()
                    },
                ) {
                    Text(stringResource(R.string.upgrade_screen_manage_subscription_action))
                }
            },
            dismissButton = {
                TextButton(onClick = { showStillRenewing = false }) {
                    Text(stringResource(R.string.upgrade_dismiss_action))
                }
            },
        )
    }

    if (showCheckFailed) {
        AlertDialog(
            onDismissRequest = { showCheckFailed = false },
            text = { Text(stringResource(R.string.upgrade_screen_sub_check_failed_message)) },
            confirmButton = {
                TextButton(onClick = { showCheckFailed = false }) {
                    Text(stringResource(R.string.upgrade_dismiss_action))
                }
            },
        )
    }

    error?.let { current ->
        val copy = upgradeErrorCopy(current)
        if (copy == null) {
            // A cancelled purchase sheet is not an error; clear it instead of showing an empty dialog.
            LaunchedEffect(current) { error = null }
        } else {
            AlertDialog(
                onDismissRequest = { error = null },
                title = { Text(copy.title) },
                text = { Text(copy.message) },
                confirmButton = {
                    TextButton(onClick = { error = null }) {
                        Text(stringResource(R.string.upgrade_dismiss_action))
                    }
                },
            )
        }
    }

    UpgradeScreen(
        uiState = uiState,
        onIap = { activity?.let { vm.onGoIap(it) } },
        onSubscription = { activity?.let { vm.onGoSubscription(it) } },
        onSubscriptionTrial = { activity?.let { vm.onGoSubscriptionTrial(it) } },
        onRestore = vm::restorePurchase,
        onManageSubscription = vm::onManageSubscription,
        onRetry = vm::retrySkuQuery,
        onNavigateUp = onBack,
    )
}

/**
 * Shown when Play answered and no purchase was found. Leads with the just-happened live check, which
 * is literally true here: non-answers route to [RestoreInconclusiveDialog] instead.
 */
@Composable
internal fun RestoreFailedDialog(
    hasPendingPayment: Boolean = false,
    onDismiss: () -> Unit = {},
) {
    val checkedMsg = stringResource(R.string.upgrade_screen_restore_checked_message)
    val pendingHint = stringResource(R.string.upgrade_screen_restore_pending_hint)
    val multiAccountHint = stringResource(R.string.upgrade_screen_restore_multiaccount_hint)
    val syncHint = stringResource(R.string.upgrade_screen_restore_sync_patience_hint)
    val message = listOfNotNull(
        checkedMsg,
        pendingHint.takeIf { hasPendingPayment },
        multiAccountHint,
        syncHint,
    ).joinToString("\n\n")
    AlertDialog(
        onDismissRequest = onDismiss,
        text = { Text(message) },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.upgrade_dismiss_action))
            }
        },
    )
}

/**
 * Shown when the restore never got an answer (timeout, or a Play error absorbed by grace). Carries no
 * multi-account hint: nothing was established, so it would be premature. Retry is the useful move,
 * and `restorePurchase()` is single-flight.
 */
@Composable
internal fun RestoreInconclusiveDialog(
    onRetry: () -> Unit = {},
    onDismiss: () -> Unit = {},
) {
    val inconclusiveMsg = stringResource(R.string.upgrade_screen_restore_inconclusive_message)
    val syncHint = stringResource(R.string.upgrade_screen_restore_sync_patience_hint)
    AlertDialog(
        onDismissRequest = onDismiss,
        text = { Text("$inconclusiveMsg\n\n$syncHint") },
        confirmButton = {
            TextButton(onClick = onRetry) {
                Text(stringResource(R.string.upgrade_retry_action))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.upgrade_dismiss_action))
            }
        },
    )
}
