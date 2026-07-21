package eu.darken.amply.diagnostics.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import eu.darken.amply.BuildConfig
import eu.darken.amply.R
import eu.darken.amply.charging.core.access.BackendStatus
import eu.darken.amply.common.ca.CaString
import eu.darken.amply.common.ca.toCaString
import eu.darken.amply.common.debug.logging.Logging
import eu.darken.amply.common.debug.logging.log
import eu.darken.amply.common.debug.logging.logTag
import eu.darken.amply.diagnostics.core.CaptureResult
import eu.darken.amply.diagnostics.core.ContributionRepository
import eu.darken.amply.diagnostics.core.Disclosure
import eu.darken.amply.diagnostics.core.IssueDelivery
import eu.darken.amply.diagnostics.core.MatrixRow
import eu.darken.amply.diagnostics.core.ModeEffect
import eu.darken.amply.diagnostics.core.ModeObservation
import eu.darken.amply.diagnostics.core.RawWizardSession
import eu.darken.amply.diagnostics.core.SettingId
import eu.darken.amply.diagnostics.core.buildReviewedReport
import eu.darken.amply.diagnostics.core.contributionIssueBody
import eu.darken.amply.diagnostics.core.contributionIssueDelivery
import eu.darken.amply.diagnostics.core.deriveMatrix
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class WizardStep { INTRO, DETAILS, CAPTURE, REVIEW, DELIVER }

/** Per-mode summary shown in the UI. Carries no raw setting values — only the label, optional effect, and a count. */
data class ModeSummary(
    val label: String,
    val effect: String? = null,
    val changedFromPrevious: Int? = null,
)

/** One review row. Values are withheld (null) for a redacted row until the user reveals it locally (stage 1). */
data class ReviewRowUi(
    val id: SettingId,
    val disclosure: Disclosure,
    val revealed: Boolean,
    val included: Boolean,
    val values: List<String?>?,
)

data class ContributionUiState(
    val step: WizardStep = WizardStep.INTRO,
    val shizuku: BackendStatus? = null,
    val busy: Boolean = false,
    val status: CaString? = null,
    val featureName: String = "",
    val romVersion: String = "",
    val notes: String = "",
    val modes: List<ModeSummary> = emptyList(),
    val pendingLabel: String = "",
    val labelError: CaString? = null,
    val review: List<ReviewRowUi> = emptyList(),
    val reportText: String? = null,
    val issueUrl: String? = null,
    val deliveryTooLargeBytes: Int? = null,
) {
    val shizukuReady: Boolean get() = shizuku?.ready == true
}

/**
 * Owns the contribution wizard session. The **raw** snapshots live only in [rawSession] (private, never published),
 * so the state exposed to the UI carries only derived, safe data. A monotonic [generation] counter invalidates any
 * in-flight capture whose result returns after the user reset or left the wizard: the coroutine tags itself with the
 * generation at launch and drops its result if the counter has since moved. All state mutation happens on the
 * `viewModelScope` main dispatcher, so the non-suspending sections are atomic — no additional lock is needed.
 */
