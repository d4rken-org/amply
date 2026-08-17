package eu.darken.amply.main.ui.widget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.updateAll
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import eu.darken.amply.charging.core.ChargePolicy
import eu.darken.amply.charging.core.ChargingRepository
import eu.darken.amply.common.datastore.value
import eu.darken.amply.common.debug.logging.Logging
import eu.darken.amply.common.debug.logging.log
import eu.darken.amply.common.debug.logging.logTag
import eu.darken.amply.common.flow.SingleEventFlow
import eu.darken.amply.common.theming.AmplyTheme
import eu.darken.amply.fullcharge.core.FullChargeStore
import eu.darken.amply.fullcharge.core.resolveQuickActionPolicies
import eu.darken.amply.fullcharge.core.toggleQuickActionPolicy
import eu.darken.amply.main.ui.MainActivity
import eu.darken.amply.upgrade.core.UpgradeRepo
import eu.darken.amply.upgrade.core.isProForUi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * The AppWidget configuration screen — the one deliberate exception to the single-Activity rule: the
 * AppWidget host launches a configuration by component name, so it needs its own target. It hosts a
 * single Compose screen and has no navigation of its own.
 */
@AndroidEntryPoint
class WidgetConfigActivity : ComponentActivity() {

    private val viewModel: WidgetConfigViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val requestedId = intent?.getIntExtra(
            AppWidgetManager.EXTRA_APPWIDGET_ID,
            AppWidgetManager.INVALID_APPWIDGET_ID,
        ) ?: AppWidgetManager.INVALID_APPWIDGET_ID
        // Ownership check before the ViewModel is ever touched (`by viewModels()` is lazy): this
        // activity is exported, so a foreign id must not reach a store read or write.
        val ownsWidget = runCatching {
            AppWidgetManager.getInstance(this).getAppWidgetInfo(requestedId)?.provider ==
                ComponentName(this, AmplyWidgetReceiver::class.java)
        }.getOrDefault(false)
        val entry = resolveWidgetConfigEntry(requestedId, ownsWidget)
        if (entry !is WidgetConfigEntry.Proceed) {
            log(TAG, Logging.Priority.WARN) { "Widget configuration rejected for id $requestedId" }
            finish()
            return
        }
        val appWidgetId = entry.appWidgetId
        // Back or a swipe-away keeps the previous configuration, and discards a widget the host is
        // still placing — the same thing the user would expect from cancelling.
        setResult(RESULT_CANCELED, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId))

        lifecycleScope.launch {
            viewModel.finishEvents.collect {
                setResult(RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId))
                finish()
            }
        }

        enableEdgeToEdge()
        setContent {
            AmplyTheme {
                val state by viewModel.state.collectAsState()
                val completionInFlight by viewModel.completionInFlight.collectAsState()
                LifecycleResumeEffect(Unit) {
                    // Returning from the upgrade screen must unlock the picker.
                    viewModel.onResumed()
                    onPauseOrDispose { }
                }
                // The result is still CANCELED until the completion emits, so Back during it would
                // discard a widget whose configuration is already stored. Inert, not "cancel the
                // completion": the save has run or is running either way.
                BackHandler(enabled = completionInFlight) { }
                WidgetConfigScreen(
                    state = state,
                    completionInFlight = completionInFlight,
                    onToggle = viewModel::togglePolicy,
                    onConfirm = viewModel::confirm,
                    onDone = viewModel::finishWithoutSaving,
                    onRetry = viewModel::reload,
                    onUpgrade = ::openUpgrade,
                )
            }
        }
    }

    private fun openUpgrade() {
        startActivity(
            Intent(this, MainActivity::class.java)
                .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(MainActivity.EXTRA_OPEN_UPGRADE, true),
        )
    }

    private companion object {
        val TAG = logTag("Widget", "Config", "Activity")
    }
}

sealed interface WidgetConfigState {
    data object Loading : WidgetConfigState

    /** Free user: the widget can still be placed, it renders its locked face until the upgrade lands. */
    data object Locked : WidgetConfigState
    data object Error : WidgetConfigState

    /** No adapter, or charging control unavailable — nothing to configure, but placement must work. */
    data object Unavailable : WidgetConfigState

    /** Two or fewer policies: the widget already shows all of them. */
    data object NotConfigurable : WidgetConfigState

    data class Ready(
        val availablePolicies: List<ChargePolicy>,
        val selectedPolicyIds: List<String>,
        val saveFailed: Boolean = false,
    ) : WidgetConfigState
}

