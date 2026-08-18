package eu.darken.amply.main.ui.qualification

import android.content.Context
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import eu.darken.amply.BuildConfig
import eu.darken.amply.battery.core.BatteryReadout
import eu.darken.amply.battery.core.BatteryReadoutSource
import eu.darken.amply.charging.core.DeviceInfo
import eu.darken.amply.charging.core.enforcement.BuildIdentitySource
import eu.darken.amply.charging.core.qualification.IneligibleReason
import eu.darken.amply.charging.core.qualification.QualificationProtocol
import eu.darken.amply.charging.core.qualification.QualificationReport
import eu.darken.amply.charging.core.qualification.QualificationRunRecord
import eu.darken.amply.charging.core.qualification.QualificationRunStore
import eu.darken.amply.charging.core.qualification.QualificationRunner
import eu.darken.amply.charging.core.qualification.RunEligibility
import eu.darken.amply.charging.core.qualification.RunPhase
import eu.darken.amply.charging.core.qualification.RunShape
import eu.darken.amply.charging.core.qualification.RunTerminal
import eu.darken.amply.charging.core.qualification.buildQualificationReport
import eu.darken.amply.charging.core.qualification.formatQualificationReport
import eu.darken.amply.charging.core.qualification.qualificationIssueUrl
import eu.darken.amply.diagnostics.core.MAX_ISSUE_URL_BYTES
import eu.darken.amply.fullcharge.core.ChargeSessionService
import eu.darken.amply.fullcharge.core.ServiceDispatch
import eu.darken.amply.common.debug.logging.Logging
import eu.darken.amply.common.debug.logging.log
import eu.darken.amply.common.debug.logging.logTag
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class QualificationStep { INTRO, PRECHECK, RUNNING, RESULT, DELIVER }

/** Live view of an in-flight run, rebuilt from the durable record on every tick. */
data class RunProgressUi(
    val phase: RunPhase,
    val shape: RunShape,
    val lowCap: Int,
    val elapsedMillis: Long,
    val phaseElapsedMillis: Long,
    val phaseBudgetMillis: Long,
    val percent: Int?,
    val chargeCounter: Int?,
)

data class QualificationUiState(
    val step: QualificationStep = QualificationStep.INTRO,
    /** Null while the first eligibility resolution is in flight. */
    val eligibility: RunEligibility? = null,
    val run: RunProgressUi? = null,
    val outcome: RunTerminal? = null,
    val reportText: String = "",
    val issueUrl: String = "",
) {
    val eligible: Boolean get() = eligibility is RunEligibility.Eligible
    val ineligibleReason: IneligibleReason?
        get() = (eligibility as? RunEligibility.Ineligible)?.reason
}

/**
 * Wiring for the guided qualification screen.
 *
 * It deliberately does **not** drive the protocol. Every piece of run state is read back from
 * [QualificationRunStore], so leaving the screen, locking the phone, or losing the process renders
 * correctly on return — the run itself lives in the foreground service, which is the only thing that
 * survives all three.
 */
