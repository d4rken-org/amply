package eu.darken.amply.charging.core.qualification

import eu.darken.amply.charging.core.DeviceInfo
import eu.darken.amply.main.core.DeviceSupportReporter
import eu.darken.amply.main.core.sanitizeReportValue
import java.net.URLEncoder

/**
 * The maintainer-facing result of a run.
 *
 * Modelled on `DeviceSupportReport` (device identity plus a result) rather than the contribution
 * wizard's settings matrix, but versioned independently: the two answer different questions and will
 * change on different schedules.
 *
 * It carries **`device`** — the `Build.DEVICE` codename — which the contribution wizard's report
 * omits and which is exactly what a `QUALIFIED_CODENAMES` entry needs. It carries no raw settings
 * values at all: the only settings this run touched are policies Amply itself commanded, and they
 * appear as policy ids.
 */
data class QualificationReport(
    val schema: Int = QUALIFICATION_SCHEMA,
    val createdAtEpochMs: Long,
    val appVersion: String,
    val manufacturer: String,
    val brand: String,
    val model: String,
    val device: String,
    val fingerprint: String,
    val sdkInt: Int,
    val androidRelease: String,
    val buildIdentity: String,
    val oneUiVersion: Int?,
    val hyperOsVersion: Int?,
    val oplusRomVersion: Int?,
    val isLineageOs: Boolean,
    val lineageOsVersion: String?,
    val isGrapheneOs: Boolean,
    val adapterId: String,
    val candidatePromotion: Boolean,
    val protocolVersion: Int,
    val shape: RunShape,
    val signal: FlowSignal,
    val lowCap: Int,
    val releasePolicy: String,
    val observedHoldPercent: Int?,
    /**
     * False when the run could not read the user's configured policy and fell back to the adapter's
     * protective default. The restore then put back a guess rather than what was there, which a reader
     * of this report needs to know before drawing conclusions from it.
     */
    val baselineVerified: Boolean,
    val outcome: String,
    val runStartedAtWallMillis: Long,
    val runEndedAtWallMillis: Long,
    val phases: List<PhaseRecord>,
) {
    companion object {
        const val QUALIFICATION_SCHEMA = 1
    }
}

/** Human-readable outcome id, stable enough to grep a mailbox for. */
fun RunTerminal.reportId(): String = when (this) {
    is RunTerminal.Passed -> "passed"
    is RunTerminal.Refuted -> "refuted"
    is RunTerminal.Inconclusive -> "inconclusive:${reason.name.lowercase()}"
    is RunTerminal.Aborted -> "aborted:${reason.name.lowercase()}"
}

fun buildQualificationReport(
    record: QualificationRunRecord,
    terminal: RunTerminal,
    device: DeviceInfo,
    buildIdentity: String,
    appVersion: String,
    androidRelease: String,
    brand: String,
    createdAtEpochMs: Long,
): QualificationReport = QualificationReport(
    createdAtEpochMs = createdAtEpochMs,
    appVersion = appVersion,
    manufacturer = device.manufacturer,
    brand = brand,
    model = device.model,
    device = device.codename,
    fingerprint = device.fingerprint,
    sdkInt = device.sdk,
    androidRelease = androidRelease,
    buildIdentity = buildIdentity,
    oneUiVersion = device.oneUiVersion,
    hyperOsVersion = device.hyperOsVersion,
    oplusRomVersion = device.oplusRomVersion,
    isLineageOs = device.isLineageOs,
    lineageOsVersion = device.lineageOsVersion,
    isGrapheneOs = device.isGrapheneOs,
    adapterId = record.adapterId,
    candidatePromotion = record.candidate,
    protocolVersion = record.protocolVersion,
    shape = record.shape,
    signal = record.signal,
    lowCap = record.lowCap,
    releasePolicy = record.releasePolicy.stableId,
    observedHoldPercent = record.observedHoldPercent,
    baselineVerified = record.baselineVerified,
    outcome = terminal.reportId(),
    runStartedAtWallMillis = record.runStartedAtWallMillis,
    runEndedAtWallMillis = createdAtEpochMs,
    phases = record.phaseLog,
)

