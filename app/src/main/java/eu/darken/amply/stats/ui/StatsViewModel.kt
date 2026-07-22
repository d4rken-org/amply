package eu.darken.amply.stats.ui

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import eu.darken.amply.fullcharge.core.ChargeSessionService
import eu.darken.amply.stats.core.ChargeCurvePoint
import eu.darken.amply.stats.core.ChargeSessionSummary
import eu.darken.amply.stats.core.ChargeStatsRecorder
import eu.darken.amply.stats.core.ChargeStatsRepository
import eu.darken.amply.stats.core.StatsPreferences
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class StatsUiState(
    val captureEnabled: Boolean = false,
    val lastCaptureWallMillis: Long? = null,
    val sessions: List<ChargeSessionSummary> = emptyList(),
)

data class StatsDetailState(
    val summary: ChargeSessionSummary?,
    val curve: List<ChargeCurvePoint>,
)

@HiltViewModel
class StatsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val preferences: StatsPreferences,
    private val repository: ChargeStatsRepository,
    private val recorder: ChargeStatsRecorder,
) : ViewModel() {

    val state: StateFlow<StatsUiState> = combine(
        preferences.captureEnabled,
        preferences.lastCaptureWallMillis,
        repository.recentSessions(),
    ) { enabled, lastCapture, sessions ->
        StatsUiState(captureEnabled = enabled, lastCaptureWallMillis = lastCapture, sessions = sessions)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), StatsUiState())

    private val selectedSessionId = MutableStateFlow<Long?>(null)

    @OptIn(ExperimentalCoroutinesApi::class)
    val detailState: StateFlow<StatsDetailState?> = selectedSessionId
        .flatMapLatest { id ->
            if (id == null) {
                flowOf<StatsDetailState?>(null)
            } else {
                flow<StatsDetailState?> {
                    // A closed session's samples are immutable, so the curve is fetched once.
                    val curve = runCatching { repository.curve(id) }.getOrDefault(emptyList())
                    emitAll(repository.session(id).map { StatsDetailState(it, curve) })
                }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), null)

    fun openSession(id: Long) {
        selectedSessionId.value = id
    }

    fun closeSession() {
        selectedSessionId.value = null
    }

    /**
     * Enable/disable capture. Enabling is routed through the activity's notification-permission flow
     * first (the always-on service shows a persistent notification). Disabling seals any open session
     * before the service is nudged to re-evaluate and stop.
     */
    fun setCaptureEnabled(enabled: Boolean) {
        viewModelScope.launch {
            // Durable state for keep-alive / isEnabled, plus an ordered recorder command that seals
            // any open session on disable before the service is nudged to re-evaluate and stop.
            preferences.setCaptureEnabled(enabled)
            recorder.setEnabled(enabled)
            nudgeService()
        }
    }

    fun clearData() {
        repository.clearAll()
    }

    private fun nudgeService() {
        val intent = Intent(context, ChargeSessionService::class.java).setAction(ChargeSessionService.ACTION_MONITOR)
        ContextCompat.startForegroundService(context, intent)
    }

    private companion object {
        const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}