@HiltViewModel
class QualificationViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val runner: QualificationRunner,
    private val runStore: QualificationRunStore,
    private val buildIdentity: BuildIdentitySource,
    batteryReadoutSource: BatteryReadoutSource,
) : ViewModel() {

    private val step = MutableStateFlow(QualificationStep.INTRO)
    private val eligibility = MutableStateFlow<RunEligibility?>(null)
    private val delivery = MutableStateFlow(Delivery())

    private data class Delivery(val reportText: String = "", val issueUrl: String = "")

    val state: StateFlow<QualificationUiState> = combine(
        step,
        eligibility,
        runStore.run,
        runner.lastResult,
        delivery,
    ) { step, eligibility, record, result, delivery ->
        QualificationUiState(
            step = resolveStep(step, record, result?.terminal),
            eligibility = eligibility,
            run = record?.toUi(),
            outcome = result?.terminal,
            reportText = delivery.reportText,
            issueUrl = delivery.issueUrl,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), QualificationUiState())

    /**
     * The record is the authority on whether a run is happening, so a live one always shows the
     * running step and its disappearance always leaves it — no matter which step the user had
     * navigated to, and no matter which process started the run.
     */
    private fun resolveStep(
        requested: QualificationStep,
        record: QualificationRunRecord?,
        outcome: RunTerminal?,
    ): QualificationStep = when {
        record != null -> QualificationStep.RUNNING
        outcome != null && requested == QualificationStep.RUNNING -> QualificationStep.RESULT
        else -> requested
    }

    init {
        refreshEligibility()
        // A start is a service command now, so its refusal comes back as an event rather than a
        // return value. Without this the screen would sit on a running step for a run that never
        // opened.
        viewModelScope.launch {
            runner.startFailed.collect {
                log(TAG, Logging.Priority.WARN) { "Qualification run did not start" }
                if (step.value == QualificationStep.RUNNING) step.value = QualificationStep.PRECHECK
                eligibility.value = runner.eligibility()
            }
        }
    }

    fun refreshEligibility() = viewModelScope.launch {
        eligibility.value = runner.eligibility()
    }

    fun start() = viewModelScope.launch {
        if (!BuildConfig.ENABLE_QUALIFICATION_RUN) return@launch
        // A local pre-check for immediate feedback only; the run is opened by the service command
        // below, which resolves eligibility again under the dispatch lock. Deciding here and acting
        // there is exactly the check-then-act this routing exists to remove.
        val eligible = runner.eligibility()
        eligibility.value = eligible
        if (eligible !is RunEligibility.Eligible) return@launch
        runner.clearResult()
        // Move to RUNNING before the run exists. resolveStep only promotes a finished run to RESULT
        // when the requested step was already RUNNING, so leaving it at PRECHECK would drop the user
        // back onto the pre-check list when their run completed, with no result anywhere.
        step.value = QualificationStep.RUNNING
        // Through the charge service's command queue, never straight into the runner: run start and
        // full-charge session start both claim the charge policy, and that queue is the one place
        // where the two are serialized against each other.
        val dispatched = runCatching {
            ContextCompat.startForegroundService(
                context,
                ServiceDispatch.startIntent(context, ChargeSessionService.ACTION_QUALIFICATION_START),
            )
        }.isSuccess
        log(TAG, Logging.Priority.INFO) { "Qualification run start dispatched: $dispatched" }
        if (!dispatched) {
            step.value = QualificationStep.PRECHECK
            eligibility.value = runner.eligibility()
        }
    }

    fun cancel() = viewModelScope.launch {
        runner.cancel()
    }

    fun goNext() {
        step.value = when (step.value) {
            QualificationStep.INTRO -> QualificationStep.PRECHECK
            QualificationStep.PRECHECK -> QualificationStep.PRECHECK
            QualificationStep.RUNNING -> QualificationStep.RUNNING
            QualificationStep.RESULT -> QualificationStep.DELIVER.also { buildDelivery() }
            QualificationStep.DELIVER -> QualificationStep.DELIVER
        }
    }

    fun goBack() {
        step.value = when (step.value) {
            QualificationStep.PRECHECK -> QualificationStep.INTRO
            QualificationStep.DELIVER -> QualificationStep.RESULT
            else -> step.value
        }
    }

    /** Drop a finished result so the screen returns to its idle state. */
    fun dismissResult() {
        runner.clearResult()
        delivery.value = Delivery()
        step.value = QualificationStep.INTRO
        refreshEligibility()
    }

    private fun buildDelivery() = viewModelScope.launch {
        val result = runner.lastResult.value ?: return@launch
        val report = buildReport(result.record, result.terminal)
        val url = qualificationIssueUrl(report)
        delivery.value = Delivery(
            reportText = formatQualificationReport(report),
            // A run with a full phase table routinely outgrows what a GitHub issue URL can carry, and
            // an over-long URL fails silently at the browser. Blank means "no prefilled issue", which
            // the composition root already handles by copying the report and opening a blank one.
            issueUrl = url.takeIf { it.toByteArray(Charsets.UTF_8).size <= MAX_ISSUE_URL_BYTES }.orEmpty(),
        )
    }

    private fun buildReport(record: QualificationRunRecord, terminal: RunTerminal): QualificationReport =
        buildQualificationReport(
            record = record,
            terminal = terminal,
            device = DeviceInfo.current(context),
            buildIdentity = buildIdentity.current(),
            appVersion = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
            androidRelease = Build.VERSION.RELEASE,
            brand = Build.BRAND,
            createdAtEpochMs = System.currentTimeMillis(),
        )

    private val readouts = batteryReadoutSource.readouts()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), BatteryReadout.UNKNOWN)

    private fun QualificationRunRecord.toUi(): RunProgressUi {
        val now = System.currentTimeMillis()
        val readout = readouts.value
        return RunProgressUi(
            phase = phase,
            shape = shape,
            lowCap = lowCap,
            elapsedMillis = (now - runStartedAtWallMillis).coerceAtLeast(0),
            phaseElapsedMillis = (now - phaseStartedAtWallMillis).coerceAtLeast(0),
            phaseBudgetMillis = when (phase) {
                RunPhase.PREFLIGHT -> QualificationProtocol.PREFLIGHT_BUDGET_MILLIS
                // Charging up to the cap is bounded by the charger rather than by the protocol, and
                // is much the longest phase, so its progress bar is scaled to its own budget.
                RunPhase.CHARGE_UP -> QualificationProtocol.CHARGE_UP_BUDGET_MILLIS
                // The baseline's bounded window IS its budget — it never reaches the general phase
                // budget — so scaling the bar to the latter would fill it to about 40% and then jump,
                // in the one phase the user is explicitly asked to wait through.
                RunPhase.BASELINE -> QualificationProtocol.BASELINE_WINDOW_MILLIS
                else -> QualificationProtocol.PHASE_BUDGET_MILLIS
            },
            percent = readout.levelPercent,
            chargeCounter = readout.chargeCounterMicroampHours,
        )
    }

    companion object {
        private val TAG = logTag("Qualification", "ViewModel")
    }
}
