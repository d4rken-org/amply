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
import eu.darken.amply.common.flow.SingleEventFlow
import eu.darken.amply.fullcharge.core.ChargeSessionService
import eu.darken.amply.main.ui.battery.BatteryMetric
import eu.darken.amply.main.ui.battery.BatteryMetricDetailState
import eu.darken.amply.stats.core.CaptureServiceHealth
import eu.darken.amply.stats.core.ChargeCurvePoint
import eu.darken.amply.stats.core.ChargeSessionSummary
import eu.darken.amply.stats.core.ChargeStatsRecorder
import eu.darken.amply.stats.core.ChargeStatsRepository
import eu.darken.amply.stats.core.StatsPreferences
import eu.darken.amply.stats.core.StatsRetention
import eu.darken.amply.upgrade.core.UpgradeRepo
import eu.darken.amply.upgrade.core.isProForUi
import eu.darken.amply.upgrade.core.isProSettled
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
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
    private val upgradeRepo: UpgradeRepo,
    private val savedStateHandle: SavedStateHandle,
) : ViewModel() {

    /** Emitted when a capture-enable attempt was denied: the caller routes to the upgrade screen. */
    val upgradeRequiredEvents = SingleEventFlow<Unit>()

    /**
     * Emitted when the entitlement check passed and enabling may continue. The permission prompt is
     * deliberately downstream of this: asking for notification access and only then refusing the
     * feature would be the wrong order to put a user through.
     */
    val proceedWithEnableEvents = SingleEventFlow<Unit>()

    /**
     * Entry point for every "start recording" affordance. Answers with exactly one of the two events
     * above; it never writes anything itself, because enabling still has to pass through the
     * activity's notification-permission flow.
     */
    fun requestEnableCapture() {
        viewModelScope.launch {
            if (upgradeRepo.isProForUi()) {
                proceedWithEnableEvents.tryEmit(Unit)
            } else {
                log(TAG) { "Capture enable denied, routing to the upgrade screen" }
                upgradeRequiredEvents.tryEmit(Unit)
            }
        }
    }

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

    /**
     * The session whose curve the battery hub's tiles are drawing, set by the composition root from
     * the hub's charge teaser. Not saved state: the hub derives it from the teaser on every entry,
     * so a restored process re-supplies it rather than restoring a stale id.
     */
    private val hubSessionId = MutableStateFlow<Long?>(null)

    fun setHubSession(id: Long?) {
        hubSessionId.value = id
    }

    /**
     * The hub's sparkline curve for a **finished** session. A live session needs nothing from here:
     * the teaser already carries `StatsLiveSession.curve`, which `currentSession()` bounds on
     * purpose — opening the unbounded curve for that same session would reload the whole thing on
     * every appended sample, which is exactly what the bounded window exists to avoid. This read is
     * bounded for the same reason.
     *
     * The repository flow is built **inside** the [flatMapLatest] block, like [detailState]:
     * `ChargeStatsRepository` calls `database.get()` eagerly, so constructing it up front would open
     * `stats.db` for a user who never enabled capture and merely opened the battery hub.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val hubCurve: StateFlow<List<ChargeCurvePoint>> = hubSessionId
        .flatMapLatest { id ->
            if (id == null) {
                flowOf(emptyList())
            } else {
                flow { emitAll(repository.recentCurveFlow(id)) }
                    .catch { e ->
                        log(TAG, Logging.Priority.ERROR) { "Hub curve flow failed for $id: ${e.message}" }
                        emit(emptyList())
                    }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), emptyList())

    fun openSession(id: Long) {
        savedStateHandle[KEY_SELECTED_SESSION] = id
    }

    fun closeSession() {
        savedStateHandle[KEY_SELECTED_SESSION] = null
    }

    /**
     * The metric-detail selection, saved as the **pair** (session, metric) rather than the metric
     * alone.
     *
     * Persisting only the metric and letting the session follow whatever the hub's teaser currently
     * points at would silently swap the chart to a different charge under an unchanged title — a
     * charge starting while the screen is open, or a process death after the teaser has moved on,
     * would both do it. The two keys are written and cleared together for the same reason.
     */
    private val metricSelection: Flow<MetricSelection?> = combine(
        savedStateHandle.getStateFlow<Long?>(KEY_METRIC_SESSION, null),
        savedStateHandle.getStateFlow<String?>(KEY_METRIC_NAME, null),
    ) { sessionId, metricName -> resolveMetricSelection(sessionId, metricName) }

    @OptIn(ExperimentalCoroutinesApi::class)
    val metricDetailState: StateFlow<BatteryMetricDetailState?> = metricSelection
        .flatMapLatest { selection ->
            if (selection == null) {
                flowOf<BatteryMetricDetailState?>(null)
            } else {
                // Both flows stay live so an open session's chart and statistics keep updating. The
                // summary is what answers "does this session still exist" — an empty curve alone
                // cannot, since a session with no samples yet also has one. Built inside the
                // collected flow so a synchronous construction failure lands in the catch below.
                flow<BatteryMetricDetailState?> {
                    emitAll(
                        combine(
                            repository.session(selection.sessionId),
                            repository.sessionMetrics(selection.sessionId),
                        ) { summary, data ->
                            BatteryMetricDetailState(
                                metric = selection.metric,
                                sessionMissing = summary == null,
                                curve = data.curve,
                                stats = selection.metric.stats(data.aggregates),
                            )
                        },
                    )
                }.catch { e ->
                    log(TAG, Logging.Priority.ERROR) { "Metric detail flow failed: ${e.message}" }
                    emit(
                        BatteryMetricDetailState(
                            metric = selection.metric,
                            sessionMissing = true,
                            curve = emptyList(),
                            stats = null,
                        ),
                    )
                }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), null)

    fun openMetric(sessionId: Long, metric: BatteryMetric) {
        savedStateHandle[KEY_METRIC_SESSION] = sessionId
        savedStateHandle[KEY_METRIC_NAME] = metric.name
    }

    fun closeMetric() {
        savedStateHandle[KEY_METRIC_SESSION] = null
        savedStateHandle[KEY_METRIC_NAME] = null
    }

    /**
     * A stored metric name is a wire format, so it is parsed defensively: a name this build no
     * longer knows clears the selection rather than throwing on restore. Clearing also stops the
     * screen resolving against a session it can't label.
     */
    private fun resolveMetricSelection(sessionId: Long?, metricName: String?): MetricSelection? {
        if (sessionId == null || metricName == null) return null
        val metric = BatteryMetric.entries.firstOrNull { it.name == metricName }
        if (metric == null) {
            log(TAG, Logging.Priority.WARN) { "Unknown saved battery metric '$metricName', clearing" }
            closeMetric()
            return null
        }
        return MetricSelection(sessionId = sessionId, metric = metric)
    }

    private data class MetricSelection(val sessionId: Long, val metric: BatteryMetric)

    /**
     * Enable/disable capture. Enabling is routed through the activity's notification-permission flow
     * first (the always-on service shows a persistent notification). Disabling seals any open session
     * before the service is nudged to re-evaluate and stop.
     *
     * Disabling, viewing and clearing are never gated: an entitlement that lapses must not strand a
     * user's data behind a paywall, and it must not keep a service running they want stopped.
     */
    fun setCaptureEnabled(enabled: Boolean) {
        viewModelScope.launch {
            // Re-checked here as defense in depth, not as the primary gate: this is the only path
            // that actually writes the flag, and it is reachable from more than one affordance.
            // The backend gate (isProSettled), not the navigation one: it reconciles a cold-start
            // billing race before denying, and fails open on a settled error — a Play hiccup must
            // never refuse a paying user the write.
            if (enabled && !upgradeRepo.isProSettled()) {
                log(TAG) { "Capture enable denied at the write, routing to the upgrade screen" }
                upgradeRequiredEvents.tryEmit(Unit)
                return@launch
            }
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

    // Internal, not private: the saved-state keys are asserted by the metric-selection test, which
    // exists precisely to pin that both halves of the selection are written and cleared together.
    internal companion object {
        val TAG = logTag("Stats", "ViewModel")
        const val STOP_TIMEOUT_MILLIS = 5_000L
        const val KEY_SELECTED_SESSION = "stats.selected_session_id"
        const val KEY_METRIC_SESSION = "stats.metric.session_id"
        const val KEY_METRIC_NAME = "stats.metric.name"
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
