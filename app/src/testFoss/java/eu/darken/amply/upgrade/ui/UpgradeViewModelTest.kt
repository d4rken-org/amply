package eu.darken.amply.upgrade.ui

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import eu.darken.amply.R
import eu.darken.amply.common.AppDataStore
import eu.darken.amply.common.WebpageTool
import eu.darken.amply.common.datastore.value
import eu.darken.amply.common.serialization.SerializationModule
import eu.darken.amply.upgrade.core.FossCache
import eu.darken.amply.upgrade.core.FossUpgrade
import eu.darken.amply.upgrade.core.UpgradeRepoFoss
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSystemClock
import java.io.File
import java.io.IOException
import java.time.Duration
import java.time.Instant

/**
 * The FOSS unlock is granted on trust after a *real* visit to the sponsor page, so the arming and
 * return rules are the whole security model: never arm without a page, never unlock on a bounce, and
 * never lose a genuine visit to a failed write.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class UpgradeViewModelTest {

    @get:Rule val tempFolder = TemporaryFolder()

    private val json: Json = SerializationModule.json()
    private val storeScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private class CountingWebpageTool(
        context: Context,
        private val opened: Boolean,
    ) : WebpageTool(context) {
        var opens = 0
        override fun open(url: String): Boolean {
            opens++
            return opened
        }
    }

    private fun webpageTool(opened: Boolean) = CountingWebpageTool(
        ApplicationProvider.getApplicationContext<Context>(),
        opened,
    )

    private fun realCache(): FossCache {
        val store = PreferenceDataStoreFactory.create(scope = storeScope) {
            File(tempFolder.newFolder(), "test.preferences_pb")
        }
        return FossCache(AppDataStore(store), json)
    }

    private fun writeFailingCache() = FossCache(
        AppDataStore(
            object : DataStore<Preferences> {
                override val data: Flow<Preferences> = flowOf(emptyPreferences())
                override suspend fun updateData(
                    transform: suspend (t: Preferences) -> Preferences,
                ): Preferences = throw IOException("disk full")
            },
        ),
        json,
    )

    @Before fun setup() {
        // viewModelScope dispatches on Main; Unconfined runs the launch body inline up to its first
        // real suspension, so the marker bookkeeping is observable without pumping a looper.
        Dispatchers.setMain(Dispatchers.Unconfined)
    }

    @After fun teardown() {
        Dispatchers.resetMain()
        storeScope.cancel()
    }

    private fun viewModel(
        cache: FossCache,
        webpageTool: WebpageTool,
        handle: SavedStateHandle = SavedStateHandle(),
    ) = UpgradeViewModel(handle, UpgradeRepoFoss(cache, webpageTool))

    private fun awaitTrue(message: String, block: () -> Boolean) {
        val deadline = System.currentTimeMillis() + AWAIT_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            if (block()) return
            Thread.sleep(10)
        }
        throw AssertionError(message)
    }

    @Test fun `a sponsor page that did not open never arms the heuristic`() {
        val tool = webpageTool(opened = false)
        val vm = viewModel(realCache(), tool)

        vm.goGithubSponsors()

        tool.opens shouldBe 1
        // Otherwise any unrelated later background round-trip would grant supporter status with no
        // page ever having been shown.
        vm.hasPendingSponsorLaunch() shouldBe false
    }

    @Test fun `a sponsor page that opened arms the heuristic`() {
        val vm = viewModel(realCache(), webpageTool(opened = true))

        vm.goGithubSponsors()

        vm.hasPendingSponsorLaunch() shouldBe true
    }

    @Test fun `a second tap while a return is pending is ignored`() {
        val tool = webpageTool(opened = true)
        val vm = viewModel(realCache(), tool)

        vm.goGithubSponsors()
        vm.goGithubSponsors()

        tool.opens shouldBe 1
    }

    @Test fun `the status view's donate button never arms the heuristic`() {
        val tool = webpageTool(opened = true)
        val vm = viewModel(realCache(), tool)

        vm.openSponsors()

        tool.opens shouldBe 1
        // An existing supporter re-visiting the page has nothing left to unlock.
        vm.hasPendingSponsorLaunch() shouldBe false
    }

    @Test fun `coming back too quickly shows the snackbar and unlocks nothing`(): Unit = runBlocking {
        val cache = realCache()
        val vm = viewModel(cache, webpageTool(opened = true))

        vm.goGithubSponsors()
        ShadowSystemClock.advanceBy(Duration.ofSeconds(2))
        vm.checkSponsorReturn()

        withTimeout(AWAIT_TIMEOUT_MS) {
            vm.snackbarEvents.first() shouldBe R.string.upgrade_screen_sponsor_return_too_quick
        }
        cache.upgrade.value() shouldBe null
        vm.hasPendingSponsorLaunch() shouldBe false
    }

    @Test fun `coming back after the delay persists the unlock and thanks the user`(): Unit = runBlocking {
        val cache = realCache()
        val vm = viewModel(cache, webpageTool(opened = true))

        vm.goGithubSponsors()
        ShadowSystemClock.advanceBy(Duration.ofSeconds(6))
        vm.checkSponsorReturn()

        withTimeout(AWAIT_TIMEOUT_MS) {
            vm.toastEvents.first() shouldBe R.string.upgrade_screen_thanks_toast
        }
        cache.upgrade.value()!!.upgradeType shouldBe FossUpgrade.Type.GITHUB_SPONSORS
    }

    @Test fun `an existing supporter returns quietly`(): Unit = runBlocking {
        val cache = realCache()
        val existing = FossUpgrade(
            upgradedAt = Instant.EPOCH,
            upgradeType = FossUpgrade.Type.GITHUB_SPONSORS,
        )
        cache.upgrade.value(existing)
        val vm = viewModel(cache, webpageTool(opened = true))

        vm.goGithubSponsors()
        ShadowSystemClock.advanceBy(Duration.ofSeconds(6))
        vm.checkSponsorReturn()

        awaitTrue("marker was never consumed") { !vm.hasPendingSponsorLaunch() }
        // No second thanks-toast, and — the part that matters — the original "supporter since" date
        // survives untouched.
        cache.upgrade.value() shouldBe existing
    }

    // The ViewModel is activity-scoped, so the visit binding — not the ViewModel's lifetime — is what
    // decides which view an entry gets. These cover the binding being taken, released, and taken
    // again, including an entry that starts on top of a previous visit's leftovers.

    private fun collectViews(vm: UpgradeViewModel, into: MutableList<FossUpgradeView?>): CoroutineScope {
        val scope = CoroutineScope(Dispatchers.Unconfined)
        scope.launch { vm.state.collect { into += it.view } }
        return scope
    }

    @Test fun `each visit binds its own view and leaving releases the binding`(): Unit = runBlocking {
        val vm = viewModel(realCache(), webpageTool(opened = true))
        val seen = mutableListOf<FossUpgradeView?>()
        val scope = collectViews(vm, seen)
        try {
            vm.onVisitStart(manage = true)
            awaitTrue("the manage entry never reached the status view") {
                vm.state.value.view == FossUpgradeView.STATUS_FREE
            }

            // Leaving must go back to neutral, or the next entry renders this visit's view first.
            vm.onVisitEnd()
            awaitTrue("the binding was never released") { vm.state.value.view == null }

            vm.onVisitStart(manage = false)
            awaitTrue("the plain entry never reached the pitch") {
                vm.state.value.view == FossUpgradeView.PITCH
            }

            vm.onVisitEnd()
            awaitTrue("the second binding was never released") { vm.state.value.view == null }

            // And the state flow is still live after a release: set-to-null keeps the handle's flow
            // instance the state combine captured, which handle.remove() would detach.
            vm.onVisitStart(manage = true)
            awaitTrue("the flow went deaf after the binding was released") {
                vm.state.value.view == FossUpgradeView.STATUS_FREE
            }
        } finally {
            scope.cancel()
        }
    }

    @Test fun `a manage entry over a restored plain binding never shows the pitch`(): Unit = runBlocking {
        val cache = realCache()
        cache.upgrade.value(
            FossUpgrade(upgradedAt = Instant.EPOCH, upgradeType = FossUpgrade.Type.GITHUB_SPONSORS),
        )
        // Process death took the screen but not the handle: the previous (plain) visit's keys are
        // restored, and they say "pitch". Key names are the ViewModel's own, spelled out because they
        // are the wire format a restored handle arrives with.
        val restored = SavedStateHandle(
            mapOf(
                "upgrade_manage" to false,
                "show_upgrade_options" to true,
            ),
        )
        val vm = viewModel(cache, webpageTool(opened = true), restored)

        vm.onVisitStart(manage = true)

        val seen = mutableListOf<FossUpgradeView?>()
        val scope = collectViews(vm, seen)
        try {
            awaitTrue("the manage entry never reached the upgraded status") {
                vm.state.value.view == FossUpgradeView.STATUS_UPGRADED
            }
            // An upgraded user opening the status entry: the pitch is exactly what must not appear,
            // because the host dismisses the screen on a pitch-plus-upgraded combination.
            seen.contains(FossUpgradeView.PITCH) shouldBe false

            // The composition root can flip `manage` in place (a widget's "open upgrade" intent
            // landing on the open status view); the host rebinds, and the pitch is then correct.
            vm.onVisitStart(manage = false)
            awaitTrue("the rebind never reached the pitch") {
                vm.state.value.view == FossUpgradeView.PITCH
            }
        } finally {
            scope.cancel()
        }
    }

    @Test fun `a failed write restores the marker so the visit can still be redeemed`() {
        val vm = viewModel(writeFailingCache(), webpageTool(opened = true))

        vm.goGithubSponsors()
        ShadowSystemClock.advanceBy(Duration.ofSeconds(6))
        vm.checkSponsorReturn()

        // The marker is consumed at the start of the check, so seeing it back means the failure path
        // put it there — a genuine sponsor visit must not be eaten by a transient storage failure.
        awaitTrue("marker was not restored after the failed write") { vm.hasPendingSponsorLaunch() }
    }

    private companion object {
        const val AWAIT_TIMEOUT_MS = 10_000L
    }
}
