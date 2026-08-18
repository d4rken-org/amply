package eu.darken.amply.upgrade.ui

import android.app.Activity
import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ApplicationProvider
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.TestProductDetails
import eu.darken.amply.common.AppDataStore
import eu.darken.amply.common.WebpageTool
import eu.darken.amply.common.serialization.SerializationModule
import eu.darken.amply.upgrade.core.BillingCache
import eu.darken.amply.upgrade.core.OurSku
import eu.darken.amply.upgrade.core.UpgradeRepoGplay
import eu.darken.amply.upgrade.core.billing.BillingData
import eu.darken.amply.upgrade.core.billing.BillingManager
import eu.darken.amply.upgrade.core.billing.GplayServiceUnavailableException
import eu.darken.amply.upgrade.core.billing.ItemAlreadyOwnedBillingException
import eu.darken.amply.upgrade.core.billing.Sku
import eu.darken.amply.upgrade.core.billing.SkuDetails
import eu.darken.amply.upgrade.core.billing.TestPurchases
import eu.darken.amply.upgrade.core.billing.client.BillingConnection
import eu.darken.amply.upgrade.core.billing.client.BillingConnectionProvider
import eu.darken.amply.upgrade.core.billing.toBillingData
import eu.darken.amply.upgrade.core.billing.work.FakePurchaseAckScheduler
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * The screen's decisions that cost money if they are wrong: whether a purchase may be started at all
 * (a renewing subscription, an already-owned upgrade, a payment Play is still processing, or a check
 * that never finished), and what a restore is allowed to claim afterwards.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GplayUpgradeViewModelTest {

    @get:Rule val tempFolder = TemporaryFolder()

    private val json: Json = SerializationModule.json()
    private val storeScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val iapId = OurSku.Iap.PRO_UPGRADE.id
    private val subId = OurSku.Sub.PRO_UPGRADE.id

    /**
     * A manager whose Play round-trips are answered by the test. The base class's connect loop is
     * harmless: its provider never emits, so it records one failure and backs off, and every call the
     * screen makes is overridden here.
     */
    private open class FakeBillingManager(
        override val billingData: Flow<BillingData>,
    ) : BillingManager(
        object : BillingConnectionProvider(ApplicationProvider.getApplicationContext()) {
            override val connection: Flow<BillingConnection> = emptyFlow()
        },
        FakePurchaseAckScheduler(),
    ) {
        override val isFailureSettled: Flow<Boolean> = MutableStateFlow(false)
        override val freshBillingData: Flow<FreshData> = emptyFlow()
        override val purchaseFailures: Flow<BillingResult> = emptyFlow()
        override val connectionFailures: Flow<Long> = emptyFlow()

        var strictAnswer: suspend () -> BillingData = { BillingData(emptyList()) }
        var refreshAnswer: () -> BillingData = { BillingData(emptyList()) }
        var launchAnswer: () -> Unit = {}
        val launches: MutableList<Sku> = mutableListOf()

        override suspend fun querySkus(vararg skus: Sku): Collection<SkuDetails> = skus.map { sku ->
            when (sku) {
                OurSku.Iap.PRO_UPGRADE -> SkuDetails(sku, TestProductDetails.oneTimePurchase(sku.id, "$9.99"))
                else -> SkuDetails(
                    sku,
                    TestProductDetails.subscription(
                        sku.id,
                        listOf(TestProductDetails.Offer("upgrade-pro-baseplan", null, "$4.99")),
                    ),
                )
            }
        }

        override suspend fun refreshStrict(): BillingData = strictAnswer()

        override suspend fun refresh(): BillingData = refreshAnswer()

        // Records what actually reached Play: the gate tests below assert that a blocked tap never
        // gets this far.
        override suspend fun startIapFlow(activity: Activity, sku: Sku, offer: Sku.Subscription.Offer?) {
            launches.add(sku)
            launchAnswer()
        }
    }

    @Before fun setup() {
        Dispatchers.setMain(Dispatchers.Unconfined)
    }

    @After fun teardown() {
        Dispatchers.resetMain()
        storeScope.cancel()
    }

    private fun cache(): BillingCache {
        val store = PreferenceDataStoreFactory.create(scope = storeScope) {
            File(tempFolder.newFolder(), "test.preferences_pb")
        }
        return BillingCache(AppDataStore(store), json)
    }

    private fun viewModel(
        manager: FakeBillingManager,
        cache: BillingCache = cache(),
    ): UpgradeViewModel = UpgradeViewModel(
        upgradeRepo = UpgradeRepoGplay(manager, cache, FakePurchaseAckScheduler()),
        webpageTool = WebpageTool(ApplicationProvider.getApplicationContext<Context>()),
    )

    private fun freeManager() = FakeBillingManager(MutableStateFlow(BillingData(emptyList())))

    // The purchase paths need an Activity to hand to Play; only its identity matters here.
    private fun activity(): Activity = Robolectric.buildActivity(Activity::class.java).get()

    // region the pre-purchase gate

    @Test fun `a failed purchase check fails closed`(): Unit = runBlocking {
        // "Couldn't verify" must never be read as "nothing owned": that is the reading that lets a
        // user pay twice.
        val manager = freeManager().apply {
            strictAnswer = { throw GplayServiceUnavailableException(RuntimeException("play down")) }
        }
        val vm = viewModel(manager)

        vm.onGoIap(activity())

        val event = withTimeout(TIMEOUT_MS) { vm.events.first() }
        event.shouldBeInstanceOf<UpgradeEvents.Error>()
        manager.launches shouldBe emptyList()
    }

    @Test fun `a subscription that is not set to renew lets the purchase through`(): Unit = runBlocking {
        val manager = freeManager().apply {
            strictAnswer = { listOf(TestPurchases.purchase(subId, autoRenewing = false)).toBillingData() }
        }
        val vm = viewModel(manager)

        vm.onGoIap(activity())

        // No blocking event, and the launch really was attempted.
        val event = withTimeoutOrNull(QUIET_MS) { vm.events.first() }
        event shouldBe null
        manager.launches shouldBe listOf(OurSku.Iap.PRO_UPGRADE)
    }

    @Test fun `a renewing subscription is refused with the manage-subscription prompt`(): Unit = runBlocking {
        val manager = freeManager().apply {
            strictAnswer = { listOf(TestPurchases.purchase(subId, autoRenewing = true)).toBillingData() }
        }
        val vm = viewModel(manager)

        vm.onGoIap(activity())

        withTimeout(TIMEOUT_MS) { vm.events.first() } shouldBe UpgradeEvents.SubscriptionStillRenewing
        manager.launches shouldBe emptyList()
    }

    @Test fun `a renewing subscription with an unknown product still blocks the one-time purchase`(): Unit =
        runBlocking {
            // The gate reads the RAW purchases, never the mapped upgrades: a subscription whose
            // product ID this build doesn't know (legacy SKU, renamed product) still renews and still
            // bills, so letting the one-time purchase through here charges the user twice.
            val manager = freeManager().apply {
                strictAnswer = {
                    listOf(TestPurchases.purchase("some.unknown.subscription", autoRenewing = true)).toBillingData()
                }
            }
            val vm = viewModel(manager)

            vm.onGoIap(activity())

            withTimeout(TIMEOUT_MS) { vm.events.first() } shouldBe UpgradeEvents.SubscriptionStillRenewing
            manager.launches shouldBe emptyList()
        }

    @Test fun `a pending payment blocks the one-time purchase`(): Unit = runBlocking {
        val manager = freeManager().apply {
            strictAnswer = { listOf(TestPurchases.purchase(iapId, pending = true)).toBillingData() }
        }
        val vm = viewModel(manager)

        vm.onGoIap(activity())

        withTimeout(TIMEOUT_MS) { vm.events.first() } shouldBe UpgradeEvents.PurchasePending
        manager.launches shouldBe emptyList()
    }

    @Test fun `a pending payment blocks the subscription purchase`(): Unit = runBlocking {
        // SKU-agnostic on purpose: the two products are alternatives, so a pending payment for either
        // one must block both — completing both charges the user twice.
        val manager = freeManager().apply {
            strictAnswer = { listOf(TestPurchases.purchase(iapId, pending = true)).toBillingData() }
        }
        val vm = viewModel(manager)

        vm.onGoSubscriptionTrial(activity())

        withTimeout(TIMEOUT_MS) { vm.events.first() } shouldBe UpgradeEvents.PurchasePending
        manager.launches shouldBe emptyList()
    }

    @Test fun `a gate timeout blocks the one-time purchase`(): Unit = runBlocking {
        val manager = freeManager().apply {
            strictAnswer = {
                delay(TIMEOUT_MS)
                BillingData(emptyList())
            }
        }
        val vm = viewModel(manager).apply { verifyTimeoutMs = GATE_TIMEOUT_MS }

        vm.onGoIap(activity())

        withTimeout(TIMEOUT_MS) { vm.events.first() } shouldBe UpgradeEvents.PurchaseCheckFailed
        manager.launches shouldBe emptyList()
    }

    @Test fun `a gate timeout blocks the subscription purchase too`(): Unit = runBlocking {
        // The subscription path used to launch unverified: a slow Play means we don't know whether a
        // payment is already pending, so it must fail closed like the one-time path.
        val manager = freeManager().apply {
            strictAnswer = {
                delay(TIMEOUT_MS)
                BillingData(emptyList())
            }
        }
        val vm = viewModel(manager).apply { verifyTimeoutMs = GATE_TIMEOUT_MS }

        vm.onGoSubscription(activity())

        withTimeout(TIMEOUT_MS) { vm.events.first() } shouldBe UpgradeEvents.PurchaseCheckFailed
        manager.launches shouldBe emptyList()
    }

    @Test fun `a subscription purchase is blocked when the fresh check finds an owned upgrade`(): Unit =
        runBlocking {
            // The screen can be stale (the one-time purchase was made on another device) and Play
            // sells the subscription right next to an owned one-time upgrade — launching here charges
            // the user a second time.
            val manager = freeManager().apply {
                strictAnswer = { listOf(TestPurchases.purchase(iapId)).toBillingData() }
            }
            val vm = viewModel(manager)

            vm.onGoSubscription(activity())

            withTimeout(TIMEOUT_MS) { vm.events.first() } shouldBe UpgradeEvents.RestoreSucceeded
            manager.launches shouldBe emptyList()
        }

    @Test fun `a subscription purchase is blocked when an unknown renewing subscription exists`(): Unit =
        runBlocking {
            // Unknown product ID means zero mapped upgrades, so the ownership block above lets it
            // through. It still renews and still bills, and a second subscription for the same
            // features is the same double charge.
            val manager = freeManager().apply {
                strictAnswer = {
                    listOf(TestPurchases.purchase("some.unknown.subscription", autoRenewing = true)).toBillingData()
                }
            }
            val vm = viewModel(manager)

            vm.onGoSubscription(activity())

            withTimeout(TIMEOUT_MS) { vm.events.first() } shouldBe UpgradeEvents.SubscriptionStillRenewing
            manager.launches shouldBe emptyList()
        }

    @Test fun `a cleared gate lets the subscription purchase launch`(): Unit = runBlocking {
        val vm = viewModel(freeManager())

        vm.onGoSubscription(activity())

        withTimeoutOrNull(QUIET_MS) { vm.events.first() } shouldBe null
    }

    @Test fun `a pending-payment launch failure surfaces as the pending dialog`(): Unit = runBlocking {
        // Play can only report this at launch time (the gate saw a clean state moments earlier): the
        // already-owned error dialog with its restore tips would be the wrong advice. The recovery
        // restore finds the pending payment, so the repo reports it as such.
        val manager = freeManager().apply {
            launchAnswer = { throw ItemAlreadyOwnedBillingException(RuntimeException("already owned")) }
            refreshAnswer = { listOf(TestPurchases.purchase(iapId, pending = true)).toBillingData() }
        }
        val vm = viewModel(manager)

        vm.onGoIap(activity())

        withTimeout(TIMEOUT_MS) { vm.events.first() } shouldBe UpgradeEvents.PurchasePending
    }

    // endregion

    // region the pending explanation on screen

    @Test fun `a pending payment keeps its hint when both price queries fail`(): Unit = runBlocking {
        // Not the acquisition-style Unavailable card: the pending explanation must survive a price
        // outage, exactly like the grace and ownership states do. Both offers are locked anyway.
        val manager = object : FakeBillingManager(
            MutableStateFlow(listOf(TestPurchases.purchase(iapId, pending = true)).toBillingData()),
        ) {
            override suspend fun querySkus(vararg skus: Sku): Collection<SkuDetails> =
                throw GplayServiceUnavailableException(RuntimeException("play down"))
        }
        val vm = viewModel(manager)

        val loaded = withTimeout(TIMEOUT_MS) {
            vm.state.first { it is GplayUpgradeUiState.Loaded } as GplayUpgradeUiState.Loaded
        }

        loaded.iapPending shouldBe true
        loaded.iapEnabled shouldBe false
        loaded.subscriptionEnabled shouldBe false
    }

    // endregion

    // region restore outcomes

    @Test fun `a restore that finds the purchase reports success`(): Unit = runBlocking {
        val manager = freeManager().apply {
            refreshAnswer = { listOf(TestPurchases.purchase(iapId)).toBillingData() }
        }
        val vm = viewModel(manager)

        vm.restorePurchase()

        withTimeout(TIMEOUT_MS) { vm.events.first() } shouldBe UpgradeEvents.RestoreSucceeded
    }

    @Test fun `a restore that finds nothing reports a completed, empty check`(): Unit = runBlocking {
        val vm = viewModel(freeManager())

        vm.restorePurchase()

        // Play answered — so troubleshooting advice is warranted, unlike an inconclusive result.
        withTimeout(TIMEOUT_MS) { vm.events.first() } shouldBe UpgradeEvents.RestoreFailed
    }

    @Test fun `a restore absorbed by grace is inconclusive, not a completed check`(): Unit = runBlocking {
        val cache = cache()
        cache.stampLastProState(iapId, System.currentTimeMillis())
        val manager = freeManager().apply {
            refreshAnswer = { throw GplayServiceUnavailableException(RuntimeException("play down")) }
        }
        val vm = viewModel(manager, cache)

        vm.restorePurchase()

        // An owner in grace is exactly the person who must not be told "we checked Play and found
        // nothing" — nothing was checked at all.
        withTimeout(TIMEOUT_MS) { vm.events.first() } shouldBe UpgradeEvents.RestoreInconclusive
    }

    @Test fun `repeated restore taps do not stack concurrent checks`(): Unit = runBlocking {
        val vm = viewModel(freeManager())

        vm.restorePurchase()
        vm.restorePurchase()
        vm.restorePurchase()

        withTimeout(TIMEOUT_MS) { vm.events.first() } shouldBe UpgradeEvents.RestoreFailed
        // One tap, one result dialog: the single-flight guard covers the whole action, so the extra
        // taps produce nothing at all.
        withTimeoutOrNull(QUIET_MS) { vm.events.first() } shouldBe null
    }

    // endregion

    private companion object {
        const val TIMEOUT_MS = 15_000L
        const val QUIET_MS = 500L
        // Short enough to keep the fail-closed timeout tests fast, long enough that a healthy answer
        // would comfortably make it back.
        const val GATE_TIMEOUT_MS = 250L
    }
}
