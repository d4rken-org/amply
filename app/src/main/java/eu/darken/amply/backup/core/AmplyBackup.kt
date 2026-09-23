package eu.darken.amply.backup.core

import eu.darken.amply.alarm.core.ChargeAlarmConfig
import eu.darken.amply.common.theming.ThemeState
import eu.darken.amply.rules.core.ChargeRule
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The portable configuration file, format v1.
 *
 * Every nullable field means "not in the file, leave the destination alone" — a backup restores
 * what it carries and nothing else. [rules] follows the same rule: null leaves the rule set alone,
 * an empty list clears it.
 */
@Serializable
data class AmplyBackup(
    @SerialName("format") val format: String = FORMAT,
    @SerialName("version") val version: Int = CURRENT_VERSION,
    /** Epoch millis. Display-only. */
    @SerialName("createdAt") val createdAt: Long = 0L,
    /** Display-only. */
    @SerialName("appVersion") val appVersion: String = "",
    @SerialName("settings") val settings: BackupSettings = BackupSettings(),
    @SerialName("rules") val rules: List<ChargeRule>? = null,
) {
    companion object {
        const val FORMAT = "eu.darken.amply.backup"
        const val CURRENT_VERSION = 1

        /** Far above any real configuration; the cap only exists so a wrong file cannot exhaust memory. */
        const val MAX_BACKUP_BYTES = 1024 * 1024
    }
}

@Serializable
data class BackupSettings(
    @SerialName("theme") val theme: ThemeState? = null,
    @SerialName("chargeAlarm") val chargeAlarm: ChargeAlarmConfig? = null,
    @SerialName("reconnectGestureEnabled") val reconnectGestureEnabled: Boolean? = null,
    @SerialName("reconnectGestureAnyLevel") val reconnectGestureAnyLevel: Boolean? = null,
    /** Policy stable ids. An empty list means "restore the default selection", not "select nothing". */
    @SerialName("reconnectNotificationPolicies") val reconnectNotificationPolicies: List<String>? = null,
    @SerialName("historyRetentionDays") val historyRetentionDays: Int? = null,
)
