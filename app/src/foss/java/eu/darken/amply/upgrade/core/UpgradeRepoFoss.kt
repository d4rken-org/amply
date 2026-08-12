package eu.darken.amply.upgrade.core

import eu.darken.amply.common.WebpageTool
import eu.darken.amply.common.debug.logging.Logging.Priority.WARN
import eu.darken.amply.common.debug.logging.asLog
import eu.darken.amply.common.debug.logging.log
import eu.darken.amply.common.debug.logging.logTag
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.shareIn
import java.time.Instant
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The FOSS entitlement: a trust-based unlock, granted after a real visit to the GitHub Sponsors
 * page. There is no receipt to verify, so the record is purely local — which is also why the unlock
 * is created once and then never overwritten.
 */
@Singleton
class UpgradeRepoFoss @Inject constructor(
    private val fossCache: FossCache,
    private val webpageTool: WebpageTool,
) : UpgradeRepo {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override val storeSite: String = STORE_SITE
    override val upgradeSite: String = UPGRADE_SITE
    override val betaSite: String = BETA_SITE

    private val refreshTrigger = MutableStateFlow(UUID.randomUUID())

    // Written only from the sharing coroutine (single collector) — no synchronization needed.
    // Recorded INSIDE the flatMapLatest block, upstream of its channel buffer: a downstream onEach
    // can still be waiting on a buffered emission when the inner flow throws, and the catch below
    // would then read a stale (null) value and revoke an entitlement we already saw.
    private var lastKnownInfo: Info? = null

    override val upgradeInfo: Flow<UpgradeRepo.Info> = refreshTrigger
        .flatMapLatest {
            fossCache.upgrade.flow
                .map { data ->
                    if (data == null) {
                        Info()
                    } else {
                        Info(
                            isPro = true,
                            upgradedAt = data.upgradedAt,
                            fossUpgradeType = data.upgradeType,
                        )
                    }
                }
                // Same coroutine as the throw below, so the ordering is guaranteed. Only
                // successfully mapped elements pass here — catch emissions go straight downstream
                // and never record themselves as a last known state.
                .onEach { lastKnownInfo = it }
                .catch { e ->
                    // A SharedFlow cannot fail: without this, a thrown cache read dies inside
                    // shareIn's sharing coroutine and every collector hangs forever (gates stuck,
                    // checkSponsorReturn suspended mid-unlock). The catch sits INSIDE flatMapLatest
                    // so the error completes only this inner subscription — refresh() resubscribes
                    // the cache and recovery stays possible. Last-known preservation: a late read
                    // failure must not revoke an entitlement we already saw; the error rides on the
                    // previous Info instead.
                    if (e is CancellationException) throw e
                    log(TAG, WARN) { "upgradeInfo read failed: ${e.asLog()}" }
                    emit((lastKnownInfo ?: Info()).copy(error = e))
                }
        }
        .shareIn(scope, SharingStarted.WhileSubscribed(3000L, 0L), replay = 1)

    // Synchronous so the caller learns whether the page actually opened: the unlock heuristic only
    // arms on a successful launch, and a fire-and-forget coroutine can't report that back.
    fun openGithubSponsorsPage(): Boolean {
        log(TAG) { "openGithubSponsorsPage()" }
        return webpageTool.open(upgradeSite)
    }

    /**
     * Create-only-if-absent inside the store transaction: an existing record (and its `upgradedAt` —
     * the user-visible "supporter since" date) is never replaced. A ViewModel-level isPro guard alone
     * is not race-free: it reads a shareIn replay that can be stale.
     *
     * @return true if a new record was created, false if an existing record was kept.
     */
    internal suspend fun persistUpgrade(): Boolean {
        log(TAG) { "persistUpgrade()" }
        val updated = fossCache.upgrade.update { existing ->
            existing ?: FossUpgrade(
                upgradedAt = Instant.now(),
                upgradeType = FossUpgrade.Type.GITHUB_SPONSORS,
            )
        }
        // A returned transaction proves the store is readable again: revive a possibly error-stuck
        // inner flow so the record propagates to collectors still holding the error replay.
        refresh()
        val previous = updated.old
        return if (previous == null) {
            true
        } else {
            log(TAG, WARN) { "persistUpgrade(): Record already exists (upgradedAt=${previous.upgradedAt}), keeping it" }
            false
        }
    }

    override suspend fun refresh() {
        log(TAG) { "refresh()" }
        refreshTrigger.value = UUID.randomUUID()
    }

    data class Info(
        override val isPro: Boolean = false,
        override val upgradedAt: Instant? = null,
        val fossUpgradeType: FossUpgrade.Type? = null,
        override val error: Throwable? = null,
    ) : UpgradeRepo.Info {
        override val type: UpgradeRepo.Type = UpgradeRepo.Type.FOSS

        // The FOSS entitlement is a local cache read — authoritative from the first emission, there
        // is no billing handshake to wait out.
        override val isSettled: Boolean = true
    }

    companion object {
        private const val STORE_SITE = "https://github.com/d4rken-org/amply"
        private const val UPGRADE_SITE = "https://github.com/sponsors/d4rken"
        private const val BETA_SITE = "https://github.com/d4rken-org/amply/releases"
        private val TAG = logTag("Upgrade", "Foss", "Repo")
    }
}