@HiltViewModel
class WidgetConfigViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    savedStateHandle: SavedStateHandle,
    private val repository: ChargingRepository,
    private val fullChargeStore: FullChargeStore,
    private val upgradeRepo: UpgradeRepo,
) : ViewModel() {

    // Seeded from the launching intent's extras; the activity has already validated ownership.
    private val appWidgetId: Int = savedStateHandle[AppWidgetManager.EXTRA_APPWIDGET_ID]
        ?: AppWidgetManager.INVALID_APPWIDGET_ID

    private val _state = MutableStateFlow<WidgetConfigState>(WidgetConfigState.Loading)
    val state = _state.asStateFlow()

    /** Emitted once the activity may return RESULT_OK and close. */
    val finishEvents = SingleEventFlow<Unit>()

    private val _completionInFlight = MutableStateFlow(false)

    /**
     * A completion (save, then a best-effort widget render) is running and the activity must not be
     * left before it emits its result. Back would otherwise finish with the initial RESULT_CANCELED
     * while the configuration is already stored — which discards a freshly placed widget on API 26–30.
     *
     * Set synchronously before the coroutine starts, so it is already true when the tap that started
     * the completion returns; cleared only where [complete] stays on the picker for a retry.
     */
    val completionInFlight = _completionInFlight.asStateFlow()

    init {
        reload()
    }

    /**
     * Nothing else refreshes during a launcher-initiated cold start — the host launches this
     * activity directly — so the charging state is read here rather than assumed.
     */
    fun reload() = viewModelScope.launch {
        _state.value = WidgetConfigState.Loading
        if (!upgradeRepo.isProForUi()) {
            _state.value = WidgetConfigState.Locked
            return@launch
        }
        val charging = try {
            repository.refresh()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log(TAG, Logging.Priority.WARN) { "Charging refresh failed: ${e.message}" }
            _state.value = WidgetConfigState.Error
            return@launch
        }
        val defaultProtective = charging.defaultProtectivePolicy
        _state.value = when {
            !charging.adapterResolved || !charging.controlEnabled || defaultProtective == null ->
                WidgetConfigState.Unavailable
            charging.supportedPolicies.size <= 2 -> WidgetConfigState.NotConfigurable
            else -> {
                val stored = runCatching { fullChargeStore.widgetQuickActions.value() }
                    .getOrNull()
                    ?.get(appWidgetId)
                WidgetConfigState.Ready(
                    availablePolicies = charging.supportedPolicies,
                    selectedPolicyIds = resolveQuickActionPolicies(
                        stored,
                        charging.supportedPolicies,
                        defaultProtective,
                    ).map { it.stableId },
                )
            }
        }
    }

    /** Re-check the entitlement only while locked, so a picker mid-edit is never reset by a resume. */
    fun onResumed() {
        if (_state.value !is WidgetConfigState.Locked) return
        viewModelScope.launch {
            runCatching { upgradeRepo.refresh() }
            reload()
        }
    }

    fun togglePolicy(policy: ChargePolicy, selected: Boolean) {
        val ready = _state.value as? WidgetConfigState.Ready ?: return
        val current = ready.availablePolicies.filter { it.stableId in ready.selectedPolicyIds }
        _state.value = ready.copy(
            selectedPolicyIds = toggleQuickActionPolicy(current, policy, selected, ready.availablePolicies)
                .map { it.stableId },
            saveFailed = false,
        )
    }

    fun confirm() {
        val ready = _state.value as? WidgetConfigState.Ready ?: return
        if (_completionInFlight.value) return
        _completionInFlight.value = true
        _state.value = ready.copy(saveFailed = false)
        viewModelScope.launch {
            // Save-time validation: what the picker showed can be older than the resolved adapter.
            val ids = ready.selectedPolicyIds.filter { id ->
                ready.availablePolicies.any { it.stableId == id }
            }
            val saved = try {
                fullChargeStore.setWidgetQuickActions(appWidgetId, ids)
                true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log(TAG, Logging.Priority.ERROR) { "Storing the widget configuration failed: ${e.message}" }
                false
            }
            complete(resolveWidgetConfigCompletion(saveAttempted = true, saveSucceeded = saved), ready)
        }
    }

    /**
     * The exit every non-picker state offers: stores nothing and still returns RESULT_OK, because a
     * CANCELED configuration discards the widget the user is placing on API 26–30.
     */
    fun finishWithoutSaving() {
        if (_completionInFlight.value) return
        _completionInFlight.value = true
        viewModelScope.launch {
            complete(resolveWidgetConfigCompletion(saveAttempted = false, saveSucceeded = false), null)
        }
    }

    private suspend fun complete(completion: WidgetConfigCompletion, ready: WidgetConfigState.Ready?) {
        if (completion.result == WidgetConfigResult.STAY_RETRY) {
            // The only branch that hands the screen back to the user, so it is the only one that
            // re-arms the controls; every other path ends the activity.
            _completionInFlight.value = false
            _state.value = (ready ?: return).copy(saveFailed = true)
            return
        }
        if (completion.updateWidget) updateWidget()
        finishEvents.emit(Unit)
    }

    /**
     * Best effort: the stored configuration is what matters, and the next widget broadcast renders
     * it anyway. One retry over all instances covers a per-id lookup that lost its session, and no
     * failure here may undo the save or the RESULT_OK.
     */
    private suspend fun updateWidget() {
        try {
            AmplyWidget().update(context, GlanceAppWidgetManager(context).getGlanceIdBy(appWidgetId))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log(TAG, Logging.Priority.WARN) { "Widget update failed, retrying over all instances: ${e.message}" }
            try {
                AmplyWidget().updateAll(context)
            } catch (e2: CancellationException) {
                throw e2
            } catch (e2: Exception) {
                log(TAG, Logging.Priority.WARN) { "Widget update retry failed: ${e2.message}" }
            }
        }
    }

    private companion object {
        val TAG = logTag("Widget", "Config", "VM")
    }
}
