package eu.darken.amply.stats.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Last outcome of starting the charge-session service on behalf of stats capture. The service is
 * what delivers battery ticks to the recorder; a swallowed start failure otherwise leaves the
 * dashboard claiming "recording is starting" forever. Both nudge paths (dashboard resume, stats
 * toggle) report here so the stats card can distinguish "starting" from "couldn't start".
 */
@Singleton
class CaptureServiceHealth @Inject constructor() {

    enum class NudgeOutcome { IDLE, DISPATCHED, FAILED }

    private val _state = MutableStateFlow(NudgeOutcome.IDLE)
    val state: StateFlow<NudgeOutcome> = _state

    fun reportDispatched() {
        _state.value = NudgeOutcome.DISPATCHED
    }

    fun reportFailed() {
        _state.value = NudgeOutcome.FAILED
    }
}
