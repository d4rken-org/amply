package eu.darken.amply.upgrade.core

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import eu.darken.amply.common.debug.logging.Logging.Priority.INFO
import eu.darken.amply.common.debug.logging.Logging.Priority.WARN
import eu.darken.amply.common.debug.logging.asLog
import eu.darken.amply.common.debug.logging.log
import eu.darken.amply.common.debug.logging.logTag
import eu.darken.amply.main.core.SurfaceUpdater
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Keeps the tile and the home-screen widget honest about the entitlement.
 *
 * Both render themselves from a snapshot taken whenever the system last asked them to — which can be
 * long before a purchase lands or a subscription lapses. Nothing else pushes them: the charging
 * surfaces are refreshed by the charge service, and an entitlement change never goes through it. So a
 * user who just bought the upgrade would keep staring at a locked widget until something unrelated
 * happened to re-render it.
 *
 * Only *settled* transitions trigger a push. The gplay entitlement reports non-Pro until billing
 * connects, so reacting to unsettled emissions would flash the locked rendering at a paying user on
 * every cold start.
 */
@Singleton
class UpgradeSurfaceSync @Inject constructor(
    @ApplicationContext private val context: Context,
    private val upgradeRepo: UpgradeRepo,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    fun start() {
        log(TAG) { "start()" }
        scope.launch {
            upgradeRepo.upgradeInfo
                .map { info -> info.isPro.takeIf { info.isSettled } }
                .filterNotNull()
                .distinctUntilChanged()
                .onEach { isPro ->
                    log(TAG, INFO) { "Entitlement settled at isPro=$isPro, refreshing tile and widget" }
                    try {
                        SurfaceUpdater.updateNow(context)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        // A failed surface push is cosmetic; the next render corrects itself.
                        log(TAG, WARN) { "Surface update failed: ${e.asLog()}" }
                    }
                }
                // Process-lifetime collector: an upstream failure must not leave the surfaces
                // permanently unsynchronised for the rest of the session.
                .retryWhen { cause, _ ->
                    if (cause is CancellationException) return@retryWhen false
                    log(TAG, WARN) { "Upgrade surface sync failed, resubscribing: ${cause.asLog()}" }
                    true
                }
                .collect { }
        }
    }

    companion object {
        private val TAG = logTag("Upgrade", "SurfaceSync")
    }
}
