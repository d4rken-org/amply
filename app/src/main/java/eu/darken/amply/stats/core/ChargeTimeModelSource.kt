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
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
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
 * The refresh trigger is the finished-session count with an explicit `distinctUntilChanged`. Room
 * invalidates per *table*, so every per-tick update to the open session re-emits an unchanged count;
 * without the guard the whole ten-session extraction would rerun every recorder tick during a
 * charge.
 *
 * Nothing here touches Room until [states] is actually collected: the trigger flow is built inside
 * the `flow` block, so merely injecting this class never creates `stats.db`.
 */
@Singleton
class ChargeTimeModelSource @Inject constructor(
    private val repository: ChargeStatsRepository,
    @param:StatsDispatcher private val dispatcher: CoroutineDispatcher,
) {
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)

    val states: Flow<ChargeTimeModelState> = flow<ChargeTimeModelState> {
        emitAll(
            repository.sessionCount()
                .distinctUntilChanged()
                .map { count ->
                    log(TAG) { "Rebuilding the charge-time model over $count finished sessions" }
                    val batch = repository.bandObservations()
                    ChargeTimeModelState.Ready(
                        ChargeTimeEstimator.buildModel(
                            observations = batch.observations,
                            sessionPowerMilliwatts = batch.sessionPowerMilliwatts,
                        ),
                    )
                },
        )
    }
        .onStart { emit(ChargeTimeModelState.Loading) }
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
