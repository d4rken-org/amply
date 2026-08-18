package eu.darken.amply.stats.ui

import android.os.BatteryManager
import eu.darken.amply.battery.core.BatteryReadout
import eu.darken.amply.stats.core.ChargeTimeBasis
import eu.darken.amply.stats.core.ChargeTimeEstimate
import eu.darken.amply.stats.core.ChargeTimeEstimator
import eu.darken.amply.stats.core.ChargeTimeModelState
import eu.darken.amply.stats.core.ChargingTypes
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf

/**
 * What the charge-time surfaces show.
 *
 * [Unavailable] is deliberately distinct from [NotEnoughData]: our own outage is not a statement
 * about the user's history.
 */
sealed interface ChargeTimeState {

    /** The history model hasn't been folded yet — a skeleton, never the empty copy. */
    data object Loading : ChargeTimeState

    /** The stats pipeline failed. */
    data object Unavailable : ChargeTimeState

    /** Recording is on and healthy, but too few charges have been observed to project anything. */
    data class NotEnoughData(val sessions: Int) : ChargeTimeState

    /**
     * A projection for the current level.
     *
     * [charging] is what decides the wording: a countdown toward a target the device is not moving
     * toward would be a false claim, so unplugged — or held at an OEM limit, where the platform
     * reports NOT_CHARGING while the session is still live — the same figures are presented as a
     * reference rather than a count down.
     */
    data class Ready(
        val estimate: ChargeTimeEstimate,
        val basis: ChargeTimeBasis,
        val charging: Boolean,
        val currentPercent: Int?,
    ) : ChargeTimeState
}

/**
 * Combines the cached history model with the **live** readout, so the estimate tracks the charge as
 * the level moves rather than being pinned to the level the model was folded at.
 *
 * [captureEnabled] gates the whole thing: with recording off there is nothing to project from, no
 * surface renders a card, and — crucially — the model provider is never invoked, so a user who never
 * enabled recording never gets `stats.db` created for them. [model] is a provider function for the
 * same reason the dashboard's stats slice uses one: it must not be a flow built at construction.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal fun chargeTimeStates(
    captureEnabled: Flow<Boolean>,
    readouts: Flow<BatteryReadout?>,
    model: () -> Flow<ChargeTimeModelState>,
): Flow<ChargeTimeState> = captureEnabled.flatMapLatest { enabled ->
    if (!enabled) {
        flowOf(ChargeTimeState.Loading)
    } else {
        flow {
            emitAll(
                combine(model(), readouts) { modelState, readout ->
                    chargeTimeState(modelState, readout)
                },
            )
        }.catch { emit(ChargeTimeState.Unavailable) }
    }
}

/** Pure projection of one model state against one readout. */
internal fun chargeTimeState(modelState: ChargeTimeModelState, readout: BatteryReadout?): ChargeTimeState =
    when (modelState) {
        ChargeTimeModelState.Loading -> ChargeTimeState.Loading
        ChargeTimeModelState.Unavailable -> ChargeTimeState.Unavailable
        is ChargeTimeModelState.Ready -> {
            val level = readout?.levelPercent
            val projection = level?.let {
                ChargeTimeEstimator.project(
                    model = modelState.model,
                    currentPercent = it,
                    chargingType = ChargingTypes.fromPluggedRaw(readout.plugged),
                )
            }
            if (projection == null) {
                ChargeTimeState.NotEnoughData(modelState.model.observedSessions)
            } else {
                ChargeTimeState.Ready(
                    estimate = projection.estimate,
                    basis = projection.basis,
                    // Both halves matter: a device on a charger that reports NOT_CHARGING is being
                    // held, not counted down.
                    charging = readout.onCharger &&
                        readout.status == BatteryManager.BATTERY_STATUS_CHARGING,
                    currentPercent = level,
                )
            }
        }
    }
