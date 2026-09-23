package eu.darken.amply.backup.core

import eu.darken.amply.BuildConfig
import eu.darken.amply.alarm.core.ChargeAlarmStore
import eu.darken.amply.common.datastore.value
import eu.darken.amply.common.debug.logging.Logging
import eu.darken.amply.common.debug.logging.asLog
import eu.darken.amply.common.debug.logging.log
import eu.darken.amply.common.debug.logging.logTag
import eu.darken.amply.common.theming.ThemeSettings
import eu.darken.amply.fullcharge.core.FullChargeStore
import eu.darken.amply.rules.core.RuleApplier
import eu.darken.amply.stats.core.StatsPreferences
import kotlinx.coroutines.CancellationException
import javax.inject.Inject
import javax.inject.Singleton

private val TAG = logTag("Backup", "Repository")

enum class BackupPart { THEME, CHARGE_ALARM, RECONNECT_GESTURE, NOTIFICATION_BUTTONS, HISTORY_RETENTION, RULES }

data class BackupApplyResult(
    /** In write order. */
    val applied: List<BackupPart>,
    /** The part whose write threw; later parts were not attempted. */
    val failed: BackupPart?,
    /** The backup carried a disabled charge alarm and that part was applied. */
    val alarmDisabled: Boolean,
)

@Singleton
class BackupRepository @Inject constructor(
    private val themeSettings: ThemeSettings,
    private val chargeAlarmStore: ChargeAlarmStore,
    private val fullChargeStore: FullChargeStore,
    private val statsPreferences: StatsPreferences,
    private val ruleApplier: RuleApplier,
) {

    /** Reads the allowlisted portable settings and the rule set; nothing else is ever read. */
    suspend fun snapshot(nowMillis: Long = System.currentTimeMillis()): AmplyBackup = AmplyBackup(
        createdAt = nowMillis,
        appVersion = BuildConfig.VERSION_NAME,
        settings = BackupSettings(
            theme = themeSettings.state.value(),
            chargeAlarm = chargeAlarmStore.configNow(),
            reconnectGestureEnabled = fullChargeStore.isQuickFullChargeEnabled(),
            reconnectGestureAnyLevel = fullChargeStore.isQuickFullChargeAnyLevel(),
            // Null (the default selection) is exported as an explicit empty list, so an import restores it.
            reconnectNotificationPolicies = fullChargeStore.gestureNotificationPolicies.value() ?: emptyList(),
            historyRetentionDays = statsPreferences.retentionDaysNow(),
        ),
        rules = ruleApplier.rulesNow(),
    )

    /**
     * Writes only the parts [backup] carries, in [BackupPart] order, and stops at the first part
     * whose write throws. Parts already written stay written.
     */
    suspend fun apply(backup: AmplyBackup): BackupApplyResult {
        val settings = backup.settings
        val applied = mutableListOf<BackupPart>()
        var alarmDisabled = false

        suspend fun write(part: BackupPart, block: suspend () -> Unit): Boolean = try {
            block()
            applied += part
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log(TAG, Logging.Priority.ERROR) { "Writing $part failed: ${e.asLog()}" }
            false
        }

        fun failedAt(part: BackupPart) = BackupApplyResult(applied.toList(), part, alarmDisabled)

        settings.theme?.let { theme ->
            if (!write(BackupPart.THEME) { themeSettings.state.value(theme) }) return failedAt(BackupPart.THEME)
        }

        settings.chargeAlarm?.let { alarm ->
            val ok = write(BackupPart.CHARGE_ALARM) {
                chargeAlarmStore.setTargetPercent(alarm.targetPercent)
                chargeAlarmStore.setEnabled(alarm.enabled)
                if (!alarm.enabled) chargeAlarmStore.setFiredCycle(false)
            }
            if (!ok) return failedAt(BackupPart.CHARGE_ALARM)
            alarmDisabled = !alarm.enabled
        }

        if (settings.reconnectGestureEnabled != null || settings.reconnectGestureAnyLevel != null) {
            val ok = write(BackupPart.RECONNECT_GESTURE) {
                settings.reconnectGestureEnabled?.let { fullChargeStore.setQuickFullChargeEnabled(it) }
                settings.reconnectGestureAnyLevel?.let { fullChargeStore.setQuickFullChargeAnyLevel(it) }
            }
            if (!ok) return failedAt(BackupPart.RECONNECT_GESTURE)
        }

        settings.reconnectNotificationPolicies?.let { ids ->
            val ok = write(BackupPart.NOTIFICATION_BUTTONS) { fullChargeStore.setGestureNotificationPolicies(ids) }
            if (!ok) return failedAt(BackupPart.NOTIFICATION_BUTTONS)
        }

        settings.historyRetentionDays?.let { days ->
            val ok = write(BackupPart.HISTORY_RETENTION) { statsPreferences.setRetentionDays(days) }
            if (!ok) return failedAt(BackupPart.HISTORY_RETENTION)
        }

        backup.rules?.let { rules ->
            if (!write(BackupPart.RULES) { ruleApplier.replaceRules(rules) }) return failedAt(BackupPart.RULES)
        }

        log(TAG, Logging.Priority.INFO) { "Applied backup parts: $applied" }
        return BackupApplyResult(applied.toList(), failed = null, alarmDisabled = alarmDisabled)
    }
}
