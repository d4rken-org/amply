package eu.darken.amply.stats.core

import eu.darken.amply.common.debug.logging.Logging
import eu.darken.amply.common.debug.logging.asLog
import eu.darken.amply.common.debug.logging.log
import eu.darken.amply.common.debug.logging.logTag
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.shareIn
import javax.inject.Inject
import javax.inject.Singleton

/** The charge-time history model, or why there isn't one yet. */
sealed interface ChargeTimeModelState {
    /** Not folded yet. Distinct from an empty model: it is not a statement about the user's history. */
    data object Loading : ChargeTimeModelState

    /** The stats pipeline failed. Our outage must stay distinguishable from an empty history. */
    data object Unavailable : ChargeTimeModelState

    data class Ready(val model: ChargeTimeModel) : ChargeTimeModelState
}

/**
 * Owns the charge-time history fold **once** for every consumer.
 *
 * Two surfaces show an estimate (the battery hub's card and the dashboard's charging card), and
 * neither may load and re-extract ten sessions of samples on its own — hence a singleton with a
 * shared, replayed flow rather than a per-ViewModel one.
 *
 * The refresh trigger is the **identities** of the recent finished sessions plus the retention
 * window, with an explicit `distinctUntilChanged`. Room invalidates per *table*, so every per-tick
 * update to the open session re-emits an unchanged list; without the guard the whole ten-session
 * extraction would rerun every recorder tick during a charge. A bare count would be the wrong key in
 * the other direction: a sealed charge and a purged one landing in the same invalidation window
 * cancel out, leaving the fold quoting history that is gone. The retention setting rides along
 * because it is what decides which samples the fold may use.
 *
 * There is deliberately **no** `onStart { emit(Loading) }`. It would sit upstream of the `shareIn`
 * and therefore re-run on every upstream restart, so a subscriber returning after the stop timeout
 * would get the replayed `Ready` followed by a fresh `Loading` — the card visibly blinking back to
 * its loading line. First-load `Loading` comes from the downstream initial state instead.
 *
 * Nothing here touches Room until [states] is actually collected: the trigger flow is built inside
 * the `flow` block, so merely injecting this class never creates `stats.db`.
 */
@Singleton
class ChargeTimeModelSource @Inject constructor(
    private val repository: ChargeStatsRepository,
    private val statsPreferences: StatsPreferences,
    @param:StatsDispatcher private val dispatcher: CoroutineDispatcher,
) {
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)

    val states: Flow<ChargeTimeModelState> = flow<ChargeTimeModelState> {
        emitAll(
            combine(
                repository.recentFinishedSessionIds(),
                statsPreferences.retentionDays.flow,
            ) { sessionIds, retentionDays -> sessionIds to retentionDays }
                .distinctUntilChanged()
                .map { (sessionIds, retentionDays) ->
                    log(TAG) { "Rebuilding the charge-time model over ${sessionIds.size} finished sessions" }
                    val batch = repository.bandObservations(
                        cutoffWallMillis = StatsRetention.cutoffWallMillis(
                            nowWallMillis = System.currentTimeMillis(),
                            days = StatsRetention.clampDays(retentionDays),
                        ),
                    )
                    ChargeTimeModelState.Ready(
                        ChargeTimeEstimator.buildModel(
                            observations = batch.observations,
                            sessionPowerMilliwatts = batch.sessionPowerMilliwatts,
                        ),
                    )
                },
        )
    }
        .catch { e ->
            log(TAG, Logging.Priority.ERROR) { "Charge-time model failed: ${e.asLog()}" }
            emit(ChargeTimeModelState.Unavailable)
        }
        // The fold reads every sample of ten sessions; it never runs on the caller's thread.
        .flowOn(dispatcher)
        .shareIn(scope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), replay = 1)

    private companion object {
        val TAG = logTag("Stats", "ChargeTimeModelSource")
        const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}
