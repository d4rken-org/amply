package eu.darken.amply.upgrade.core

import android.app.Activity
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ApplicationProvider
import eu.darken.amply.common.AppDataStore
import eu.darken.amply.common.serialization.SerializationModule
import com.android.billingclient.api.BillingResult
import eu.darken.amply.upgrade.core.billing.BillingData
import eu.darken.amply.upgrade.core.billing.BillingManager
import eu.darken.amply.upgrade.core.billing.GplayServiceUnavailableException
import eu.darken.amply.upgrade.core.billing.ItemAlreadyOwnedBillingException
import eu.darken.amply.upgrade.core.billing.PendingPurchaseBillingException
import eu.darken.amply.upgrade.core.billing.Sku
import eu.darken.amply.upgrade.core.billing.TestPurchases
import eu.darken.amply.upgrade.core.billing.client.BillingConnection
import eu.darken.amply.upgrade.core.billing.client.BillingConnectionProvider
import eu.darken.amply.upgrade.core.billing.toBillingData
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * The reactive entitlement: how billing data, the local grace cache and the "Play is unreachable"
 * signal combine into the one `Info` every gate reads.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class UpgradeRepoGplayFlowTest {

    @get:Rule val tempFolder = TemporaryFolder()

    private val json: Json = SerializationModule.json()
    private val storeScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val iapId = OurSku.Iap.PRO_UPGRADE.id

    @After fun teardown() {
        storeScope.cancel()
    }

    private fun cache(): BillingCache {
        val store = PreferenceDataStoreFactory.create(scope = storeScope) {
            File(tempFolder.newFolder(), "test.preferences_pb")
        }
        return BillingCache(AppDataStore(store), json)
    }

    /**
     * A manager whose Play-facing streams are supplied by the test. The connect loop the base class
     * starts is harmless here: its provider never emits, so it just records one failure and backs
     * off, and every stream the repository reads is overridden below.
     */
    private class FakeBillingManager(
        override val billingData: Flow<BillingData>,
        override val isFailureSettled: Flow<Boolean>,
    ) : BillingManager(
        object : BillingConnectionProvider(ApplicationProvider.getApplicationContext()) {
            override val connection: Flow<BillingConnection> = emptyFlow()
        },
    ) {
        override val freshBillingData: Flow<FreshData> = emptyFlow()
        override val purchaseFailures: Flow<BillingResult> = emptyFlow()
        override val connectionFailures: Flow<Long> = emptyFlow()

        var strictAnswer: () -> BillingData = { BillingData(emptyList()) }
        var refreshAnswer: () -> BillingData = { BillingData(emptyList()) }
        var launchAnswer: () -> Unit = {}

        override suspend fun refreshStrict(): BillingData = strictAnswer()

        override suspend fun refresh(): BillingData = refreshAnswer()

        override suspend fun startIapFlow(activity: Activity, sku: Sku, offer: Sku.Subscription.Offer?) =
            launchAnswer()
    }

    private fun manager(
        billingData: Flow<BillingData>,
        failureSettled: Flow<Boolean> = MutableStateFlow(false),
    ): FakeBillingManager = FakeBillingManager(billingData, failureSettled)

    private fun freeManager(): FakeBillingManager = manager(MutableStateFlow(BillingData(emptyList())))

    // The launch path needs an Activity to hand to Play; only its identity matters here.
    private fun activity(): Activity = Robolectric.buildActivity(Activity::class.java).get()

    @Test fun `an owned purchase settles as pro`(): Unit = runBlocking {
        val repo = UpgradeRepoGplay(
            manager(MutableStateFlow(listOf(TestPurchases.purchase(iapId)).toBillingData())),
            cache(),
        )

        withTimeout(TIMEOUT_MS) {
            repo.upgradeInfo.first { it.isSettled }.apply {
                isPro shouldBe true
                error shouldBe null
            }
        }
    }

    @Test fun `an empty answer from play settles as not pro`(): Unit = runBlocking {
        val repo = UpgradeRepoGplay(manager(MutableStateFlow(BillingData(emptyList()))), cache())

        withTimeout(TIMEOUT_MS) {
            repo.upgradeInfo.first { it.isSettled }.isPro shouldBe false
        }
    }

    @Test fun `a recent confirmation keeps the upgrade through an empty answer`(): Unit = runBlocking {
        val cache = cache()
        cache.stampLastProState(iapId, System.currentTimeMillis())
        val repo = UpgradeRepoGplay(manager(MutableStateFlow(BillingData(emptyList()))), cache)

        withTimeout(TIMEOUT_MS) {
            // Play returning nothing during a hiccup must not revoke a purchase we confirmed minutes
            // ago — that is the entire point of the grace window.
            repo.upgradeInfo.first { it.isSettled }.isPro shouldBe true
        }
    }

    @Test fun `an expired confirmation lets the upgrade lapse`(): Unit = runBlocking {
        val cache = cache()
        cache.stampLastProState(
            OurSku.Sub.PRO_UPGRADE.id,
            System.currentTimeMillis() - UpgradeRepoGplay.GRACE_PERIOD_MS - 1,
        )
        val repo = UpgradeRepoGplay(manager(MutableStateFlow(BillingData(emptyList()))), cache)

        withTimeout(TIMEOUT_MS) {
            repo.upgradeInfo.first { it.isSettled }.isPro shouldBe false
        }
    }

    @Test fun `a known purchase is decided before any grace-cache read`(): Unit = runBlocking {
        // The cache says the window is long gone; the purchase still wins.
        val cache = cache()
        cache.stampLastProState(iapId, 1L)
        val repo = UpgradeRepoGplay(
            manager(MutableStateFlow(listOf(TestPurchases.purchase(iapId)).toBillingData())),
            cache,
        )

        withTimeout(TIMEOUT_MS) {
            repo.upgradeInfo.first { it.isSettled }.apply {
                isPro shouldBe true
                upgrades.size shouldBe 1
            }
        }
    }

    @Test fun `an unreachable play settles the seed instead of waiting forever`(): Unit = runBlocking {
        // No billing data will ever arrive; only the failure signal can settle this, and it must —
        // otherwise every UI gate would sit on its timeout during a Play outage.
        val repo = UpgradeRepoGplay(
            manager(billingData = emptyFlow(), failureSettled = MutableStateFlow(true)),
            cache(),
        )

        withTimeout(TIMEOUT_MS) {
            repo.upgradeInfo.first().apply {
                isSettled shouldBe true
                isPro shouldBe false
            }
        }
    }

    @Test fun `a pending payment is reported without granting the upgrade`(): Unit = runBlocking {
        val repo = UpgradeRepoGplay(
            manager(MutableStateFlow(listOf(TestPurchases.purchase(iapId, pending = true)).toBillingData())),
            cache(),
        )

        withTimeout(TIMEOUT_MS) {
            val info = repo.upgradeInfo.first { it.isSettled } as UpgradeRepoGplay.Info
            info.isPro shouldBe false
            info.pending shouldBe listOf(OurSku.Iap.PRO_UPGRADE)
        }
    }

    @Test fun `settledness never regresses once reached`(): Unit = runBlocking {
        // The billing flow can resubscribe and re-emit its unsettled seed; a gate that saw "settled"
        // must not be told to wait again.
        val data = MutableStateFlow<BillingData?>(null)
        val repo = UpgradeRepoGplay(
            manager(data.filterNotNull(), failureSettled = MutableStateFlow(true)),
            cache(),
        )

        withTimeout(TIMEOUT_MS) {
            repo.upgradeInfo.first { it.isSettled }
            data.value = listOf(TestPurchases.purchase(iapId)).toBillingData()
            repo.upgradeInfo.map { it.isSettled }.first { it } shouldBe true
        }
    }

    @Test fun `a pending payment stays visible while grace keeps the upgrade`(): Unit = runBlocking {
        // The audience that needs the explanation most: the upgrade runs on grace while Play is still
        // processing the payment that will renew it. Dropping the data in the grace branch left them
        // with a silent screen.
        val cache = cache()
        cache.stampLastProState(iapId, System.currentTimeMillis())
        val repo = UpgradeRepoGplay(
            manager(MutableStateFlow(listOf(TestPurchases.purchase(iapId, pending = true)).toBillingData())),
            cache,
        )

        withTimeout(TIMEOUT_MS) {
            val info = repo.upgradeInfo.first { it.isSettled } as UpgradeRepoGplay.Info
            info.isPro shouldBe true
            info.pending shouldBe listOf(OurSku.Iap.PRO_UPGRADE)
        }
    }

    // region the strict gate lookup

    @Test fun `verifyPurchaseStateNow fails closed instead of substituting grace`(): Unit = runBlocking {
        val cache = cache()
        cache.stampLastProState(iapId, System.currentTimeMillis())
        val manager = freeManager().apply {
            strictAnswer = { throw GplayServiceUnavailableException(RuntimeException("one product type failed")) }
        }
        val repo = UpgradeRepoGplay(manager, cache)

        // Even a recent owner gets the error: a gate that can't verify must not let a purchase
        // through on the strength of a grace window.
        shouldThrow<GplayServiceUnavailableException> { repo.verifyPurchaseStateNow() }
    }

    @Test fun `verifyPurchaseStateNow reports the fresh split state`(): Unit = runBlocking {
        val manager = freeManager().apply {
            strictAnswer = {
                listOf(
                    TestPurchases.purchase(iapId),
                    TestPurchases.purchase(OurSku.Sub.PRO_UPGRADE.id, token = "sub", pending = true),
                ).toBillingData()
            }
        }
        val repo = UpgradeRepoGplay(manager, cache())

        val info = repo.verifyPurchaseStateNow()

        info.upgrades.map { it.sku } shouldBe listOf(OurSku.Iap.PRO_UPGRADE)
        info.pending shouldBe listOf(OurSku.Sub.PRO_UPGRADE)
        info.isSettled shouldBe true
    }

    @Test fun `an already-owned recovery reports a pending payment instead of restore tips`(): Unit = runBlocking {
        // Play refuses to re-sell a product whose payment it is still processing. The already-owned
        // dialog would tell the user to restore, which cannot help.
        val manager = freeManager().apply {
            launchAnswer = { throw ItemAlreadyOwnedBillingException(RuntimeException("launch result")) }
            refreshAnswer = { listOf(TestPurchases.purchase(iapId, pending = true)).toBillingData() }
        }
        val repo = UpgradeRepoGplay(manager, cache())

        val errors = mutableListOf<Throwable>()
        withTimeout(TIMEOUT_MS) {
            repo.launchBillingFlowNow(activity(), OurSku.Iap.PRO_UPGRADE, null) { errors.add(it) }
        }

        errors.single().shouldBeInstanceOf<PendingPurchaseBillingException>()
    }

    // endregion

    @Test fun `wasEverPro tracks whether this install ever confirmed a purchase`(): Unit = runBlocking {
        val cache = cache()
        val repo = UpgradeRepoGplay(manager(MutableStateFlow(BillingData(emptyList()))), cache)

        withTimeout(TIMEOUT_MS) {
            repo.wasEverPro.first() shouldBe false
            cache.stampLastProState(iapId, System.currentTimeMillis())
            repo.wasEverPro.first { it } shouldBe true
        }
    }

    private companion object {
        const val TIMEOUT_MS = 10_000L
    }
}
