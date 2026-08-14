package eu.darken.amply.upgrade.ui

import android.app.Activity
import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ApplicationProvider
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.Purchase
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
import eu.darken.amply.upgrade.core.billing.Sku
import eu.darken.amply.upgrade.core.billing.SkuDetails
import eu.darken.amply.upgrade.core.billing.TestPurchases
import eu.darken.amply.upgrade.core.billing.client.BillingConnection
import eu.darken.amply.upgrade.core.billing.client.BillingConnectionProvider
import eu.darken.amply.upgrade.core.billing.toBillingData
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
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
 * The screen's decisions that cost money if they are wrong: whether a one-time purchase may be
 * started while a subscription still renews, and what a restore is allowed to claim afterwards.
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
    ) {
        override val isFailureSettled: Flow<Boolean> = MutableStateFlow(false)
        override val freshBillingData: Flow<FreshData> = emptyFlow()
        override val purchaseFailures: Flow<BillingResult> = emptyFlow()
        override val connectionFailures: Flow<Long> = emptyFlow()

        var subscriptionsAnswer: () -> Collection<Purchase> = { emptyList() }
        var refreshAnswer: () -> BillingData = { BillingData(emptyList()) }

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

        override suspend fun querySubscriptions(): Collection<Purchase> = subscriptionsAnswer()

        override suspend fun refresh(): BillingData = refreshAnswer()
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
        upgradeRepo = UpgradeRepoGplay(manager, cache),
        webpageTool = WebpageTool(ApplicationProvider.getApplicationContext<Context>()),
    )

    private fun freeManager() = FakeBillingManager(MutableStateFlow(BillingData(emptyList())))

    // The purchase paths need an Activity to hand to Play; only its identity matters here.
    private fun activity(): Activity = Robolectric.buildActivity(Activity::class.java).get()

    // region the pre-purchase subscription gate

    @Test fun `a failed subscription check fails closed`(): Unit = runBlocking {
        // "Couldn't verify" must never be read as "no subscription": that is the reading that lets a
        // user pay twice.
        val manager = freeManager().apply {
            subscriptionsAnswer = { throw GplayServiceUnavailableException(RuntimeException("play down")) }
        }
        val vm = viewModel(manager)

        vm.onGoIap(activity())

        val event = withTimeout(TIMEOUT_MS) { vm.events.first() }
        event.shouldBeInstanceOf<UpgradeEvents.Error>()
    }

    @Test fun `a subscription that is not set to renew lets the purchase through`(): Unit = runBlocking {
        val manager = freeManager().apply {
            subscriptionsAnswer = { listOf(TestPurchases.purchase(subId, autoRenewing = false)) }
        }
        val vm = viewModel(manager)

        vm.onGoIap(activity())

        // No blocking event: the launch itself is attempted (and fails without a real Play sheet,
        // which surfaces as an error rather than the still-renewing refusal).
        val event = withTimeoutOrNull(QUIET_MS) { vm.events.first() }
        (event is UpgradeEvents.SubscriptionStillRenewing) shouldBe false
    }

    @Test fun `a renewing subscription is refused with the manage-subscription prompt`(): Unit = runBlocking {
        val manager = freeManager().apply {
            subscriptionsAnswer = { listOf(TestPurchases.purchase(subId, autoRenewing = true)) }
        }
        val vm = viewModel(manager)

        vm.onGoIap(activity())

        withTimeout(TIMEOUT_MS) { vm.events.first() } shouldBe UpgradeEvents.SubscriptionStillRenewing
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
    }
}
