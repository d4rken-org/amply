package eu.darken.amply.upgrade.core

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import eu.darken.amply.common.AppDataStore
import eu.darken.amply.common.datastore.kotlinxReader
import eu.darken.amply.common.datastore.value
import eu.darken.amply.common.serialization.SerializationModule
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * The grace bookkeeping is one record precisely because the three values are only meaningful
 * together. These tests pin the transactional rules that follow from that.
 *
 * `runBlocking`, not `runTest`: [BillingCache] bounds its own reads and writes, and virtual time
 * would trip those timeouts instantly while the real DataStore does its I/O off the scheduler.
 */
class BillingCacheTest {

    @TempDir lateinit var tempDir: File

    private val json: Json = SerializationModule.json()
    private val storeScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val store by lazy {
        PreferenceDataStoreFactory.create(scope = storeScope) { File(tempDir, "test.preferences_pb") }
    }
    private val cache by lazy { BillingCache(AppDataStore(store), json) }

    private val week = UpgradeRepoGplay.GRACE_PERIOD_MS

    @AfterEach fun teardown() {
        storeScope.cancel()
    }

    @Test fun `a fresh install has never been confirmed`() = runBlocking {
        cache.snapshot() shouldBe GraceState()
    }

    @Test fun `stamping records the sku and the confirmation time together`() = runBlocking {
        cache.stampLastProState("upgrade.pro", 5_000L)

        cache.snapshot() shouldBe GraceState(lastProAt = 5_000L, lastProSku = "upgrade.pro", proUnconfirmedAt = 0L)
    }

    @Test fun `a confirmation closes an episode that started before it`() = runBlocking {
        cache.stampLastProState("upgrade.pro", 1_000L)
        cache.markProUnconfirmed(occurredAt = 2_000L) { week }
        cache.snapshot().proUnconfirmedAt shouldBe 2_000L

        cache.stampLastProState("upgrade.pro", 3_000L)

        cache.snapshot().proUnconfirmedAt shouldBe 0L
    }

    @Test fun `a confirmation does not close an episode that started after it`() = runBlocking {
        // Out-of-order delivery: the failure happened later than this confirmation, so the episode it
        // opened is still valid and an older success must not erase it.
        cache.stampLastProState("upgrade.pro", 1_000L)
        cache.markProUnconfirmed(occurredAt = 5_000L) { week }

        cache.stampLastProState("upgrade.pro", 3_000L)

        cache.snapshot().proUnconfirmedAt shouldBe 5_000L
    }

    @Test fun `an install that was never confirmed starts no episode`() = runBlocking {
        cache.markProUnconfirmed(occurredAt = 5_000L) { week }

        cache.snapshot().proUnconfirmedAt shouldBe 0L
    }

    @Test fun `a failure older than the last confirmation is rejected`() = runBlocking {
        cache.stampLastProState("upgrade.pro", 5_000L)

        cache.markProUnconfirmed(occurredAt = 4_000L) { week }

        cache.snapshot().proUnconfirmedAt shouldBe 0L
    }

    @Test fun `a failure past the grace window is not worth tracking`() = runBlocking {
        cache.stampLastProState("upgrade.pro", 1_000L)

        cache.markProUnconfirmed(occurredAt = 1_000L + week) { week }

        cache.snapshot().proUnconfirmedAt shouldBe 0L
    }

    @Test fun `follow-up failures never move the episode start`() = runBlocking {
        cache.stampLastProState("upgrade.pro", 1_000L)

        cache.markProUnconfirmed(occurredAt = 2_000L) { week }
        cache.markProUnconfirmed(occurredAt = 3_000L) { week }

        // The episode is what the diagnostics threshold ages against; refreshing it would keep the
        // hint permanently invisible during a sustained outage.
        cache.snapshot().proUnconfirmedAt shouldBe 2_000L
    }

    @Test fun `the window is evaluated against the stored sku`() = runBlocking {
        cache.stampLastProState(OurSku.Iap.PRO_UPGRADE.id, 1_000L)

        var seenSku: String? = null
        cache.markProUnconfirmed(occurredAt = 2_000L) { sku ->
            seenSku = sku
            UpgradeRepoGplay.GRACE_PERIOD_IAP_MS
        }

        // Read inside the transaction, so the window and the timestamp it gates always come from the
        // same record.
        seenSku shouldBe OurSku.Iap.PRO_UPGRADE.id
        cache.snapshot().proUnconfirmedAt shouldBe 2_000L
    }

    @Test fun `a corrupt record reads as never confirmed instead of throwing`() = runBlocking {
        val key = stringPreferencesKey("upgrade.gplay.grace.v1")
        store.edit { it[key] = "{not json" }

        // Costs a recent purchaser their grace window, but is recoverable — the next successful Play
        // round-trip rewrites it. Throwing would take down the entitlement flow this only decorates.
        cache.snapshot() shouldBe GraceState()

        cache.stampLastProState("upgrade.pro", 7_000L)
        cache.snapshot().lastProAt shouldBe 7_000L
    }

    @Test fun `the record encodes to the pinned shape`() = runBlocking {
        cache.stampLastProState("upgrade.pro", 5_000L)

        // Pinned literally: this outlives the process that wrote it, so a rename has to fail here
        // rather than silently drop a purchaser's grace window on the next launch.
        json.encodeToString(
            GraceState.serializer(),
            cache.state.value(),
        ) shouldBe """{"lastProAt":5000,"lastProSku":"upgrade.pro","proUnconfirmedAt":0}"""
    }

    @Test fun `a record from a newer build keeps the fields it recognizes`() {
        val reader = kotlinxReader(json, defaultValue = GraceState(), fallbackToDefault = true)
        val key = stringPreferencesKey("upgrade.gplay.grace.v1")
        val stored = """{"lastProAt":9,"lastProSku":"upgrade.pro","proUnconfirmedAt":0,"tier":"gold"}"""

        reader(mutablePreferencesOf(key to stored)[key]) shouldBe
            GraceState(lastProAt = 9L, lastProSku = "upgrade.pro")
    }
}
