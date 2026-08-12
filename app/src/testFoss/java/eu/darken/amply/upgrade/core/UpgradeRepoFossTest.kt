package eu.darken.amply.upgrade.core

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.test.core.app.ApplicationProvider
import eu.darken.amply.common.AppDataStore
import eu.darken.amply.common.WebpageTool
import eu.darken.amply.common.datastore.value
import eu.darken.amply.common.serialization.SerializationModule
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException
import java.time.Instant

/**
 * Robolectric because [WebpageTool] needs a real [Context]; the behaviour under test is the
 * entitlement flow itself, which runs on real dispatchers on purpose — the point of the error
 * handling is that a broken cache read *settles* instead of hanging every collector forever.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class UpgradeRepoFossTest {

    @get:Rule val tempFolder = TemporaryFolder()

    private val json: Json = SerializationModule.json()
    private val storeScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val record = FossUpgrade(
        upgradedAt = Instant.EPOCH,
        upgradeType = FossUpgrade.Type.GITHUB_SPONSORS,
    )

    @After fun teardown() {
        storeScope.cancel()
    }

    private fun webpageTool(opened: Boolean = false) = object : WebpageTool(
        ApplicationProvider.getApplicationContext<Context>(),
    ) {
        override fun open(url: String): Boolean = opened
    }

    private fun realCache(): FossCache {
        val store = PreferenceDataStoreFactory.create(scope = storeScope) {
            File(tempFolder.newFolder(), "test.preferences_pb")
        }
        return FossCache(AppDataStore(store), json)
    }

    private fun cacheOver(data: Flow<Preferences>) = FossCache(
        AppDataStore(
            object : DataStore<Preferences> {
                override val data: Flow<Preferences> = data
                override suspend fun updateData(
                    transform: suspend (t: Preferences) -> Preferences,
                ): Preferences = throw IOException("cache broken")
            },
        ),
        json,
    )

    private fun storedPreferences(record: FossUpgrade): Preferences = mutablePreferencesOf(
        stringPreferencesKey("upgrade.foss.v1") to json.encodeToString(FossUpgrade.serializer(), record),
    )

    @Test fun `an absent record maps to a settled free info`(): Unit = runBlocking {
        val repo = UpgradeRepoFoss(realCache(), webpageTool())

        withTimeout(TIMEOUT_MS) {
            repo.upgradeInfo.first().apply {
                type shouldBe UpgradeRepo.Type.FOSS
                isPro shouldBe false
                upgradedAt shouldBe null
                error shouldBe null
                // A local cache read is authoritative from the first emission — there is no
                // handshake to wait out, so the UI gates must never see an unsettled FOSS state.
                isSettled shouldBe true
            }
        }
    }

    @Test fun `a stored record maps to pro with its supporter date`(): Unit = runBlocking {
        val cache = realCache()
        val repo = UpgradeRepoFoss(cache, webpageTool())

        withTimeout(TIMEOUT_MS) {
            cache.upgrade.value(record)

            repo.upgradeInfo.first { it.isPro }.apply {
                upgradedAt shouldBe Instant.EPOCH
                isSettled shouldBe true
                error shouldBe null
            }
        }
    }

    @Test fun `a failing cache read surfaces as a settled error info instead of hanging`(): Unit = runBlocking {
        val repo = UpgradeRepoFoss(cacheOver(flow { throw IOException("cache broken") }), webpageTool())

        withTimeout(TIMEOUT_MS) {
            repo.upgradeInfo.first().apply {
                // Type and message: a bare non-null check would also pass on a swallow-and-wrap.
                error.shouldBeInstanceOf<IOException>().message shouldBe "cache broken"
                isPro shouldBe false
                // The UI must be able to render this: an unsettled error is an endless spinner.
                isSettled shouldBe true
            }
        }
    }

    @Test fun `a late cache failure keeps the last known entitlement`(): Unit = runBlocking {
        val repo = UpgradeRepoFoss(
            cacheOver(
                flow {
                    emit(storedPreferences(record))
                    throw IOException("cache broken later")
                },
            ),
            webpageTool(),
        )

        withTimeout(TIMEOUT_MS) {
            val infos = repo.upgradeInfo.take(2).toList()

            infos[0].apply {
                isPro shouldBe true
                error shouldBe null
            }
            // The entitlement we already saw must survive the read failure — revoking Pro would kick
            // a supporter back to the pitch over a transient storage problem.
            infos[1].apply {
                isPro shouldBe true
                error.shouldBeInstanceOf<IOException>()
            }
        }
    }

    @Test fun `an empty snapshot is not mistaken for a failure`(): Unit = runBlocking {
        val repo = UpgradeRepoFoss(cacheOver(flow { emit(emptyPreferences()) }), webpageTool())

        withTimeout(TIMEOUT_MS) {
            repo.upgradeInfo.first().apply {
                isPro shouldBe false
                error shouldBe null
            }
        }
    }

    @Test fun `persistUpgrade is create-only-if-absent`(): Unit = runBlocking {
        val cache = realCache()
        val repo = UpgradeRepoFoss(cache, webpageTool())

        withTimeout(TIMEOUT_MS) {
            cache.upgrade.value() shouldBe null

            // Create path: a fresh unlock stamps "now", bracketed so the assertion doesn't depend on
            // a fixed clock.
            val before = Instant.now()
            repo.persistUpgrade() shouldBe true
            val after = Instant.now()

            val created = cache.upgrade.value()!!
            created.upgradeType shouldBe FossUpgrade.Type.GITHUB_SPONSORS
            (created.upgradedAt >= before && created.upgradedAt <= after) shouldBe true

            // The regression this guards: a second persist must keep the existing record, not
            // overwrite the user-visible "supporter since" date with a fresh timestamp. Asserted via
            // the returned Boolean too, so a timestamp collision can't make it vacuously true.
            repo.persistUpgrade() shouldBe false
            cache.upgrade.value() shouldBe created
        }
    }

    @Test fun `the sponsor page result is reported to the caller`() {
        UpgradeRepoFoss(realCache(), webpageTool(opened = false)).openGithubSponsorsPage() shouldBe false
        UpgradeRepoFoss(realCache(), webpageTool(opened = true)).openGithubSponsorsPage() shouldBe true
    }

    private companion object {
        const val TIMEOUT_MS = 10_000L
    }
}
