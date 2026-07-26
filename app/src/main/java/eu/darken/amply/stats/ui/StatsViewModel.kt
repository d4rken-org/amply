package eu.darken.amply.stats.ui

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import eu.darken.amply.common.debug.logging.Logging
import eu.darken.amply.common.debug.logging.asLog
import eu.darken.amply.common.debug.logging.log
import eu.darken.amply.common.debug.logging.logTag
import eu.darken.amply.fullcharge.core.ChargeSessionService
import eu.darken.amply.stats.core.CaptureServiceHealth
import eu.darken.amply.stats.core.ChargeCurvePoint
import eu.darken.amply.stats.core.ChargeSessionSummary
import eu.darken.amply.stats.core.ChargeStatsRecorder
import eu.darken.amply.stats.core.ChargeStatsRepository
import eu.darken.amply.stats.core.StatsPreferences
import eu.darken.amply.stats.core.StatsRetention
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * The recorded-session list. Three states, because a Room failure must not be indistinguishable from
 * "nothing recorded yet" — the empty copy is a claim about the user's data, not about our own health.
 */
sealed interface ChargeHistoryState {
    data object Loading : ChargeHistoryState
    data object Unavailable : ChargeHistoryState
    data class Ready(val sessions: List<ChargeSessionSummary>) : ChargeHistoryState
}

/**
 * Detail-screen state: null while the selection is still resolving (spinner); resolved with a null
 * [summary] when the session no longer exists (missing notice); otherwise the session's data.
 */
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
    private val serviceHealth: CaptureServiceHealth,
    private val savedStateHandle: SavedStateHandle,
) : ViewModel() {

    /**
     * The retention window, for the charging-history settings screen.
     *
     * Shared **eagerly**, unlike everything else here: with lazy sharing the screen's first frame
     * would render the placeholder default and then jump to the stored value. Collection starts with
     * the ViewModel and is DataStore-only, so it resolves long before the user navigates here — and it
     * still never opens `stats.db` (see [chargeHistoryStates] for the property that matters there).
     *
     * Eager sharing only helps if the ViewModel is actually constructed early, which is why
     * `MainActivity` collects this at its composition root rather than in the settings destination.
     */
    val retentionDays: StateFlow<Int> = preferences.retentionDays.flow
        .map(StatsRetention::clampDays)
        .stateIn(viewModelScope, SharingStarted.Eagerly, StatsRetention.DEFAULT_DAYS)

    // Collected only by the history screen. Deliberately NOT gated on captureEnabled: switching capture
    // off must not hide (or make unclearable) what was already recorded.
    val historyState: StateFlow<ChargeHistoryState> = chargeHistoryStates(
        recentSessions = { repository.recentSessions() },
    ).stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), ChargeHistoryState.Loading)

    // Backed by SavedStateHandle so the open detail screen survives process death: the restored
    // Activity comes back to STATS_SESSION_DETAIL (a saved destination), and the id it needs is
    // restored here too, rather than defaulting to null and stranding the screen on a spinner.
    private val selectedSessionId: StateFlow<Long?> =
        savedStateHandle.getStateFlow(KEY_SELECTED_SESSION, null)

    @OptIn(ExperimentalCoroutinesApi::class)
    val detailState: StateFlow<StatsDetailState?> = selectedSessionId
        .flatMapLatest { id ->
            if (id == null) {
                flowOf<StatsDetailState?>(null)
            } else {
                // Both flows are live: an open session's summary and curve keep updating as samples
                // land. A resolved-but-null summary means the row no longer exists (discarded
                // zero-duration session, "clear data") — the screen shows a notice, not a spinner.
                // The repository flows are built inside the collected flow so even a synchronous
                // construction failure (broken stats.db) lands in the catch below.
                flow<StatsDetailState?> {
                    emitAll(
                        combine(
                            repository.session(id),
                            repository.curveFlow(id),
                        ) { summary, curve ->
                            StatsDetailState(summary = summary, curve = curve)
                        },
                    )
                }.catch { e ->
                    log(TAG, Logging.Priority.ERROR) { "Session detail flow failed for $id: ${e.message}" }
                    emit(StatsDetailState(summary = null, curve = emptyList()))
                }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), null)

    fun openSession(id: Long) {
        savedStateHandle[KEY_SELECTED_SESSION] = id
    }

    fun closeSession() {
        savedStateHandle[KEY_SELECTED_SESSION] = null
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

    /** Change the retention window and apply it right away, so the effect is visible immediately. */
    fun setRetentionDays(days: Int) {
        viewModelScope.launch {
            preferences.setRetentionDays(days)
            recorder.purgeNow()
        }
    }

    fun clearData() {
        repository.clearAll()
    }

    // A refused foreground start (background-start restrictions) must not crash the toggle — report
    // it so the dashboard card can show "couldn't start" with a retry instead of a silent gap.
    private fun nudgeService() {
        val intent = Intent(context, ChargeSessionService::class.java).setAction(ChargeSessionService.ACTION_MONITOR)
        try {
            ContextCompat.startForegroundService(context, intent)
            serviceHealth.reportDispatched()
        } catch (e: Exception) {
            log(TAG, Logging.Priority.WARN) { "Charge service nudge failed: ${e.message}" }
            serviceHealth.reportFailed()
        }
    }

    private companion object {
        val TAG = logTag("Stats", "ViewModel")
        const val STOP_TIMEOUT_MILLIS = 5_000L
        const val KEY_SELECTED_SESSION = "stats.selected_session_id"
    }
}

/**
 * The history slice, extracted so its semantics are JVM-testable without the ViewModel — and so the
 * one property that matters is checkable: [recentSessions] is a **provider**, invoked inside the
 * flow, not a flow built at construction.
 *
 * That is not a style preference. `ChargeStatsRepository.recentSessions()` calls `database.get()`
 * eagerly, so `repository.recentSessions().stateIn(…)` would open the Room database the moment this
 * ViewModel is instantiated — including for a user who never enabled capture and only opened the
 * battery hub. `SharingStarted.WhileSubscribed` defers collection, never construction.
 *
 * Building the provider's flow inside [flow] also means a synchronous construction failure (a broken
 * `stats.db`) lands in [catch] rather than escaping to the collector.
 */
internal fun chargeHistoryStates(
    recentSessions: () -> Flow<List<ChargeSessionSummary>>,
): Flow<ChargeHistoryState> = flow<ChargeHistoryState> {
    emitAll(recentSessions().map { sessions -> ChargeHistoryState.Ready(sessions) })
}
    .onStart { emit(ChargeHistoryState.Loading) }
    .catch { e ->
        log(HISTORY_FLOW_TAG, Logging.Priority.ERROR) { "Charge history flow failed: ${e.asLog()}" }
        emit(ChargeHistoryState.Unavailable)
    }

private val HISTORY_FLOW_TAG = logTag("Stats", "VM", "History")
