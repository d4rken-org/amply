package eu.darken.amply.diagnostics.core

import eu.darken.amply.charging.core.DeviceInfo
import eu.darken.amply.main.core.DeviceSupportReporter
import eu.darken.amply.main.core.sanitizeReportValue
import java.net.URLEncoder

internal const val CONTRIBUTION_SCHEMA = 1

/** Conservative cap for a GitHub issue prefill URL; browsers/GitHub start truncating well above this. */
internal const val MAX_ISSUE_URL_BYTES = 6_000

private const val MAX_NOTE = 500
private const val MAX_FINGERPRINT = 200

/**
 * Derives the changed-setting matrix across the captured modes. A setting is "changed" only if it does not hold the
 * same value in every mode (an absent value counts as distinct). A row auto-discloses when it is a known charging key
 * whose every observed value is inside its public domain; anything else is redacted.
 */
internal fun deriveMatrix(observations: List<ModeObservation>): List<MatrixRow> {
    val allKeys = observations.flatMapTo(sortedSetOf(compareBy({ it.namespace }, { it.key }))) { it.snapshot.keys }
    return allKeys.mapNotNull { id ->
        val valuesByMode = observations.map { it.snapshot[id] }
        if (valuesByMode.toSet().size <= 1) return@mapNotNull null
        val domain = ContributionAllowlist.publicValues(id)
        val allInDomain = domain != null && valuesByMode.filterNotNull().all { it in domain }
        MatrixRow(id, valuesByMode, if (allInDomain) Disclosure.AUTO else Disclosure.REDACTED)
    }
}

/**
 * Assembles the export-safe report from the raw session plus the user's explicit inclusion choices. This is the only
 * place raw values cross into the reviewed model, and every field is sanitized here — the pure formatter below never
 * sees a raw snapshot. Redacted rows the user did not approve are dropped entirely (no namespace/key/value survives),
 * counted only by [ReviewedContributionReport.withheldRowCount].
 */
internal fun buildReviewedReport(
    session: RawWizardSession,
    approvedRedacted: Set<SettingId>,
    device: DeviceInfo,
    appVersion: String,
    adapterId: String?,
    romVersion: String,
    featureName: String,
    effects: List<ModeEffect>,
    notes: String,
    createdAtEpochMs: Long,
    schema: Int = CONTRIBUTION_SCHEMA,
): ReviewedContributionReport {
    val matrix = deriveMatrix(session.observations)
    val exportRows = matrix.filter { it.disclosure == Disclosure.AUTO || it.id in approvedRedacted }
    return ReviewedContributionReport(
        schema = schema,
        createdAtEpochMs = createdAtEpochMs,
        appVersion = sanitizeReportValue(appVersion),
        manufacturer = sanitizeReportValue(device.manufacturer),
        model = sanitizeReportValue(device.model),
        fingerprint = sanitizeReportValue(device.fingerprint, MAX_FINGERPRINT),
        sdkInt = device.sdk,
        oneUiVersion = device.oneUiVersion,
        hyperOsVersion = device.hyperOsVersion,
        adapterId = adapterId?.let { sanitizeReportValue(it) },
        romVersion = sanitizeReportValue(romVersion),
        featureName = sanitizeReportValue(featureName),
        modeLabels = session.observations.map { sanitizeReportValue(it.label) },
        rows = exportRows.map { row ->
            ReviewedRow(
                namespace = row.id.namespace.commandName,
                key = sanitizeReportValue(row.id.key),
                valuesByMode = row.valuesByMode.map { value -> value?.let { sanitizeReportValue(it) } },
            )
        },
        withheldRowCount = matrix.size - exportRows.size,
        effects = effects.map { ModeEffect(sanitizeReportValue(it.modeLabel), sanitizeReportValue(it.effect)) },
        notes = sanitizeReportValue(notes, MAX_NOTE),
    )
}

/** Pure formatter: [ReviewedContributionReport] → text. No raw-snapshot access, so privacy can't regress here. */
internal fun formatContributionReport(report: ReviewedContributionReport): String = buildString {
    appendLine("contribution_schema=${report.schema}")
    appendLine("created_epoch_ms=${report.createdAtEpochMs}")
    appendLine("app_version=${report.appVersion}")
    appendLine("manufacturer=${report.manufacturer}")
    appendLine("model=${report.model}")
    appendLine("android_sdk=${report.sdkInt}")
    appendLine("fingerprint=${report.fingerprint}")
    appendLine("one_ui_version=${report.oneUiVersion ?: "none"}")
    appendLine("hyperos_version=${report.hyperOsVersion ?: "none"}")
    appendLine("adapter=${report.adapterId ?: "none"}")
    appendLine("rom_version=${report.romVersion.ifBlank { "unspecified" }}")
    appendLine("feature_name=${report.featureName.ifBlank { "unspecified" }}")
    appendLine("modes=${report.modeLabels.joinToString(" | ")}")
    appendLine()
    appendLine("# changed settings (value per mode, in the order above)")
    if (report.rows.isEmpty()) {
        appendLine("(no settings approved for inclusion)")
    } else {
        report.rows.forEach { row ->
            appendLine("${row.namespace}/${row.key} = ${row.valuesByMode.joinToString(" | ") { it ?: "<absent>" }}")
        }
    }
    if (report.withheldRowCount > 0) {
        appendLine("withheld_rows=${report.withheldRowCount} (redacted; not included by the contributor)")
    }
    if (report.effects.isNotEmpty()) {
        appendLine()
        appendLine("# user_reported_effect (self-reported, NOT verification)")
        report.effects.forEach { appendLine("${it.modeLabel}: ${it.effect}") }
    }
    if (report.notes.isNotBlank()) {
        appendLine()
        appendLine("# notes")
        appendLine(report.notes)
    }
}.trimEnd('\n')

internal fun contributionIssueTitle(report: ReviewedContributionReport): String =
    "[Device support] ${report.manufacturer} ${report.model} — settings discovery".trim()

/** Markdown issue body (also used verbatim for copy/share). Uses a fence longer than any backtick run in the body. */
internal fun contributionIssueBody(report: ReviewedContributionReport): String {
    val body = formatContributionReport(report)
    val fence = "`".repeat(maxOf(3, (Regex("`+").findAll(body).maxOfOrNull { it.value.length } ?: 0) + 1))
    return buildString {
        appendLine("Thanks for helping add support for your device. Everything below was collected on-device and reviewed by you before sending.")
        appendLine()
        appendLine("This is *candidate discovery* to help a maintainer reproduce the mapping — not a guarantee of support.")
        appendLine()
        appendLine(fence)
        appendLine(body)
        append(fence)
    }
}

/** Outcome of preparing the prefilled issue: a launchable URL, or "too large" so the caller falls back to copy/email. */
sealed interface IssueDelivery {
    data class Url(val url: String) : IssueDelivery
    data class TooLarge(val encodedBytes: Int) : IssueDelivery
}

internal fun contributionIssueDelivery(report: ReviewedContributionReport): IssueDelivery {
    val title = URLEncoder.encode(contributionIssueTitle(report), Charsets.UTF_8.name())
    val body = URLEncoder.encode(contributionIssueBody(report), Charsets.UTF_8.name())
    val url = "${DeviceSupportReporter.ISSUE_BASE_URL}?title=$title&body=$body"
    val bytes = url.toByteArray(Charsets.UTF_8).size
    return if (bytes <= MAX_ISSUE_URL_BYTES) IssueDelivery.Url(url) else IssueDelivery.TooLarge(bytes)
}