/** Deterministic `key=value` lines, same shape as the device-support report. Keep the field order fixed. */
fun formatQualificationReport(report: QualificationReport): String = buildString {
    appendLine("Amply guided qualification run")
    appendLine("qualification_schema=${report.schema}")
    appendLine("created_epoch_ms=${report.createdAtEpochMs}")
    appendLine("app_version=${sanitizeReportValue(report.appVersion)}")
    appendLine("manufacturer=${sanitizeReportValue(report.manufacturer)}")
    appendLine("brand=${sanitizeReportValue(report.brand)}")
    appendLine("model=${sanitizeReportValue(report.model)}")
    appendLine("device=${sanitizeReportValue(report.device)}")
    appendLine("fingerprint=${sanitizeReportValue(report.fingerprint, MAX_FINGERPRINT)}")
    appendLine("android_sdk=${report.sdkInt}")
    appendLine("android_release=${sanitizeReportValue(report.androidRelease)}")
    appendLine("build_identity=${sanitizeReportValue(report.buildIdentity)}")
    appendLine("one_ui_version=${report.oneUiVersion ?: "none"}")
    appendLine("hyperos_version=${report.hyperOsVersion ?: "none"}")
    appendLine("oplus_rom_version=${report.oplusRomVersion ?: "none"}")
    appendLine("is_lineageos=${report.isLineageOs}")
    appendLine("lineageos_version=${report.lineageOsVersion?.let { sanitizeReportValue(it) } ?: "none"}")
    appendLine("is_grapheneos=${report.isGrapheneOs}")
    appendLine("adapter=${sanitizeReportValue(report.adapterId)}")
    appendLine("candidate_promotion=${report.candidatePromotion}")
    appendLine("protocol_version=${report.protocolVersion}")
    appendLine("run_shape=${report.shape.name.lowercase()}")
    appendLine("accumulation_signal=${report.signal.name.lowercase()}")
    appendLine("cap_percent=${report.lowCap}")
    appendLine("release_policy=${sanitizeReportValue(report.releasePolicy)}")
    appendLine("observed_hold_percent=${report.observedHoldPercent ?: "none"}")
    appendLine("baseline_verified=${report.baselineVerified}")
    appendLine("outcome=${report.outcome}")
    appendLine("run_started_epoch_ms=${report.runStartedAtWallMillis}")
    appendLine("run_ended_epoch_ms=${report.runEndedAtWallMillis}")
    appendLine("run_duration_ms=${(report.runEndedAtWallMillis - report.runStartedAtWallMillis).coerceAtLeast(0)}")
    appendLine()
    appendLine("# phases (phase, commanded policy, entry %/counter, exit %/counter, duration ms)")
    if (report.phases.isEmpty()) {
        appendLine("(the run ended before completing a phase)")
    } else {
        report.phases.forEach { phase ->
            val duration = (phase.exitAtWallMillis - phase.enteredAtWallMillis).coerceAtLeast(0)
            appendLine(
                "${phase.phase.name} | ${sanitizeReportValue(phase.commanded).ifBlank { "none" }} | " +
                    "${phase.entryPercent}%/${phase.entryCounter ?: "none"} | " +
                    "${phase.exitPercent}%/${phase.exitCounter ?: "none"} | $duration",
            )
        }
    }
}

fun qualificationIssueTitle(report: QualificationReport): String =
    "[Qualification] ${report.manufacturer} ${report.model} (${report.device}): ${report.outcome}".trim()

fun qualificationIssueBody(report: QualificationReport): String = buildString {
    appendLine(
        "A guided qualification run was completed on-device. It drives the charge limit and watches " +
            "whether charging actually stops and restarts on command.",
    )
    appendLine()
    appendLine("### Run")
    appendLine()
    appendLine("```")
    appendLine(formatQualificationReport(report))
    appendLine("```")
}

fun qualificationIssueUrl(report: QualificationReport): String {
    val title = URLEncoder.encode(qualificationIssueTitle(report), Charsets.UTF_8.name())
    val body = URLEncoder.encode(qualificationIssueBody(report), Charsets.UTF_8.name())
    return "${DeviceSupportReporter.ISSUE_BASE_URL}?title=$title&body=$body"
}

private const val MAX_FINGERPRINT = 200
