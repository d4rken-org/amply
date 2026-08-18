package eu.darken.amply.main.ui.qualification

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.twotone.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import eu.darken.amply.R
import eu.darken.amply.charging.core.qualification.AbortReason
import eu.darken.amply.charging.core.qualification.IneligibleReason
import eu.darken.amply.charging.core.qualification.InconclusiveReason
import eu.darken.amply.charging.core.qualification.RunEligibility
import eu.darken.amply.charging.core.qualification.RunPhase
import eu.darken.amply.charging.core.qualification.RunShape
import eu.darken.amply.charging.core.qualification.RunTerminal
import eu.darken.amply.common.compose.AmplyCodeBlock
import eu.darken.amply.common.compose.AmplyPreview
import eu.darken.amply.common.compose.PreviewWrapper

/**
 * The guided qualification run.
 *
 * Pure and state-hoisted like every other screen: it renders [QualificationUiState] and calls back,
 * and in particular it never drives the protocol. The run lives in the foreground service, so the
 * user can leave this screen, lock the phone, or lose the process and come back to a correct view.
 * The copy says so, because a long observation with no visible progress otherwise reads as a hang.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QualificationScreen(
    state: QualificationUiState,
    onExit: () -> Unit,
    onRefresh: () -> Unit,
    onStart: () -> Unit,
    onCancel: () -> Unit,
    onNext: () -> Unit,
    onBack: () -> Unit,
    onDismissResult: () -> Unit,
    onOpenIssue: () -> Unit,
    onCopyReport: () -> Unit,
    onEmail: () -> Unit,
) {
    LaunchedEffect(Unit) { onRefresh() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.qualification_title)) },
                navigationIcon = {
                    IconButton(onClick = onExit) {
                        Icon(
                            Icons.AutoMirrored.TwoTone.ArrowBack,
                            contentDescription = stringResource(R.string.qualification_back),
                        )
                    }
                },
            )
        },
        bottomBar = {
            QualificationBottomBar(
                state = state,
                onBack = onBack,
                onNext = onNext,
                onStart = onStart,
                onCancel = onCancel,
                onDismissResult = onDismissResult,
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = padding.calculateTopPadding() + 8.dp,
                bottom = padding.calculateBottomPadding() + 16.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            when (state.step) {
                QualificationStep.INTRO -> introStep()
                QualificationStep.PRECHECK -> precheckStep(state)
                QualificationStep.RUNNING -> runningStep(state)
                QualificationStep.RESULT -> resultStep(state)
                QualificationStep.DELIVER -> deliverStep(state, onOpenIssue, onCopyReport, onEmail)
            }
        }
    }
}

@Composable
private fun QualificationBottomBar(
    state: QualificationUiState,
    onBack: () -> Unit,
    onNext: () -> Unit,
    onStart: () -> Unit,
    onCancel: () -> Unit,
    onDismissResult: () -> Unit,
) {
    Surface(tonalElevation = 3.dp) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (state.step == QualificationStep.PRECHECK || state.step == QualificationStep.DELIVER) {
                OutlinedButton(onClick = onBack) {
                    Text(stringResource(R.string.qualification_back))
                }
            }
            Spacer(Modifier.weight(1f))
            when (state.step) {
                QualificationStep.INTRO -> Button(onClick = onNext) {
                    Text(stringResource(R.string.qualification_continue))
                }

                QualificationStep.PRECHECK -> Button(onClick = onStart, enabled = state.eligible) {
                    Text(stringResource(R.string.qualification_start))
                }

                QualificationStep.RUNNING -> OutlinedButton(onClick = onCancel) {
                    Text(stringResource(R.string.qualification_cancel))
                }

                QualificationStep.RESULT -> {
                    TextButton(onClick = onDismissResult) {
                        Text(stringResource(R.string.qualification_done))
                    }
                    Button(onClick = onNext) {
                        Text(stringResource(R.string.qualification_share_result))
                    }
                }

                QualificationStep.DELIVER -> Button(onClick = onDismissResult) {
                    Text(stringResource(R.string.qualification_done))
                }
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleLarge)
}

@Composable
private fun BodyText(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

private fun LazyListScope.introStep() {
    item { SectionTitle(stringResource(R.string.qualification_intro_title)) }
    item { BodyText(stringResource(R.string.qualification_intro_body)) }
    item { BodyText(stringResource(R.string.qualification_intro_what_happens)) }
    item { BodyText(stringResource(R.string.qualification_intro_restore)) }
}

private fun LazyListScope.precheckStep(state: QualificationUiState) {
    item { SectionTitle(stringResource(R.string.qualification_precheck_title)) }
    when (val eligibility = state.eligibility) {
        null -> item { BodyText(stringResource(R.string.qualification_precheck_checking)) }
        is RunEligibility.Eligible -> {
            item { BodyText(stringResource(R.string.qualification_precheck_ready)) }
            item {
                BodyText(
                    when (eligibility.plan.shape) {
                        RunShape.VARIABLE_CAP -> stringResource(
                            R.string.qualification_precheck_plan_variable,
                            eligibility.plan.lowCap,
                        )

                        RunShape.FIXED_CAP -> stringResource(
                            R.string.qualification_precheck_plan_fixed,
                            eligibility.plan.lowCap,
                        )
                    },
                )
            }
        }

        is RunEligibility.Ineligible -> item { BodyText(stringResource(eligibility.reason.messageRes())) }
    }
}

private fun LazyListScope.runningStep(state: QualificationUiState) {
    val run = state.run
    item { SectionTitle(stringResource(R.string.qualification_running_title)) }
    if (run == null) {
        item { BodyText(stringResource(R.string.qualification_precheck_checking)) }
        return
    }
    item { BodyText(stringResource(run.phase.messageRes, run.lowCap)) }
    item {
        val fraction = if (run.phaseBudgetMillis <= 0) {
            0f
        } else {
            (run.phaseElapsedMillis.toFloat() / run.phaseBudgetMillis).coerceIn(0f, 1f)
        }
        LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
    }
    item {
        BodyText(
            stringResource(
                R.string.qualification_running_readings,
                run.percent?.toString() ?: "—",
                run.chargeCounter?.toString() ?: "—",
            ),
        )
    }
    item { BodyText(stringResource(R.string.qualification_running_leave_hint)) }
}

private fun LazyListScope.resultStep(state: QualificationUiState) {
    item { SectionTitle(stringResource(state.outcome.titleRes())) }
    item { BodyText(stringResource(state.outcome.bodyRes())) }
}

private fun LazyListScope.deliverStep(
    state: QualificationUiState,
    onOpenIssue: () -> Unit,
    onCopyReport: () -> Unit,
    onEmail: () -> Unit,
) {
    item { SectionTitle(stringResource(R.string.qualification_deliver_title)) }
    item { BodyText(stringResource(R.string.qualification_deliver_body)) }
    item { AmplyCodeBlock(text = state.reportText, maxHeight = 260.dp) }
    item {
        Button(onClick = onOpenIssue, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.qualification_open_issue))
        }
    }
    item {
        OutlinedButton(onClick = onCopyReport, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.qualification_copy_report))
        }
    }
    item {
        TextButton(onClick = onEmail, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.qualification_email))
        }
    }
}

internal fun IneligibleReason.messageRes(): Int = when (this) {
    IneligibleReason.NO_ADAPTER -> R.string.qualification_blocked_no_adapter
    IneligibleReason.NOTHING_TO_PROVE -> R.string.qualification_blocked_nothing_to_prove
    IneligibleReason.CONTROL_UNAVAILABLE -> R.string.qualification_blocked_control_unavailable
    IneligibleReason.REFUTED -> R.string.qualification_blocked_refuted
    IneligibleReason.LATCHES_AT_PLUG -> R.string.qualification_blocked_latches_at_plug
    IneligibleReason.NO_TESTABLE_CAP -> R.string.qualification_blocked_no_testable_cap
    IneligibleReason.ACCESS_NOT_READY -> R.string.qualification_blocked_access
    IneligibleReason.SESSION_ACTIVE -> R.string.qualification_blocked_session
    IneligibleReason.RECOVERY_PENDING -> R.string.qualification_blocked_recovery
    IneligibleReason.NOT_CHARGING -> R.string.qualification_blocked_not_charging
    IneligibleReason.BATTERY_LEVEL -> R.string.qualification_blocked_battery_level
    IneligibleReason.BATTERY_TOO_FULL -> R.string.qualification_blocked_battery_too_full
    IneligibleReason.RULE_ACTIVE -> R.string.qualification_blocked_rule_active
    IneligibleReason.BASELINE_UNREADABLE -> R.string.qualification_blocked_baseline_unreadable
}

internal fun RunTerminal?.titleRes(): Int = when (this) {
    is RunTerminal.Passed -> R.string.qualification_result_passed_title
    is RunTerminal.Refuted -> R.string.qualification_result_refuted_title
    is RunTerminal.Inconclusive -> R.string.qualification_result_inconclusive_title
    is RunTerminal.Aborted -> R.string.qualification_result_aborted_title
    null -> R.string.qualification_result_inconclusive_title
}

internal fun RunTerminal?.bodyRes(): Int = when (this) {
    is RunTerminal.Passed -> R.string.qualification_result_passed_body
    is RunTerminal.Refuted -> R.string.qualification_result_refuted_body
    is RunTerminal.Inconclusive -> when (reason) {
        InconclusiveReason.NO_CUT -> R.string.qualification_result_no_cut_body
        InconclusiveReason.NO_RESUME -> R.string.qualification_result_no_resume_body
        InconclusiveReason.NO_RECUT -> R.string.qualification_result_no_recut_body
        InconclusiveReason.CAP_MISMATCH -> R.string.qualification_result_cap_mismatch_body
        InconclusiveReason.NO_SIGNAL -> R.string.qualification_result_no_signal_body
        InconclusiveReason.NEAR_FULL -> R.string.qualification_result_near_full_body
        InconclusiveReason.CHARGE_UP_TIMEOUT -> R.string.qualification_result_charge_up_body
        InconclusiveReason.NO_BASELINE -> R.string.qualification_result_no_baseline_body
        InconclusiveReason.PRECONDITION_TIMEOUT -> R.string.qualification_result_precondition_body
    }

    is RunTerminal.Aborted -> when (reason) {
        AbortReason.UNPLUGGED -> R.string.qualification_result_unplugged_body
        AbortReason.CONFIGURATION_DRIFT -> R.string.qualification_result_drift_body
        AbortReason.WRITE_FAILED -> R.string.qualification_result_write_failed_body
        AbortReason.SESSION_STARTED -> R.string.qualification_result_session_body
        AbortReason.USER_CANCELLED -> R.string.qualification_result_cancelled_body
        AbortReason.RUN_CEILING -> R.string.qualification_result_ceiling_body
        AbortReason.PROCESS_DEATH -> R.string.qualification_result_process_death_body
        AbortReason.SERVICE_UNAVAILABLE -> R.string.qualification_result_service_unavailable_body
    }

    null -> R.string.qualification_result_inconclusive_title
}

@AmplyPreview
@Composable
private fun QualificationScreenIntroPreview() = PreviewWrapper {
    PreviewScreen(QualificationUiState(step = QualificationStep.INTRO))
}

@AmplyPreview
@Composable
private fun QualificationScreenRunningPreview() = PreviewWrapper {
    PreviewScreen(
        QualificationUiState(
            step = QualificationStep.RUNNING,
            run = RunProgressUi(
                phase = RunPhase.CUT_1,
                shape = RunShape.VARIABLE_CAP,
                lowCap = 70,
                elapsedMillis = 8 * 60_000L,
                phaseElapsedMillis = 5 * 60_000L,
                phaseBudgetMillis = 25 * 60_000L,
                percent = 80,
                chargeCounter = 3_201_000,
            ),
        ),
    )
}

@AmplyPreview
@Composable
private fun QualificationScreenBlockedPreview() = PreviewWrapper {
    PreviewScreen(
        QualificationUiState(
            step = QualificationStep.PRECHECK,
            eligibility = RunEligibility.Ineligible(IneligibleReason.NOT_CHARGING),
        ),
    )
}

@AmplyPreview
@Composable
private fun QualificationScreenResultPreview() = PreviewWrapper {
    PreviewScreen(
        QualificationUiState(step = QualificationStep.RESULT, outcome = RunTerminal.Passed),
    )
}

@Composable
private fun PreviewScreen(state: QualificationUiState) {
    QualificationScreen(
        state = state,
        onExit = {},
        onRefresh = {},
        onStart = {},
        onCancel = {},
        onNext = {},
        onBack = {},
        onDismissResult = {},
        onOpenIssue = {},
        onCopyReport = {},
        onEmail = {},
    )
}
