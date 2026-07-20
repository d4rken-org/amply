package eu.darken.amply.diagnostics.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import eu.darken.amply.R
import eu.darken.amply.common.ca.CaString
import eu.darken.amply.common.ca.toCaString
import eu.darken.amply.common.debug.logging.Logging
import eu.darken.amply.common.debug.logging.log
import eu.darken.amply.common.debug.logging.logTag
import eu.darken.amply.diagnostics.core.DiagnosticsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class DiagnosticsUiState(
    val busy: Boolean = false,
    val baselineCaptured: Boolean = false,
    val status: CaString = R.string.diagnostics_status_ready.toCaString(),
    val report: String? = null,
)

@HiltViewModel
class DiagnosticsViewModel @Inject constructor(
    private val repository: DiagnosticsRepository,
) : ViewModel() {
    private val mutableState = MutableStateFlow(DiagnosticsUiState())
    val state: StateFlow<DiagnosticsUiState> = mutableState.asStateFlow()

    fun captureBaseline() = viewModelScope.launch {
        log(TAG, Logging.Priority.INFO) { "Capturing settings baseline" }
        mutableState.value = mutableState.value.copy(busy = true, report = null)
        mutableState.value = runCatching { repository.captureBaseline() }
            .fold(
                onSuccess = { count ->
                    DiagnosticsUiState(
                        baselineCaptured = true,
                        status = R.string.diagnostics_status_baseline_captured.toCaString(count),
                    )
                },
                onFailure = {
                    log(TAG, Logging.Priority.WARN) { "Baseline capture failed: ${it.message}" }
                    DiagnosticsUiState(status = R.string.diagnostics_status_capture_failed.toCaString())
                },
            )
    }

    fun compare() = viewModelScope.launch {
        if (!mutableState.value.baselineCaptured) return@launch
        log(TAG, Logging.Priority.INFO) { "Comparing settings with baseline" }
        mutableState.value = mutableState.value.copy(busy = true)
        mutableState.value = runCatching { repository.compare() }
            .fold(
                onSuccess = { report ->
                    DiagnosticsUiState(
                        baselineCaptured = true,
                        status = R.string.diagnostics_status_compare_complete.toCaString(),
                        report = report,
                    )
                },
                onFailure = {
                    log(TAG, Logging.Priority.WARN) { "Comparison failed: ${it.message}" }
                    mutableState.value.copy(
                        busy = false,
                        status = R.string.diagnostics_status_compare_failed.toCaString(),
                    )
                },
            )
    }

    private companion object {
        val TAG = logTag("Diagnostics", "VM")
    }
}
