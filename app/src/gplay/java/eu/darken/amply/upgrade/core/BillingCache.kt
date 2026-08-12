package eu.darken.amply.upgrade.core

import eu.darken.amply.common.AppDataStore
import eu.darken.amply.common.datastore.createValue
import eu.darken.amply.common.datastore.value
import eu.darken.amply.common.debug.logging.Logging.Priority.WARN
import eu.darken.amply.common.debug.logging.asLog
import eu.darken.amply.common.debug.logging.log
import eu.darken.amply.common.debug.logging.logTag
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The local grace bookkeeping. One record rather than three keys, because the three values are only
 * meaningful *together*: the timestamp gates the grace period, the SKU decides how long that period
 * is, and the episode start says how long Play has been failing to re-confirm. Reading them
 * separately could observe a combination that never existed.
 */
@Serializable
data class GraceState(
    /** When Play last confirmed a known purchase (epoch millis, 0 = never). */
    @SerialName("lastProAt") val lastProAt: Long = 0L,
    /** Which SKU that confirmation was for — a one-time purchase gets a longer grace window. */
    @SerialName("lastProSku") val lastProSku: String = "",
    /** Start of the current "fresh data can't confirm Pro" episode (0 = none/confirmed). */
    @SerialName("proUnconfirmedAt") val proUnconfirmedAt: Long = 0L,
)

@Singleton
class BillingCache @Inject constructor(
    dataStore: AppDataStore,
    json: Json,
) {

    /**
     * `fallbackToDefault = true`: a corrupt record reads as "never confirmed", which costs a recent
     * purchaser their grace window but is recoverable — the next successful Play round-trip rewrites
     * it. Throwing would take down the entitlement flow that this only decorates.
     */
    internal val state = dataStore.createValue(
        key = "upgrade.gplay.grace.v1",
        defaultValue = GraceState(),
        json = json,
        fallbackToDefault = true,
    )

    // Test seam: the bounded reads/writes below run on real dispatchers, so a virtual-time test
    // cannot advance the production bound.
    internal var cacheTimeoutMs: Long = CACHE_TIMEOUT_MS

    val lastProStateAt: Flow<Long> = state.flow.map { it.lastProAt }.distinctUntilChanged()
    val proUnconfirmedSince: Flow<Long> = state.flow.map { it.proUnconfirmedAt }.distinctUntilChanged()

    /**
     * Bounded on purpose: a wedged DataStore file lock would otherwise hang the caller forever. A
     * timeout must NOT fall back to the default record — that would report "never bought" for an
     * install whose evidence merely couldn't be read.
     */
    suspend fun snapshot(): GraceState = withTimeoutOrNull(cacheTimeoutMs) { state.value() } ?: run {
        log(TAG, WARN) { "snapshot() timed out after ${cacheTimeoutMs}ms" }
        throw IOException("BillingCache snapshot timed out after ${cacheTimeoutMs}ms")
    }

    /**
     * One transaction for all three values: none of it may be observable half-updated. [at] is the
     * confirmation's OCCURRENCE time (commit time of the Play round-trip). The unconfirmed episode is
     * closed only if it began at or before [at]: a failure that occurred AFTER this confirmation —
     * delivered to the entitlement layer out of order — opened a still-valid episode that this older
     * confirmation must not erase.
     */
    suspend fun stampLastProState(skuId: String, at: Long) {
        // Fail-soft: this decorates the entitlement path, it must never be the thing that blocks it.
        // A wedged file lock (timeout) and a broken write (IOException, corrupt file, no disk space)
        // are the same to the caller — the stamp is lost, the bookkeeping around it carries on.
        try {
            withTimeoutOrNull(cacheTimeoutMs) {
                state.update { current ->
                    current.copy(
                        lastProSku = skuId,
                        lastProAt = at,
                        proUnconfirmedAt = if (current.proUnconfirmedAt in 1..at) 0L else current.proUnconfirmedAt,
                    )
                }
            } ?: log(TAG, WARN) { "stampLastProState($skuId, $at) timed out after ${cacheTimeoutMs}ms, write skipped" }
        } catch (e: CancellationException) {
            // Caught before the general case on purpose: our caller going away is not a write
            // failure, and swallowing it would break their structured concurrency.
            throw e
        } catch (e: Exception) {
            log(TAG, WARN) { "stampLastProState($skuId, $at) failed, write skipped: ${e.asLog()}" }
        }
    }

    /**
     * A fresh reconciliation failed to confirm a known purchase. Starts the unconfirmed-episode clock
     * that delays the grace diagnostics on the upgrade screen.
     *
     * Set-if-unset, so follow-up failures never refresh it; stamps from an earlier episode (older
     * than the last confirmation) or from the future (clock changes) are replaced. [occurredAt] is
     * WHEN the failure happened, not when it was processed: a failure that happened BEFORE the latest
     * confirmation — one buffered during an outage but consumed after a later retry succeeded — is
     * rejected instead of reopening an episode Play already closed.
     *
     * [graceWindowFor] maps the stored SKU id to its grace window; it is evaluated INSIDE the
     * transaction, so the window and the timestamp it is compared against always come from the same
     * record. Fail-quiet: purely informational, it must never affect entitlement handling.
     */
    suspend fun markProUnconfirmed(occurredAt: Long, graceWindowFor: (String) -> Long) {
        try {
            withTimeoutOrNull(cacheTimeoutMs) {
                state.update { current ->
                    val sinceConfirm = occurredAt - current.lastProAt
                    // sinceConfirm <= 0 rejects failures superseded by a later confirmation AND
                    // future confirmations (clock moved backwards) — both would otherwise pass the
                    // window check and (re)stamp the episode.
                    if (current.lastProAt <= 0L ||
                        sinceConfirm <= 0L ||
                        sinceConfirm >= graceWindowFor(current.lastProSku)
                    ) {
                        return@update current
                    }
                    val stale = current.proUnconfirmedAt <= 0L ||
                        current.proUnconfirmedAt < current.lastProAt ||
                        current.proUnconfirmedAt > occurredAt
                    if (stale) current.copy(proUnconfirmedAt = occurredAt) else current
                }
            } ?: log(TAG, WARN) { "markProUnconfirmed($occurredAt) timed out after ${cacheTimeoutMs}ms" }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log(TAG, WARN) { "markProUnconfirmed($occurredAt) failed: ${e.asLog()}" }
        }
    }

    companion object {
        private const val CACHE_TIMEOUT_MS = 2_000L
        private val TAG = logTag("Upgrade", "Gplay", "BillingCache")
    }
}
