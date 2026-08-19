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
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart
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
    /** Live pre-check figures; only present while the pre-check step is on screen. */
    val precheck: PrecheckStatusUi? = null,
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
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class QualificationViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val runner: QualificationRunner,
    private val runStore: QualificationRunStore,
    private val buildIdentity: BuildIdentitySource,
    private val batteryReadoutSource: BatteryReadoutSource,
) : ViewModel() {

    private val step = MutableStateFlow(QualificationStep.INTRO)
    private val eligibility = MutableStateFlow<RunEligibility?>(null)
    private val delivery = MutableStateFlow(Delivery())

    private data class Delivery(val reportText: String = "", val issueUrl: String = "")

    /** Everything the UI state is made of except the live battery figures. */
    private data class Snapshot(
        val step: QualificationStep,
        val eligibility: RunEligibility?,
        val record: QualificationRunRecord?,
        val outcome: RunTerminal?,
        val delivery: Delivery,
    )

    private val snapshots: Flow<Snapshot> = combine(
        step,
        eligibility,
        runStore.run,
        runner.lastResult,
        delivery,
    ) { step, eligibility, record, result, delivery ->
        Snapshot(
            step = resolveStep(step, record, result?.terminal),
            eligibility = eligibility,
            record = record,
            outcome = result?.terminal,
            delivery = delivery,
        )
    }

    /**
     * The step actually on screen, which is what the battery polling is scoped to — the *requested*
     * step is not it: a live run shows the running step from whichever step the user left behind.
     *
     * Derived separately from [snapshots] rather than taken off it on purpose: keying the polling off
     * the whole snapshot would tear the battery subscription down and build it back up on every
     * unrelated change (a record tick, a delivery, an eligibility write).
     */
    private val resolvedStep: Flow<QualificationStep> = combine(
        step,
        runStore.run,
        runner.lastResult,
    ) { requested, record, result -> resolveStep(requested, record, result?.terminal) }

    private val live: Flow<LiveFigures> = liveFigures(
        steps = resolvedStep,
        readouts = { batteryReadoutSource.readouts() },
        // Resolved on the same cadence as the figures, so the block and the Start button can never
        // disagree about the same battery reading.
        resolveEligibility = { runner.eligibility() },
    ).onEach { figures -> figures.eligibility?.let { eligibility.value = it } }

    val state: StateFlow<QualificationUiState> = snapshots
        .combine(live) { snapshot, live ->
            QualificationUiState(
                step = snapshot.step,
                eligibility = snapshot.eligibility,
                run = snapshot.record?.toUi(live.readout),
                outcome = snapshot.outcome,
                reportText = snapshot.delivery.reportText,
                issueUrl = snapshot.delivery.issueUrl,
                // Only on the step that renders it: a stale block must never outlive the pre-check.
                precheck = live.precheck.takeIf { snapshot.step == QualificationStep.PRECHECK },
            )
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), QualificationUiState())

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

    private fun QualificationRunRecord.toUi(readout: BatteryReadout?): RunProgressUi {
        val now = System.currentTimeMillis()
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
            percent = readout?.levelPercent,
            chargeCounter = readout?.chargeCounterMicroampHours,
        )
    }

    companion object {
        private val TAG = logTag("Qualification", "ViewModel")
    }
}

/** The battery figures the live steps render, plus the eligibility resolved from that same reading. */
internal data class LiveFigures(
    val readout: BatteryReadout? = null,
    val precheck: PrecheckStatusUi? = null,
    val eligibility: RunEligibility? = null,
)

/**
 * The live half of the qualification state graph: a battery poll (and, on the pre-check, a fresh
 * eligibility resolution) for exactly as long as a step that shows figures is on screen.
 *
 * **Cold on purpose.** The returned flow polls only while it is collected, so it stops when the UI
 * state it feeds loses its last subscriber. That matters twice over: the poll costs a battery read
 * plus a full eligibility resolution — store reads and, on some adapters, a Shizuku binder round trip
 * — and this is an app about battery care, so it must not keep doing that from the back stack or from
 * the background. The step is deliberately not treated as a proxy for the screen being visible; only
 * the collector's lifetime is (see how `MainActivity` collects the state).
 *
 * [QualificationStep.RUNNING] polls too, not just the pre-check: the running screen renders the same
 * percent and charge counter for the half hour to hour and a half a run takes, and a poll that stopped
 * at the start of the run would leave those figures frozen at the last pre-check reading while looking
 * live.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal fun liveFigures(
    steps: Flow<QualificationStep>,
    readouts: () -> Flow<BatteryReadout>,
    resolveEligibility: suspend () -> RunEligibility,
): Flow<LiveFigures> = steps
    .distinctUntilChanged()
    .flatMapLatest { step ->
        when (step) {
            QualificationStep.PRECHECK -> readouts().map { readout ->
                val resolved = resolveEligibility()
                LiveFigures(
                    readout = readout,
                    precheck = precheckStatus(
                        readout = readout,
                        requiredPercent = (resolved as? RunEligibility.Ineligible)?.requiredPercent,
                    ),
                    eligibility = resolved,
                )
            }

            QualificationStep.RUNNING -> readouts().map { LiveFigures(readout = it) }
            // No figures, and no poll: nothing on these steps renders one.
            else -> emptyFlow()
        }
            // The step change itself must reach the screen immediately: the first real emission of a
            // polling step waits on a battery read and an eligibility resolution, and combining on that
            // would hold the whole UI state on the previous step until both land. It is also the only
            // emission a non-polling step makes, which is what clears the previous step's figures.
            .onStart { emit(LiveFigures()) }
    }
