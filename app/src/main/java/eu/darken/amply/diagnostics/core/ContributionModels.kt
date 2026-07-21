package eu.darken.amply.diagnostics.core

import eu.darken.amply.charging.core.access.SettingNamespace

/** Namespace-qualified setting identity. Namespace is part of identity — the same key name can exist in more than one. */
data class SettingId(val namespace: SettingNamespace, val key: String) {
    val display: String get() = "${namespace.commandName}/$key"
}

/**
 * One labeled full-namespace snapshot captured while the device sits in a particular OEM mode. The [snapshot] is
 * **raw** and never leaves the device except through the explicit two-stage review — it lives only inside
 * [RawWizardSession], never in the exported [ReviewedContributionReport].
 */
data class ModeObservation(
    val label: String,
    val capturedAtEpochMs: Long,
    val snapshot: Map<SettingId, String>,
)

/** On-device-only accumulator of raw observations. Owned by the wizard ViewModel; cleared on finish/back/reset. */
data class RawWizardSession(
    val observations: List<ModeObservation> = emptyList(),
)

/** Whether a derived matrix row can be auto-disclosed (a known charging key with only in-domain values) or must be opt-in. */
enum class Disclosure { AUTO, REDACTED }

/**
 * One changed setting across the captured modes. [valuesByMode] is aligned to the observation order; `null` means the
 * key was absent in that mode.
 */
data class MatrixRow(
    val id: SettingId,
    val valuesByMode: List<String?>,
    val disclosure: Disclosure,
)

/** A single per-mode observation the user optionally reported. Named an *effect*, never "verification". */
data class ModeEffect(
    val modeLabel: String,
    val effect: String,
)

/** Only rows explicitly approved for export (auto-disclosed, or opt-in revealed) reach this — already namespace/key/value bearing. */
data class ReviewedRow(
    val namespace: String,
    val key: String,
    val valuesByMode: List<String?>,
)

/**
 * The complete, immutable, export-safe contribution report. The pure formatter accepts **only** this type and has no
 * access to raw snapshots, so privacy cannot regress through a later formatting change. Redacted rows are represented
 * solely by [withheldRowCount] — their namespace, key, and values are absent entirely.
 */
data class ReviewedContributionReport(
    val schema: Int,
    val createdAtEpochMs: Long,
    val appVersion: String,
    val manufacturer: String,
    val model: String,
    val fingerprint: String,
    val sdkInt: Int,
    val oneUiVersion: Int?,
    val hyperOsVersion: Int?,
    val adapterId: String?,
    val romVersion: String,
    val featureName: String,
    val modeLabels: List<String>,
    val rows: List<ReviewedRow>,
    val withheldRowCount: Int,
    val effects: List<ModeEffect>,
    val notes: String,
)
