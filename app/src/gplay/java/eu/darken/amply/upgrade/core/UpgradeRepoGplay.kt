package eu.darken.amply.upgrade.core

import android.app.Activity
import com.android.billingclient.api.BillingClient.BillingResponseCode
import com.android.billingclient.api.Purchase
import eu.darken.amply.common.debug.logging.Logging.Priority.ERROR
import eu.darken.amply.common.debug.logging.Logging.Priority.INFO
import eu.darken.amply.common.debug.logging.Logging.Priority.VERBOSE
import eu.darken.amply.common.debug.logging.Logging.Priority.WARN
import eu.darken.amply.common.debug.logging.asLog
import eu.darken.amply.common.debug.logging.log
import eu.darken.amply.common.debug.logging.logTag
import eu.darken.amply.upgrade.core.billing.BillingData
import eu.darken.amply.upgrade.core.billing.BillingManager
import eu.darken.amply.upgrade.core.billing.GplayServiceUnavailableException
import eu.darken.amply.upgrade.core.billing.ItemAlreadyOwnedBillingException
import eu.darken.amply.upgrade.core.billing.PurchasedSku
import eu.darken.amply.upgrade.core.billing.Sku
import eu.darken.amply.upgrade.core.billing.SkuDetails
import eu.darken.amply.upgrade.core.billing.UserCanceledBillingException
import eu.darken.amply.upgrade.core.billing.client.redacted
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.flow.runningReduce
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Duration
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class UpgradeRepoGplay @Inject constructor(
    private val billingManager: BillingManager,
    private val billingCache: BillingCache,
) : UpgradeRepo {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override val storeSite: String = STORE_SITE
    override val upgradeSite: String = UPGRADE_SITE
    override val betaSite: String = BETA_SITE

    // Coalescing single-flight for the invisible already-owned recoveries: overlapping triggers
    // (async Play event racing a buy tap's launch result) join the SAME restore instead of stacking
    // concurrent Play queries. Busy state is exact because at most one job runs.
    private val autoRestoreLock = Mutex()
    private var autoRestoreJob: Deferred<Info?>? = null
    private val autoRestoreState = MutableStateFlow(false)

    // The already-owned auto-restores run invisibly on our own scope; expose their busy state so the
    // UI can pause entitlement actions instead of racing them with a manual restore or a buy.
    val autoRestoreBusy: Flow<Boolean> = autoRestoreState

    // Process-wide single-flight for Play launches: the launch outlives the ViewModel that started
    // it, so a ViewModel-level guard alone lets a rotation (or a second screen) start a competing
    // purchase flow. Holds the SKU being launched, null while idle.
    private val launchBusySku = MutableStateFlow<Sku?>(null)

    // Which purchase launch (if any) is currently in flight, so the UI can present the busy state
    // even for a launch a previous ViewModel instance started.
    val purchaseLaunchSku: StateFlow<Sku?> = launchBusySku

    // Test seams: these bounds run on real dispatchers, so a virtual-time test cannot advance them.
    internal var launchTimeoutMs: Long = LAUNCH_TIMEOUT_MS
    internal var refreshTimeoutMs: Long = REFRESH_TIMEOUT_MS
    internal var restoreOnOwnedTimeoutMs: Long = RESTORE_ON_OWNED_TIMEOUT_MS

    // Serializes the pro-state recorders: the fresh-data collector and the failure paths in
    // refresh()/restorePurchaseNow() can run concurrently.
    private val proStateLock = Mutex()

    init {
        // Grace bookkeeping is driven by *fresh* Play data only: freshBillingData emissions each
        // represent an actual Play round-trip — never the replayed billingData/upgradeInfo flows,
        // whose old data must not keep re-stamping the grace window (e.g. after a refund).
        billingManager.freshBillingData
            .onEach { fresh ->
                try {
                    recordProState(fresh)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // A failed DataStore write must not kill this process-lifetime collector.
                    log(TAG, WARN) { "Failed to record pro state: ${e.asLog()}" }
                }
            }
            .launchIn(scope)

        // Async variant of the launch-result ITEM_ALREADY_OWNED case: Play told us mid-flow that the
        // user already owns it. Reconcile silently — Play shows its own UI for purchase-sheet
        // failures, so no app-side dialog here.
        billingManager.purchaseFailures
            .filter { it.responseCode == BillingResponseCode.ITEM_ALREADY_OWNED }
            .onEach {
                log(TAG, INFO) { "Async already-owned event -> restoring purchase" }
                autoRestore()
            }
            .launchIn(scope)

        // Connect-loop failures never reach an explicit refresh() caller (the loop retries internally
        // and downstream flows just go quiet), so without this a sustained Play outage between
        // resumes wouldn't advance the grace episode clock. The emitted value is the failure's
        // occurrence time, so a buffered failure that a later success already superseded is dropped
        // rather than reopening a closed episode.
        billingManager.connectionFailures
            .onEach { failedAt -> recordProUnconfirmed(failedAt) }
            .launchIn(scope)
    }

    /**
     * Settledness travels WITH the ownership data ([Info.isSettled]), never on a parallel flow — a
     * parallel signal could be observed out of step and pair "settled" with a stale non-Pro seed for
     * one emission.
     *
     * Per emission: `data != null` means a COMMITTED Play round-trip happened by construction
     * (`BillingConnection.purchases` only emits after `refreshPurchases` committed under the reducer
     * lock, and the manager only publishes the connection after that refresh succeeded). Committed,
     * not necessarily complete — `combinePurchaseResults` tolerates one failed product type when the
     * other returned a purchase, so a partial snapshot also settles (grace covers a recently
     * confirmed purchase whose type failed). A null seed settles only via `isFailureSettled`: Play is
     * unreachable, so seed + grace mapping IS the best knowledge.
     */
    override val upgradeInfo: Flow<Info> = combine(
        billingManager.billingData
            .map<BillingData, BillingData?> { it }
            .onStart { emit(null) },
        billingManager.isFailureSettled,
    ) { data, failureSettled -> data to (data != null || failureSettled) }
        .map { (data, settled) -> data.toUpgradeInfo(settled = settled) }
        .distinctUntilChanged()
        .retryWhen { error, attempt ->
            if (error is CancellationException) return@retryWhen false
            // Billing connection errors can no longer reach this flow (the connect loop retries them
            // internally) — what CAN fail here are the LOCAL DataStore reads in the mapping,
            // plausible exactly when storage is full. Keep the flow alive and keep a recently-Pro
            // user in their grace window.
            log(TAG, WARN) { "upgradeInfo mapping failed (attempt=$attempt): ${error.asLog()}" }
            // Fallbacks are settled: a local storage failure is a definitive best-knowledge outcome —
            // gates must resolve now, not stall out a 30s+ retry backoff.
            val fallback = try {
                val snapshot = billingCache.snapshot()
                if (isWithinGrace(snapshot.lastProAt, graceWindowMs(snapshot.lastProSku))) {
                    Info(gracePeriod = true, billingData = null, isSettled = true)
                } else {
                    Info(billingData = null, error = error, isSettled = true)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // The grace probe reads the same storage that just failed — a second failure must not
                // kill the retry loop that exists for exactly this situation.
                Info(billingData = null, error = error, isSettled = true)
            }
            emit(fallback)
            delay(retryDelayMs(attempt))
            true
        }
        // Settledness is monotonic within a subscription span: the retry above resubscribes the
        // upstream, whose onStart re-emits the null seed — without this latch, that seed would
        // regress an already-settled stream back to unsettled until the billing replay lands.
        .runningReduce { acc, next ->
            if (acc.isSettled && !next.isSettled) next.copy(isSettled = true) else next
        }
        .shareIn(scope, SharingStarted.WhileSubscribed(3000L, 0L), replay = 1)

    /**
     * True once we've ever confirmed a (known) purchase on this install; drives the proactive restore
     * banner. Local signal only — a fresh install or switched Google account starts false.
     *
     * Fail-soft, and that matters more here than the value itself: this is combined into the whole
     * upgrade-screen state, so a full-disk DataStore would otherwise take the entire screen down over
     * a decoration. Entitlement does not depend on it — [upgradeInfo] reads the cache on its own
     * retrying path.
     */
    val wasEverPro: Flow<Boolean> = billingCache.lastProStateAt
        .map { it > 0 }
        .catch { e ->
            if (e is CancellationException) throw e
            log(TAG, WARN) { "wasEverPro read failed: ${e.asLog()}" }
            emit(false)
        }
        .distinctUntilChanged()

    /**
     * Epoch millis of the first fresh reconciliation that couldn't confirm a purchase in the current
     * grace episode (0 = none). The upgrade screen delays its grace diagnostics until this has aged,
     * so self-healing Play blips never surface it.
     */
    val proUnconfirmedSince: Flow<Long> = billingCache.proUnconfirmedSince
        .catch { e ->
            if (e is CancellationException) throw e
            log(TAG, WARN) { "proUnconfirmedSince read failed: ${e.asLog()}" }
            emit(0L)
        }
        .distinctUntilChanged()

    /**
     * Suspends until the Play launch resolved (sheet up, or failed) — callers holding an in-progress
     * guard stay guarded through the launch. Still runs on OUR scope: the purchase flow and the
     * already-owned recovery must survive the upgrade screen being closed, so caller cancellation
     * only abandons the await.
     */
    suspend fun launchBillingFlowNow(
        activity: Activity,
        sku: Sku,
        offer: Sku.Subscription.Offer?,
        onError: (Throwable) -> Unit,
    ) {
        scope.async { launchBillingFlowInternal(activity, sku, offer, onError) }.await()
    }

    private suspend fun launchBillingFlowInternal(
        activity: Activity,
        sku: Sku,
        offer: Sku.Subscription.Offer?,
        onError: (Throwable) -> Unit,
    ) {
        log(TAG) { "launchBillingFlow($activity,$sku)" }
        // Silent coalesce, no error event: a second tap (or a second ViewModel instance after a
        // rotation) must not open a competing Play sheet. Cleared in the finally below.
        if (!launchBusySku.compareAndSet(null, sku)) {
            log(TAG, WARN) { "Billing launch already in flight, ignoring" }
            return
        }
        try {
            // Bounded, like every other Play path. useConnection waits for a healthy connection
            // indefinitely, so a Play outage between rendering the offers and this tap would park the
            // launch forever — with launchBusySku still held, which leaves every purchase button busy
            // and never surfaces an error. Generous on purpose: a cold Play can take >8s just for the
            // SKU query this launch does first.
            withTimeoutOrNull(launchTimeoutMs) {
                billingManager.startIapFlow(activity, sku, offer)
            } ?: throw GplayServiceUnavailableException(
                RuntimeException("Billing flow launch timed out for ${sku.id}"),
            )
        } catch (e: CancellationException) {
            // Not an error: must not reach onError (spurious dialog).
            throw e
        } catch (e: Exception) {
            when {
                e is UserCanceledBillingException -> log(TAG) { "User canceled billing flow" }

                e is ItemAlreadyOwnedBillingException -> {
                    // Stale local state: Play says they already own it, so tapping "buy" really means
                    // "unlock what I own" — restore instead of showing an error.
                    log(TAG, INFO) { "Launch says already owned -> restoring purchase" }
                    val restored = autoRestore()
                    // Reconciled only if the restore actually returned the SKU Play claims is owned —
                    // a grace-only isPro doesn't count, the entitlement is still missing.
                    if (restored?.upgrades?.any { it.sku == sku } != true) {
                        onError(e)
                    }
                }

                else -> {
                    log(TAG) { "startIapFlow failed:${e.asLog()}" }
                    onError(e)
                }
            }
        } finally {
            // Released on ANY termination, including the already-owned recovery path and caller
            // cancellation: a launch that is over must not block the next one.
            launchBusySku.value = null
        }
    }

    /**
     * Bounded, silent restore for the already-owned recovery paths. Coalescing: a trigger that
     * arrives while one is running awaits the running one. Returns null when the restore failed or
     * timed out — never throws (except cancellation of the AWAITING caller).
     */
    private suspend fun autoRestore(): Info? {
        val job = autoRestoreLock.withLock {
            autoRestoreJob?.takeIf { it.isActive } ?: scope.async {
                autoRestoreState.value = true
                try {
                    // Provenance is irrelevant here: the caller only checks whether the claimed SKU
                    // came back, and a grace-only result doesn't reconcile it either way.
                    withTimeoutOrNull(restoreOnOwnedTimeoutMs) { restorePurchaseNow().info }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    log(TAG, WARN) { "Already-owned restore failed: ${e.asLog()}" }
                    null
                } finally {
                    autoRestoreState.value = false
                }
            }.also { autoRestoreJob = it }
        }
        return job.await()
    }

    suspend fun querySkus(vararg skus: Sku): Collection<SkuDetails> = billingManager.querySkus(*skus)

    /**
     * Strict subscription lookup for the pre-purchase gate: fresh SUBS-only query with explicit
     * failure. No grace substitution and no cross-product-type tolerance — callers must treat any
     * error as "couldn't verify" and fail closed.
     */
    suspend fun queryCurrentSubscriptions(): Collection<Purchase> {
        log(TAG) { "queryCurrentSubscriptions()" }
        return billingManager.querySubscriptions()
    }

    override suspend fun refresh() {
        log(TAG) { "refresh()" }
        try {
            // Bounded: with unbounded connection retry, an unavailable Play would otherwise keep
            // background callers (the resume refresh, the isProSettled gate) suspended indefinitely.
            // Grace stamping happens via the freshBillingData collector, not here.
            val fresh = withTimeoutOrNull(refreshTimeoutMs) { billingManager.refresh() }
            if (fresh == null) {
                // A hanging connection is also a fresh attempt that couldn't confirm a purchase.
                recordProUnconfirmed()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Background refresh: swallow and log so callers aren't affected. The explicit restore
            // path uses restorePurchaseNow(), which surfaces errors.
            log(TAG, ERROR) { "Background refresh failed: ${e.asLog()}" }
            recordProUnconfirmed()
        }
    }

    /**
     * Explicit "Restore purchase": query Play now and evaluate entitlement from the returned data in
     * the same coroutine (real happens-before), so we never read a stale [upgradeInfo] replay.
     * Billing errors propagate so the caller can distinguish "not owned" from "Play unavailable".
     */
    suspend fun restorePurchaseNow(): RestoreOutcome {
        // INFO: pairs with the refreshPurchases() outcome line so a support log shows which Play
        // round-trip belongs to an explicit restore tap.
        log(TAG, INFO) { "restorePurchaseNow()" }
        return try {
            // A restore result IS a real Play round-trip outcome -> settled.
            RestoreOutcome.Checked(billingManager.refresh().toUpgradeInfo(settled = true))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Mirror the reactive flow's retryWhen: a transient Play error while we were Pro recently
            // keeps us Pro via the grace period; otherwise surface the error so the caller can show
            // the proper "Play unavailable" message instead of a generic restore failure.
            val snapshot = billingCache.snapshot()
            if (isWithinGrace(snapshot.lastProAt, graceWindowMs(snapshot.lastProSku))) {
                log(TAG, VERBOSE) { "restore hit a Play error but we were Pro recently -> grace" }
                recordProUnconfirmed()
                // Grace keeps the upgrade, but the lookup itself never landed. Reported as
                // Inconclusive so the UI can't claim a completed check: an owner in grace is exactly
                // who must not be told "we checked Play and found nothing".
                RestoreOutcome.Inconclusive(
                    Info(gracePeriod = true, billingData = null, isSettled = true),
                    e,
                )
            } else {
                throw e
            }
        }
    }

    /**
     * Provenance of an explicit restore, kept apart from [Info] so entitlement stays untouched.
     *
     * [Info] alone can't carry this: a grace-substituted `Info(gracePeriod = true, billingData =
     * null)` is produced both by a successful empty query (a real answer) and by a swallowed Play
     * error (no answer at all). Those need opposite UI treatment.
     */
    sealed interface RestoreOutcome {
        val info: Info

        /** Play answered. [info] reflects a real entitlement lookup. */
        data class Checked(override val info: Info) : RestoreOutcome

        /** Play couldn't be reached; [info] is grace-substituted and ownership stays unknown. */
        data class Inconclusive(override val info: Info, val cause: Throwable) : RestoreOutcome
    }

    /**
     * Persists "we saw a known purchase" for the grace machinery, or feeds the unconfirmed-episode
     * clock when fresh data can't confirm one. Only ever fed by the freshBillingData collector —
     * fresh Play round-trips, never replayed flow data, so a refunded purchase can't keep re-stamping
     * its grace window.
     */
    private suspend fun recordProState(fresh: BillingManager.FreshData) = proStateLock.withLock {
        val sku = preferredProSku(Info(billingData = fresh.data).upgrades)
        if (sku == null) {
            // A full snapshot proves absence; a partial one (purchase event, single-type query) only
            // proves presence of what it contains and must not start an unconfirmed episode.
            // occurredAt is the snapshot's commit time — when Play confirmed the absence.
            if (fresh.isFullSnapshot) recordProUnconfirmedLocked(fresh.occurredAt)
            return@withLock
        }
        val storedSkuId = billingCache.snapshot().lastProSku
        val storedType = OurSku.PRO_SKUS.singleOrNull { it.id == storedSkuId }?.type
        // A non-full snapshot (purchase event, partial refresh) proves ownership of what it contains,
        // but not the ABSENCE of anything else: it must not downgrade the grace class of a previously
        // confirmed permanent IAP (30d) to the subscription window (7d). Only a full snapshot, where
        // Play confirmed the IAP is really gone, may do that.
        val effectiveSkuId = if (
            !fresh.isFullSnapshot && storedType == Sku.Type.IAP && sku.type != Sku.Type.IAP
        ) {
            storedSkuId
        } else {
            sku.id
        }
        log(TAG, VERBOSE) { "Fresh pro state confirmed by $sku, stamping $effectiveSkuId" }
        // Stamp with the confirmation's OWN commit time, not processing-now: the same value gates
        // which unconfirmed episode this closes and lets a later connection failure be correctly
        // ordered against this success.
        billingCache.stampLastProState(effectiveSkuId, fresh.occurredAt)
    }

    private suspend fun recordProUnconfirmed(occurredAt: Long = System.currentTimeMillis()) =
        proStateLock.withLock { recordProUnconfirmedLocked(occurredAt) }

    private suspend fun recordProUnconfirmedLocked(occurredAt: Long) {
        try {
            billingCache.markProUnconfirmed(occurredAt) { skuId -> graceWindowMs(skuId) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log(TAG, WARN) { "Failed to record unconfirmed pro state: ${e.asLog()}" }
        }
    }

    /**
     * Shared entitlement/grace mapping used by both the reactive [upgradeInfo] flow and
     * [restorePurchaseNow]. Only relinquishes the upgrade if we haven't had it for a while (grace
     * period). READ-ONLY: this runs on replayed shared-flow data too, so it must never stamp the
     * grace cache — see [recordProState]. [settled] comes from the caller, never from `billingData`
     * nullness: the grace branch returns an Info with `billingData = null` that may well be settled.
     */
    private suspend fun BillingData?.toUpgradeInfo(settled: Boolean): Info {
        // Branch on MAPPED upgrades, not raw purchases: a purchase list containing only products this
        // app doesn't know maps to zero upgrades and must fall through to the grace check — otherwise
        // a recently-Pro user is denied grace they're entitled to. A known purchase is decided before
        // any grace-cache read, so failing local storage can't turn a confirmed purchase into an
        // error episode.
        val mapped = Info(billingData = this, isSettled = settled)
        if (mapped.upgrades.isNotEmpty()) return mapped

        val snapshot = billingCache.snapshot()
        log(TAG) { "toUpgradeInfo(): lastProAt=${snapshot.lastProAt}, data=$this" }
        return if (isWithinGrace(snapshot.lastProAt, graceWindowMs(snapshot.lastProSku))) {
            log(TAG, VERBOSE) { "Not currently pro, but were recently — staying pro through the grace window" }
            Info(gracePeriod = true, billingData = null, isSettled = settled)
        } else {
            mapped
        }
    }

    // Grace window depends on what was last owned: a permanent one-time purchase gets a long window,
    // a subscription (or an unknown/legacy last SKU) gets the short default.
    private fun graceWindowMs(lastSku: String): Long {
        val type = OurSku.PRO_SKUS.singleOrNull { it.id == lastSku }?.type
        return if (type == Sku.Type.IAP) GRACE_PERIOD_IAP_MS else GRACE_PERIOD_MS
    }

    data class Info(
        private val gracePeriod: Boolean = false,
        private val billingData: BillingData?,
        override val error: Throwable? = null,
        // Default false is the fail-safe direction: a forgotten stamp shows up as "never settles"
        // (loud), never as a settled pre-reconciliation flash.
        override val isSettled: Boolean = false,
    ) : UpgradeRepo.Info {

        override val type: UpgradeRepo.Type = UpgradeRepo.Type.GPLAY

        val upgrades: Collection<PurchasedSku> = billingData?.purchases
            ?.map { purchase ->
                purchase.products.mapNotNull { productId ->
                    val sku = OurSku.PRO_SKUS.singleOrNull { it.id == productId }
                    if (sku == null) {
                        log(TAG, ERROR) { "Unknown product: $productId (${purchase.redacted()})" }
                        return@mapNotNull null
                    }
                    PurchasedSku(sku, purchase)
                }
            }
            ?.flatten()
            ?: emptySet()

        /**
         * SKUs whose purchase is awaiting payment approval. They grant nothing — [isPro] ignores them
         * — but the UI needs them: an offer row for something already being paid for must not invite
         * a second purchase.
         */
        val pending: Collection<Sku> = billingData?.pendingPurchases
            ?.flatMap { purchase ->
                purchase.products.mapNotNull { productId -> OurSku.PRO_SKUS.singleOrNull { it.id == productId } }
            }
            ?.distinct()
            ?: emptySet()

        override val isPro: Boolean = upgrades.isNotEmpty() || gracePeriod

        override val upgradedAt: Instant? = upgrades
            .maxByOrNull { it.purchase.purchaseTime }
            ?.let { Instant.ofEpochMilli(it.purchase.purchaseTime) }
    }

    companion object {
        private const val STORE_SITE = "https://play.google.com/store/apps/details?id=eu.darken.amply"
        private const val UPGRADE_SITE = "https://play.google.com/store/apps/details?id=eu.darken.amply"
        private const val BETA_SITE = "https://play.google.com/apps/testing/eu.darken.amply"

        // Keep paying users upgraded through transient empty/failed Play Billing responses. A
        // permanent one-time purchase should almost never be dropped on a hiccup, so it gets a long
        // window; a subscription legitimately lapses, so it keeps the short one. GRACE_PERIOD_MS is
        // the subscription/default window (also used when the last-owned SKU is unknown/legacy).
        val GRACE_PERIOD_MS: Long = Duration.ofDays(7).toMillis()
        val GRACE_PERIOD_IAP_MS: Long = Duration.ofDays(30).toMillis()

        private const val RESTORE_ON_OWNED_TIMEOUT_MS = 15_000L
        private const val REFRESH_TIMEOUT_MS = 30_000L

        // Covers connecting, the SKU query the launch does first, and the sheet launch itself.
        internal const val LAUNCH_TIMEOUT_MS = 30_000L
        val TAG: String = logTag("Upgrade", "Gplay", "Repo")

        /**
         * Whether a confirmation at [lastProAt] still keeps the upgrade alive.
         *
         * The lower bound is not decoration: a device clock moved backwards makes `now - lastProAt`
         * negative, which a bare `< window` check would accept — granting the upgrade indefinitely to
         * anyone who sets their clock back. Requiring the age to be in `0 until window` denies that
         * outright, and the next successful Play round-trip re-stamps a sane anchor.
         */
        internal fun isWithinGrace(
            lastProAt: Long,
            windowMs: Long,
            now: Long = System.currentTimeMillis(),
        ): Boolean = lastProAt > 0L && (now - lastProAt) in 0 until windowMs

        /**
         * The SKU whose grace window applies when several are owned: the permanent one-time purchase
         * wins over a subscription (purchases are time-sorted, so firstOrNull alone isn't enough).
         * null when no known SKU is owned.
         */
        internal fun preferredProSku(upgrades: Collection<PurchasedSku>): Sku? =
            upgrades.firstOrNull { it.sku.type == Sku.Type.IAP }?.sku ?: upgrades.firstOrNull()?.sku

        /**
         * Backoff for the local-failure retry in [upgradeInfo]: 30s/60s/120s/240s, capped at 5min.
         * Integer math on purpose — a Double-pow formula sleeps for hours and can overflow into a hot
         * loop at extreme attempt counts.
         */
        internal fun retryDelayMs(attempt: Long): Long =
            if (attempt >= 4) 300_000L else 30_000L shl attempt.toInt()
    }
}
