package eu.darken.amply.upgrade.ui

import android.app.Activity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import eu.darken.amply.common.WebpageTool
import eu.darken.amply.common.debug.logging.Logging.Priority.INFO
import eu.darken.amply.common.debug.logging.Logging.Priority.WARN
import eu.darken.amply.common.debug.logging.asLog
import eu.darken.amply.common.debug.logging.log
import eu.darken.amply.common.debug.logging.logTag
import eu.darken.amply.common.flow.SingleEventFlow
import eu.darken.amply.upgrade.core.OurSku
import eu.darken.amply.upgrade.core.UpgradeRepoGplay
import eu.darken.amply.upgrade.core.billing.GplayServiceUnavailableException
import eu.darken.amply.upgrade.core.billing.OfferUnavailableBillingException
import eu.darken.amply.upgrade.core.billing.Sku
import eu.darken.amply.upgrade.core.billing.SkuDetails
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Duration
import javax.inject.Inject

@HiltViewModel
class UpgradeViewModel @Inject constructor(
    private val upgradeRepo: UpgradeRepoGplay,
    private val webpageTool: WebpageTool,
) : ViewModel() {

    val events = SingleEventFlow<UpgradeEvents>()

    private var hasShownRepoError: Boolean = false
    private var hasShownServiceUnavailableError: Boolean = false
    private var hasShownPartialQueryError: Boolean = false

    /**
     * Called once per visit. The ViewModel is activity-scoped and outlives the screen, so a new visit
     * must not resume the previous one's error episodes. [manage] marks the settings "upgrade status"
     * entry, whose audience is existing purchasers — it must never auto-dismiss under them.
     */
    fun onVisitStart(manage: Boolean) {
        log(TAG) { "onVisitStart(manage=$manage)" }
        hasShownRepoError = false
        hasShownServiceUnavailableError = false
        hasShownPartialQueryError = false
    }

    /** Drives the host's auto-dismiss: the acquisition pitch closes itself once the upgrade lands. */
    val isPro: StateFlow<Boolean> = upgradeRepo.upgradeInfo
        .map { it.isPro }
        .catch { e ->
            if (e is CancellationException) throw e
            emit(false)
        }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), false)

    // ONE arbiter for every entitlement action (both purchase paths and restore). They all talk to
    // the same Play account state, so two independent guards would let a subscription tap and a
    // restore tap run concurrent Play operations against each other.
    private val activeOp = MutableStateFlow<BusyOp?>(null)
    private val retryTrigger = MutableStateFlow(0)

    // Test seam: the diagnostics threshold compares wall-clock time, which coroutine test dispatchers
    // can't advance.
    internal var clock: () -> Long = { System.currentTimeMillis() }

    /**
     * The unconfirmed-episode stamp, re-emitted when the episode crosses the diagnostics threshold:
     * every other combined flow is distinct-until-changed and can stay silent across the 24h
     * boundary, which would otherwise leave a long-lived ViewModel stuck on the quiet stage.
     */
    private val graceStamp: Flow<Long> = upgradeRepo.proUnconfirmedSince
        .flatMapLatest { stamp ->
            flow {
                emit(stamp)
                if (stamp > 0L) {
                    val remaining = stamp + GRACE_DIAGNOSTICS_AFTER_MS - clock()
                    if (remaining > 0) {
                        delay(remaining)
                        emit(stamp)
                    }
                }
            }
        }

    // This ViewModel's own action wins; otherwise a launch started elsewhere (a previous instance
    // across a rotation — the launch outlives the ViewModel) or the repo's invisible already-owned
    // auto-restore still pauses the entitlement actions here.
    private val busyOp: Flow<BusyOp?> = combine(
        activeOp,
        upgradeRepo.purchaseLaunchSku,
        upgradeRepo.autoRestoreBusy,
    ) { vmOp, launchSku, isAutoRestoring ->
        vmOp
            ?: launchSku?.let { if (it is Sku.Subscription) BusyOp.SUBSCRIPTION else BusyOp.IAP }
            ?: BusyOp.RESTORE.takeIf { isAutoRestoring }
    }

    // One aggregate query per retry generation: both SKU lookups run concurrently and land in a
    // single Done, so the UI can never combine results from two different retry attempts.
    private sealed interface SkuQueries {
        data object Pending : SkuQueries
        data class Done(
            val iap: Result<Collection<SkuDetails>>,
            val sub: Result<Collection<SkuDetails>>,
        ) : SkuQueries
    }

    private val skuQueries: Flow<SkuQueries> = retryTrigger.flatMapLatest {
        flow {
            emit(SkuQueries.Pending)
            val done = coroutineScope {
                val iap = async { querySkuDetails(OurSku.Iap.PRO_UPGRADE) }
                val sub = async { querySkuDetails(OurSku.Sub.PRO_UPGRADE) }
                SkuQueries.Done(iap = iap.await(), sub = sub.await())
            }
            emit(done)
        }
    }

    private suspend fun querySkuDetails(sku: Sku): Result<Collection<SkuDetails>> = try {
        val details = withTimeoutOrNull(SKU_QUERY_TIMEOUT_MS) { upgradeRepo.querySkus(sku) }
            ?: throw GplayServiceUnavailableException(RuntimeException("SKU query timed out for ${sku.id}"))
        Result.success(details)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        log(TAG, WARN) { "querySkuDetails($sku) failed: ${e.asLog()}" }
        Result.failure(e)
    }

    internal val state: StateFlow<GplayUpgradeUiState> = combine(
        skuQueries,
        upgradeRepo.upgradeInfo,
        upgradeRepo.wasEverPro,
        graceStamp,
        busyOp,
    ) { queries, current, wasEverPro, proUnconfirmedSince, busy ->
        val ownership = current.toOwnership()
        // Pro without any owned purchase == grace. Stage 1 (quiet "still active" line) shows
        // immediately; the diagnostics + restore CTA only once the unconfirmed episode has aged past
        // the threshold, so self-healing Play blips never surface them.
        val grace = if (current.isPro && !ownership.ownsAnything) {
            GraceHint(
                showDiagnostics = proUnconfirmedSince > 0L &&
                    clock() - proUnconfirmedSince >= GRACE_DIAGNOSTICS_AFTER_MS,
            )
        } else {
            null
        }
        // Owners and grace users don't depend on offer prices: their status and management actions
        // render immediately and price problems are not their problem.
        val priceIndependent = ownership.ownsAnything || grace != null

        val done = queries as? SkuQueries.Done
        if (done == null) {
            // A new attempt starts a new error episode.
            hasShownServiceUnavailableError = false
            hasShownPartialQueryError = false
        }
        // Structural close: entitlement-dependent UI never renders from a pre-reconciliation Info —
        // even if fast SKU queries finish before the reconciled Info propagates, an unsettled owner
        // must not be flashed acquisition offers. Carve-out: a Done where BOTH fresh SKU queries
        // failed is itself a definitive can't-reach-Play outcome and may resolve without waiting for
        // the connect loop's failure signal.
        val bothQueriesFailed = done != null && done.iap.isFailure && done.sub.isFailure
        if (!current.isSettled && !bothQueriesFailed) return@combine GplayUpgradeUiState.Loading
        // Acquisition renders with prices; owners and grace users render their status immediately
        // without waiting for prices.
        if (done == null && !priceIndependent) return@combine GplayUpgradeUiState.Loading

        val iap = done?.iap?.getOrNull()
        val sub = done?.sub?.getOrNull()

        if (done != null) {
            if (iap == null && sub == null) {
                val iapCause = done.iap.exceptionOrNull()
                val subCause = done.sub.exceptionOrNull()
                // Play answered fine and simply has nothing to sell here (region, account
                // eligibility, pulled product): reporting that as a connectivity failure sends the
                // user chasing futile advice. Only when BOTH causes are merchandising — a single
                // connectivity failure can't rule out a real Play problem.
                val queryError = if (
                    iapCause is OfferUnavailableBillingException && subCause is OfferUnavailableBillingException
                ) {
                    iapCause
                } else {
                    GplayServiceUnavailableException(
                        iapCause ?: RuntimeException("IAP and SUB data request failed."),
                    )
                }
                // Grace users and owners are excluded: during an outage (exactly when grace matters)
                // they must keep the Loaded presentation with their status/grace card.
                if (!priceIndependent) {
                    // This combine re-runs on every upstream change — emit once per failure episode,
                    // not once per recombination.
                    if (!hasShownServiceUnavailableError) {
                        hasShownServiceUnavailableError = true
                        events.tryEmit(UpgradeEvents.Error(queryError))
                    }
                    return@combine GplayUpgradeUiState.Unavailable(queryError)
                }
            } else {
                hasShownServiceUnavailableError = false

                // Exactly one product type failed: show what's available, surface the failure once.
                // Not for owners/grace: price errors aren't their problem.
                val partialError = done.iap.exceptionOrNull() ?: done.sub.exceptionOrNull()
                if (partialError != null && !priceIndependent) {
                    if (!hasShownPartialQueryError) {
                        hasShownPartialQueryError = true
                        events.tryEmit(UpgradeEvents.Error(partialError))
                    }
                } else if (partialError == null) {
                    // Only a SUCCESS resets the flag. A priceIndependent user with a failed query
                    // must leave it untouched: it may already be true from before they became an
                    // owner, and resetting would re-emit the same episode if ownership lapses again.
                    hasShownPartialQueryError = false
                }
            }

            if (!current.isPro && current.error != null) {
                if (!hasShownRepoError) {
                    hasShownRepoError = true
                    current.error?.let { events.tryEmit(UpgradeEvents.Error(it)) }
                }
            } else {
                hasShownRepoError = false
            }

            // Diagnosability: distinguishes "Play withheld the trial offer" from "offer matching
            // failed" when users report a missing trial.
            sub?.firstOrNull()?.details?.subscriptionOfferDetails?.let { offers ->
                log(TAG) { "Subscription offers from Play: ${offers.map { "${it.basePlanId}/${it.offerId}" }}" }
            }
        }

        toLoadedState(
            iap = iap?.firstOrNull(),
            sub = sub?.firstOrNull(),
            ownership = ownership,
            grace = grace,
            wasPreviouslyPro = wasEverPro && !current.isPro,
            busy = busy,
            subscriptionPending = current.pending.any { it == OurSku.Sub.PRO_UPGRADE },
            iapPending = current.pending.any { it == OurSku.Iap.PRO_UPGRADE },
        )
    }
        .catch { e ->
            if (e is CancellationException) throw e
            log(TAG, WARN) { "Upgrade state failed: ${e.asLog()}" }
            emit(GplayUpgradeUiState.Unavailable(e))
        }
        // Lazily (not WhileSubscribed): keep the billing SKU queries cached for the ViewModel
        // lifetime, so backgrounding >5s and returning doesn't drop the offer cards back to Loading
        // and re-query.
        .stateIn(viewModelScope, SharingStarted.Lazily, GplayUpgradeUiState.Loading)

    // Re-runs the SKU queries after a full "Play unavailable" episode — without this, the Lazily
    // cached failure bricks the screen for the whole ViewModel lifetime.
    fun retrySkuQuery() {
        log(TAG) { "retrySkuQuery()" }
        retryTrigger.update { it + 1 }
    }

    // Returning to the screen is the user's own "try again": a transient Play outage would otherwise
    // leave the retry card up until it's tapped by hand. Only re-queries from the unavailable state —
    // a loaded or still-loading screen has nothing to retry.
    fun onResume() {
        log(TAG) { "onResume()" }
        if (state.value is GplayUpgradeUiState.Unavailable) retrySkuQuery()
    }

    // Acquires the single action slot. Rejects while ANY other entitlement action of this ViewModel
    // runs, and while the repo reports a Play launch in flight (which may belong to another instance
    // — the repo CAS remains the authoritative gate, this only avoids the pointless tap).
    private fun acquireOp(op: BusyOp): Boolean {
        upgradeRepo.purchaseLaunchSku.value?.let {
            log(TAG) { "$op ignored, a billing launch for $it is already in flight" }
            return false
        }
        if (!activeOp.compareAndSet(expect = null, update = op)) {
            log(TAG) { "$op ignored, ${activeOp.value} is already in progress" }
            return false
        }
        return true
    }

    fun onGoIap(activity: Activity) {
        log(TAG) { "onGoIap($activity)" }
        viewModelScope.launch {
            // Single-flight: repeated taps must not stack verifications or billing launches.
            if (!acquireOp(BusyOp.IAP)) return@launch
            try {
                // Hard gate against double-billing: verify against a FRESH SUBS-only query — the
                // replayed upgradeInfo can be stale or built from partial results. Fails closed: no
                // verified "not set to renew" (or no sub at all), no one-time purchase.
                val subscriptions = try {
                    withTimeoutOrNull(VERIFY_TIMEOUT_MS) { upgradeRepo.queryCurrentSubscriptions() }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    log(TAG, WARN) { "Subscription verification errored: ${e.asLog()}" }
                    events.tryEmit(UpgradeEvents.Error(e))
                    return@launch
                }
                when {
                    subscriptions == null -> {
                        log(TAG, WARN) { "Subscription verification timed out" }
                        events.tryEmit(UpgradeEvents.SubscriptionCheckFailed)
                    }

                    subscriptions.any { it.isAutoRenewing } -> {
                        log(TAG, INFO) { "IAP purchase blocked: subscription is still set to renew" }
                        events.tryEmit(UpgradeEvents.SubscriptionStillRenewing)
                    }

                    // Suspends until the Play sheet launch resolved, so the single-flight guard
                    // covers the whole tap-to-sheet window, not just the verification.
                    else -> upgradeRepo.launchBillingFlowNow(
                        activity,
                        OurSku.Iap.PRO_UPGRADE,
                        null,
                        onError = { events.tryEmit(UpgradeEvents.Error(it)) },
                    )
                }
            } finally {
                activeOp.value = null
            }
        }
    }

    fun onGoSubscription(activity: Activity) {
        log(TAG) { "onGoSubscription($activity)" }
        startSubPurchase(activity, OurSku.Sub.PRO_UPGRADE.BASE_OFFER)
    }

    fun onGoSubscriptionTrial(activity: Activity) {
        log(TAG) { "onGoSubscriptionTrial($activity)" }
        startSubPurchase(activity, OurSku.Sub.PRO_UPGRADE.TRIAL_OFFER)
    }

    private fun startSubPurchase(activity: Activity, offer: Sku.Subscription.Offer) {
        viewModelScope.launch {
            if (!acquireOp(BusyOp.SUBSCRIPTION)) return@launch
            try {
                // launchBillingFlowNow suspends until the launch resolved, so the guard covers the
                // whole tap-to-sheet window. The flow itself runs on the repo's own scope, so closing
                // the screen mid-launch doesn't abort the purchase.
                upgradeRepo.launchBillingFlowNow(
                    activity,
                    OurSku.Sub.PRO_UPGRADE,
                    offer,
                    onError = { events.tryEmit(UpgradeEvents.Error(it)) },
                )
            } finally {
                activeOp.value = null
            }
        }
    }

    fun onManageSubscription() {
        log(TAG) { "onManageSubscription()" }
        webpageTool.open(PLAY_SUBSCRIPTION_SITE)
    }

    fun restorePurchase() {
        viewModelScope.launch {
            // Single-flight: repeated taps while a restore is running must not stack concurrent
            // restores and duplicate result dialogs — and a restore must not run alongside a purchase.
            if (!acquireOp(BusyOp.RESTORE)) return@launch
            log(TAG) { "restorePurchase()" }

            try {
                // Minimum visible duration, not a fixed add-on: the pad runs CONCURRENTLY with the
                // real Play query, so a fast check gets stretched to a believable length while a slow
                // one gains nothing. A sub-second round-trip reads as "nothing was checked" and
                // undermines the result. Manual restores only; the repo's invisible auto-restore must
                // stay fast.
                val restored = coroutineScope {
                    val minVisible = async { delay(RESTORE_MIN_VISIBLE_MS) }
                    val result = withTimeoutOrNull(RESTORE_TIMEOUT_MS) { upgradeRepo.restorePurchaseNow() }
                    minVisible.await()
                    result
                }
                when {
                    restored == null -> {
                        // The budget covers connecting, the refresh mutex AND both queries, so a
                        // query may well have started. All we know is the check didn't finish — not
                        // that Play said no. Reporting this as a completed check would send an owner
                        // chasing the multi-account explanation for a slow or unreachable Play.
                        log(TAG, WARN) { "Restore purchase timed out" }
                        events.tryEmit(UpgradeEvents.RestoreInconclusive)
                    }

                    restored is UpgradeRepoGplay.RestoreOutcome.Inconclusive -> {
                        // Play errored and grace kept the upgrade alive. Same non-answer as a
                        // timeout, and the user is by definition a recent owner.
                        log(TAG, WARN) { "Restore purchase inconclusive: ${restored.cause.asLog()}" }
                        events.tryEmit(UpgradeEvents.RestoreInconclusive)
                    }

                    restored.info.upgrades.isNotEmpty() -> {
                        log(TAG, INFO) { "Restored purchase :))" }
                        // Explicit feedback: on the ownership screen a successful restore changes
                        // nothing visible, so silence reads as "broken".
                        events.tryEmit(UpgradeEvents.RestoreSucceeded)
                    }

                    else -> {
                        // Play answered and had nothing. Includes a grace-only result from a
                        // successful EMPTY query: the upgrade may still be active, but the check
                        // really did complete, so troubleshooting is warranted.
                        log(TAG, WARN) { "Restore purchase found no purchases (isPro=${restored.info.isPro})" }
                        events.tryEmit(UpgradeEvents.RestoreFailed)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Play/billing error (e.g. service unavailable): surface the proper error copy
                // instead of the generic "restore failed" message, so the two cases stay apart.
                log(TAG, WARN) { "Restore purchase errored: ${e.asLog()}" }
                events.tryEmit(UpgradeEvents.Error(e))
            } finally {
                // Reset only after result handling, so the single-flight guard covers the whole action.
                activeOp.value = null
            }
        }
    }

    companion object {
        private const val RESTORE_TIMEOUT_MS = 15_000L
        // Floor for how long a manual restore visibly runs (spinner up, result held back). Long
        // enough that the user believes a round-trip to Play happened, short enough not to drag.
        internal const val RESTORE_MIN_VISIBLE_MS = 1_500L
        private const val VERIFY_TIMEOUT_MS = 10_000L
        private const val STOP_TIMEOUT_MILLIS = 5_000L

        // The very first billing query after Play sign-in can take >8s while Play warms up — a 5s
        // budget produces false "Play unavailable" dialogs on slow-but-healthy stores.
        private const val SKU_QUERY_TIMEOUT_MS = 15_000L

        // How long a fresh-data-confirmed grace episode must last before the grace card shows its
        // diagnostics: long enough that self-healing Play blips stay invisible, short enough to leave
        // most of the 7-day subscription grace for the user to act in.
        internal val GRACE_DIAGNOSTICS_AFTER_MS: Long = Duration.ofHours(24).toMillis()

        // Play's management page for our subscription specifically; harmless without a matching sub
        // on the account (Play falls back to the general subscription list).
        internal const val PLAY_SUBSCRIPTION_SITE =
            "https://play.google.com/store/account/subscriptions" +
                "?sku=upgrade.pro&package=eu.darken.amply"

        private val TAG = logTag("Upgrade", "Gplay", "ViewModel")
    }
}