@HiltViewModel
class ContributionWizardViewModel @Inject constructor(
    private val repository: ContributionRepository,
) : ViewModel() {

    private val mutableState = MutableStateFlow(ContributionUiState())
    val state: StateFlow<ContributionUiState> = mutableState.asStateFlow()

    private var rawSession = RawWizardSession()
    // The exact session frozen when Review was built. Delivery reports from THIS, never the live rawSession, so a
    // capture that somehow lands after Review can never inject a value the user didn't see and approve.
    private var reviewedSession = RawWizardSession()
    private var reviewMatrix: List<MatrixRow> = emptyList()
    private var generation = 0
    private var captureJob: Job? = null

    fun refreshStatus() = viewModelScope.launch {
        val status = repository.status()
        mutableState.update { it.copy(shizuku = status) }
    }

    fun setFeatureName(value: String) = mutableState.update { it.copy(featureName = value) }
    fun setRomVersion(value: String) = mutableState.update { it.copy(romVersion = value) }
    fun setNotes(value: String) = mutableState.update { it.copy(notes = value) }
    fun setPendingLabel(value: String) = mutableState.update { it.copy(pendingLabel = value, labelError = null) }

    fun captureMode() {
        if (captureJob?.isActive == true) return // ignore double-taps while a capture is in flight
        val current = mutableState.value
        val label = current.pendingLabel.trim()
        when {
            label.isEmpty() -> {
                mutableState.update { it.copy(labelError = R.string.contribution_label_required.toCaString()) }
                return
            }
            current.modes.any { it.label.equals(label, ignoreCase = true) } -> {
                mutableState.update { it.copy(labelError = R.string.contribution_label_duplicate.toCaString()) }
                return
            }
            !current.shizukuReady -> {
                mutableState.update { it.copy(status = R.string.contribution_status_shizuku_required.toCaString()) }
                return
            }
        }
        val gen = generation
        captureJob = viewModelScope.launch {
            mutableState.update { it.copy(busy = true, status = null, labelError = null) }
            val result = repository.captureSnapshot()
            if (gen != generation) return@launch // session was reset/left while capturing — discard
            when (result) {
                is CaptureResult.Success -> {
                    val previous = rawSession.observations.lastOrNull()?.snapshot
                    val changed = previous?.let { diffCount(it, result.snapshot) }
                    rawSession = rawSession.copy(
                        observations = rawSession.observations +
                            ModeObservation(label, System.currentTimeMillis(), result.snapshot),
                    )
                    mutableState.update {
                        it.copy(
                            busy = false,
                            pendingLabel = "",
                            modes = it.modes + ModeSummary(label, effect = null, changedFromPrevious = changed),
                            status = if (changed == 0) {
                                R.string.contribution_status_no_change.toCaString()
                            } else {
                                null
                            },
                        )
                    }
                }
                is CaptureResult.Failure -> mutableState.update {
                    it.copy(busy = false, status = result.reason)
                }
            }
        }
    }

    fun setEffect(index: Int, effect: String) = mutableState.update { s ->
        s.copy(modes = s.modes.mapIndexed { i, mode -> if (i == index) mode.copy(effect = effect) else mode })
    }

    fun undoLastCapture() {
        if (rawSession.observations.isEmpty()) return
        rawSession = rawSession.copy(observations = rawSession.observations.dropLast(1))
        mutableState.update { it.copy(modes = it.modes.dropLast(1), status = null) }
    }

    fun restartSession() {
        generation++
        captureJob?.cancel()
        rawSession = RawWizardSession()
        reviewedSession = RawWizardSession()
        reviewMatrix = emptyList()
        mutableState.update {
            it.copy(
                step = WizardStep.CAPTURE,
                busy = false,
                modes = emptyList(),
                pendingLabel = "",
                labelError = null,
                review = emptyList(),
                reportText = null,
                issueUrl = null,
                deliveryTooLargeBytes = null,
                status = null,
            )
        }
    }

    fun revealRow(id: SettingId) {
        val row = reviewMatrix.firstOrNull { it.id == id } ?: return
        mutableState.update { s ->
            s.copy(review = s.review.map { if (it.id == id) it.copy(revealed = true, values = row.valuesByMode) else it })
        }
    }

    fun toggleInclude(id: SettingId) = mutableState.update { s ->
        s.copy(
            review = s.review.map {
                // Only a revealed row can be included (two-stage: reveal locally, then opt in to publishing).
                if (it.id == id && it.revealed) it.copy(included = !it.included) else it
            },
        )
    }

    fun goNext() {
        when (mutableState.value.step) {
            WizardStep.INTRO -> if (mutableState.value.shizukuReady) transitionTo(WizardStep.DETAILS)
            WizardStep.DETAILS -> transitionTo(WizardStep.CAPTURE)
            // Never advance while a capture is in flight — Review must reflect a settled session.
            WizardStep.CAPTURE -> if (rawSession.observations.isNotEmpty() && captureJob?.isActive != true) buildReview()
            WizardStep.REVIEW -> buildDelivery()
            WizardStep.DELIVER -> Unit
        }
    }

    fun goBack() {
        val target = when (mutableState.value.step) {
            WizardStep.INTRO -> return
            WizardStep.DETAILS -> WizardStep.INTRO
            WizardStep.CAPTURE -> WizardStep.DETAILS
            WizardStep.REVIEW -> WizardStep.CAPTURE
            WizardStep.DELIVER -> WizardStep.REVIEW
        }
        transitionTo(target)
    }

    /** Single synchronous teardown for both toolbar and system back: invalidate in-flight work and clear raw state. */
    fun exitWizard() {
        generation++
        captureJob?.cancel()
        rawSession = RawWizardSession()
        reviewedSession = RawWizardSession()
        reviewMatrix = emptyList()
        mutableState.value = ContributionUiState(shizuku = mutableState.value.shizuku)
    }

    private fun transitionTo(step: WizardStep) = mutableState.update { it.copy(step = step, status = null) }

    private fun buildReview() {
        reviewedSession = rawSession
        reviewMatrix = deriveMatrix(reviewedSession.observations)
        val rows = reviewMatrix.map { row ->
            val auto = row.disclosure == Disclosure.AUTO
            ReviewRowUi(
                id = row.id,
                disclosure = row.disclosure,
                revealed = auto,
                included = auto,
                values = if (auto) row.valuesByMode else null,
            )
        }
        mutableState.update { it.copy(step = WizardStep.REVIEW, review = rows, status = null) }
    }

    private fun buildDelivery() {
        val s = mutableState.value
        val approved = s.review.filter { it.disclosure == Disclosure.REDACTED && it.included }.map { it.id }.toSet()
        val deviceContext = repository.deviceContext()
        val effects = s.modes.mapNotNull { mode ->
            mode.effect?.takeIf { it.isNotBlank() }?.let { ModeEffect(mode.label, it) }
        }
        val report = buildReviewedReport(
            session = reviewedSession,
            approvedRedacted = approved,
            device = deviceContext.device,
            appVersion = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
            adapterId = deviceContext.adapterId,
            romVersion = s.romVersion,
            featureName = s.featureName,
            effects = effects,
            notes = s.notes,
            createdAtEpochMs = System.currentTimeMillis(),
        )
        val text = contributionIssueBody(report)
        val delivery = contributionIssueDelivery(report)
        log(TAG, Logging.Priority.INFO) { "Contribution report built: ${report.rows.size} rows, ${report.withheldRowCount} withheld" }
        mutableState.update {
            it.copy(
                step = WizardStep.DELIVER,
                reportText = text,
                issueUrl = (delivery as? IssueDelivery.Url)?.url,
                deliveryTooLargeBytes = (delivery as? IssueDelivery.TooLarge)?.encodedBytes,
                status = null,
            )
        }
    }

    private fun diffCount(before: Map<SettingId, String>, after: Map<SettingId, String>): Int =
        (before.keys + after.keys).count { before[it] != after[it] }

    private companion object {
        val TAG = logTag("Contribution", "VM")
    }
}
