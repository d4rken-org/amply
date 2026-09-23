package eu.darken.amply.backup.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import eu.darken.amply.alarm.core.ChargeAlarmNotifications
import eu.darken.amply.backup.core.AmplyBackup
import eu.darken.amply.backup.core.BackupCodec
import eu.darken.amply.backup.core.BackupDecodeResult
import eu.darken.amply.backup.core.BackupPart
import eu.darken.amply.backup.core.BackupRepository
import eu.darken.amply.backup.core.countUnsupportedRules
import eu.darken.amply.backup.core.needsNotifications
import eu.darken.amply.backup.core.withDestinationCapability
import eu.darken.amply.backup.core.withNotificationsDenied
import eu.darken.amply.charging.core.ChargingRepository
import eu.darken.amply.common.debug.logging.Logging
import eu.darken.amply.common.debug.logging.log
import eu.darken.amply.common.debug.logging.logTag
import eu.darken.amply.common.flow.SingleEventFlow
import eu.darken.amply.fullcharge.core.ChargeSessionService
import eu.darken.amply.rules.core.RuleChargeGateway
import eu.darken.amply.upgrade.core.UpgradeRepo
import eu.darken.amply.upgrade.core.isProForUi
import eu.darken.amply.upgrade.core.isProStrict
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import kotlin.time.Duration.Companion.seconds

/** What the confirm dialog shows about a decoded file. */
data class BackupImportSummary(
    /** Epoch millis; 0 when the file does not say. */
    val createdAt: Long,
    /** Blank when the file does not say. */
    val appVersion: String,
    /** The settings parts the file carries, in write order. Never contains [BackupPart.RULES]. */
    val settingsParts: List<BackupPart>,
    /** Null when the file carries no rule set, which leaves the current rules alone. */
    val ruleCount: Int?,
)

sealed interface BackupMessage {
    data object ExportSaved : BackupMessage
    data object ExportFailed : BackupMessage
    data object ReadFailed : BackupMessage
    data object NotABackup : BackupMessage
    data class NewerVersion(val version: Int) : BackupMessage
    data object TooLarge : BackupMessage
    data object ProStatusUnknown : BackupMessage

    data class Imported(
        /** Null when no rule set was written. */
        val rulesImported: Int?,
        val skippedEntries: Int,
        val unsupportedRules: Int,
        val gestureDowngraded: Boolean,
        val notificationsDenied: Boolean,
        val partial: Boolean,
    ) : BackupMessage
}

data class BackupUiState(
    val busy: Boolean = false,
    val pendingImport: BackupImportSummary? = null,
    val message: BackupMessage? = null,
)

@HiltViewModel
class BackupViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val backupRepository: BackupRepository,
    private val codec: BackupCodec,
    private val upgradeRepo: UpgradeRepo,
    private val chargingRepository: ChargingRepository,
    private val ruleGateway: RuleChargeGateway,
) : ViewModel() {

    private val mutableState = MutableStateFlow(BackupUiState())
    val state: StateFlow<BackupUiState> = mutableState.asStateFlow()

    /** A gated action was used without the entitlement; the root navigates to the upgrade screen. */
    val upgradeRequiredEvents = SingleEventFlow<Unit>()

    /** The export may go ahead; carries the suggested file name for the document picker. */
    val exportProceedEvents = SingleEventFlow<String>()

    /** The import may go ahead; the root opens the document picker. */
    val importProceedEvents = SingleEventFlow<Unit>()

    /** The decided import enables something that notifies; the root asks, then calls [applyPendingImport]. */
    val notificationPermissionEvents = SingleEventFlow<Unit>()

    /** The file as decoded, held while the confirm dialog is up. */
    private var pendingDecode: BackupDecodeResult.Success? = null

    /** The import after the destination-capability decision, held across the notification prompt. */
    private var decidedImport: DecidedImport? = null

    fun requestExport() = routeThroughUiGate {
        // The picker creates the file, so the strict gate has to pass before it opens.
        if (passesStrictGate()) exportProceedEvents.emit(suggestedFileName())
    }

    fun requestImport() = routeThroughUiGate { importProceedEvents.emit(Unit) }

    fun export(uri: Uri?) {
        if (uri == null) return
        launchBusy {
            val saved = try {
                withContext(Dispatchers.IO) {
                    val text = codec.encode(backupRepository.snapshot())
                    val stream = context.contentResolver.openOutputStream(uri, "wt")
                    if (stream == null) {
                        log(TAG, Logging.Priority.WARN) { "Export failed: no output stream" }
                        false
                    } else {
                        stream.use { it.write(text.toByteArray(Charsets.UTF_8)) }
                        true
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Class name only: provider exception messages can carry the URI.
                log(TAG, Logging.Priority.WARN) { "Export failed: ${e.javaClass.simpleName}" }
                false
            }
            showMessage(if (saved) BackupMessage.ExportSaved else BackupMessage.ExportFailed)
        }
    }

    fun loadImport(uri: Uri?) {
        if (uri == null) return
        launchBusy {
            val result = try {
                withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.use { codec.read(it) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log(TAG, Logging.Priority.WARN) { "Reading the backup failed: ${e.javaClass.simpleName}" }
                null
            }
            when (result) {
                null -> showMessage(BackupMessage.ReadFailed)
                BackupDecodeResult.NotABackup -> showMessage(BackupMessage.NotABackup)
                is BackupDecodeResult.NewerVersion -> showMessage(BackupMessage.NewerVersion(result.version))
                BackupDecodeResult.TooLarge -> showMessage(BackupMessage.TooLarge)
                is BackupDecodeResult.Success -> {
                    pendingDecode = result
                    decidedImport = null
                    mutableState.update { it.copy(pendingImport = result.backup.toSummary()) }
                }
            }
        }
    }

    fun cancelImport() {
        // A confirm in flight owns the pending import until it settles.
        if (mutableState.value.busy) return
        pendingDecode = null
        decidedImport = null
        mutableState.update { it.copy(pendingImport = null) }
    }

    fun confirmImport() {
        val decoded = pendingDecode ?: return
        launchBusy {
            // A failed gate keeps the dialog up: a retry, or a return from a completed upgrade,
            // lands back on the same file instead of the picker.
            if (!passesStrictGate()) return@launchBusy
            pendingDecode = null
            mutableState.update { it.copy(pendingImport = null) }

            val resolved = withTimeoutOrNull(ADAPTER_WAIT) {
                chargingRepository.state.first { it.adapterResolved }
            }
            val gestureAvailable = resolved?.let { it.reconnectSupported && it.canApply } == true
            val outcome = decoded.backup.withDestinationCapability(gestureAvailable)
            decidedImport = DecidedImport(
                backup = outcome.backup,
                gestureDowngraded = outcome.gestureDowngraded,
                skippedEntries = decoded.skippedFields.size + decoded.skippedRules,
            )

            val promptNeeded = outcome.backup.needsNotifications() &&
                Build.VERSION.SDK_INT >= 33 &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED
            if (promptNeeded) {
                notificationPermissionEvents.emit(Unit)
            } else {
                applyDecided(notificationsGranted = true)
            }
        }
    }

    /** The notification prompt's answer; always called, granted or not, so the import is never left hanging. */
    fun applyPendingImport(notificationsGranted: Boolean) {
        if (decidedImport == null) return
        launchBusy { applyDecided(notificationsGranted) }
    }

    fun onMessageShown() {
        mutableState.update { it.copy(message = null) }
    }

    private suspend fun applyDecided(notificationsGranted: Boolean) {
        val decided = decidedImport ?: return
        decidedImport = null
        val backup = if (notificationsGranted) decided.backup else decided.backup.withNotificationsDenied()

        val result = backupRepository.apply(backup)
        if (result.alarmDisabled) ChargeAlarmNotifications.cancel(context)
        nudgeService(ChargeSessionService.ACTION_MONITOR)
        nudgeService(ChargeSessionService.ACTION_EVALUATE_RULES)

        val importedRules = backup.rules?.takeIf { BackupPart.RULES in result.applied }
        val unsupportedRules = importedRules?.let { rules ->
            withContext(Dispatchers.Default) { countUnsupportedRules(rules, ruleGateway.supportedPolicyIds()) }
        } ?: 0
        showMessage(
            BackupMessage.Imported(
                rulesImported = importedRules?.size,
                skippedEntries = decided.skippedEntries,
                unsupportedRules = unsupportedRules,
                gestureDowngraded = decided.gestureDowngraded,
                notificationsDenied = !notificationsGranted,
                partial = result.failed != null,
            ),
        )
    }

    /**
     * The entitlement check before any export write or import apply. Denies on every doubt; a doubt
     * that is not a settled "no purchase" asks for a retry rather than sending a paying user to the
     * upgrade screen.
     */
    private suspend fun passesStrictGate(): Boolean {
        if (upgradeRepo.isProStrict()) return true
        val current = try {
            upgradeRepo.upgradeInfo.first()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log(TAG, Logging.Priority.WARN) { "Reading the upgrade state failed: ${e.javaClass.simpleName}" }
            null
        }
        if (current == null || current.error != null || !current.isSettled) {
            log(TAG, Logging.Priority.WARN) { "Pro status unconfirmed, backup action refused" }
            showMessage(BackupMessage.ProStatusUnknown)
        } else {
            log(TAG) { "Backup action denied, routing to the upgrade screen" }
            upgradeRequiredEvents.emit(Unit)
        }
        return false
    }

    private fun routeThroughUiGate(proceed: suspend () -> Unit) = launchBusy {
        if (upgradeRepo.isProForUi()) {
            proceed()
        } else {
            log(TAG) { "Backup action denied, routing to the upgrade screen" }
            upgradeRequiredEvents.emit(Unit)
        }
    }

    /** One operation at a time: the screen disables its actions while [BackupUiState.busy], and this enforces it. */
    private fun launchBusy(block: suspend () -> Unit) {
        if (mutableState.value.busy) return
        mutableState.update { it.copy(busy = true) }
        viewModelScope.launch {
            try {
                block()
            } finally {
                mutableState.update { it.copy(busy = false) }
            }
        }
    }

    private fun showMessage(message: BackupMessage) {
        mutableState.update { it.copy(message = message) }
    }

    private fun nudgeService(action: String) {
        val intent = Intent(context, ChargeSessionService::class.java).setAction(action)
        try {
            ContextCompat.startForegroundService(context, intent)
        } catch (e: Exception) {
            log(TAG, Logging.Priority.WARN) { "Service nudge ($action) failed: ${e.message}" }
        }
    }

    private fun AmplyBackup.toSummary() = BackupImportSummary(
        createdAt = createdAt,
        appVersion = appVersion,
        settingsParts = buildList {
            if (settings.theme != null) add(BackupPart.THEME)
            if (settings.chargeAlarm != null) add(BackupPart.CHARGE_ALARM)
            if (settings.reconnectGestureEnabled != null || settings.reconnectGestureAnyLevel != null) {
                add(BackupPart.RECONNECT_GESTURE)
            }
            if (settings.reconnectNotificationPolicies != null) add(BackupPart.NOTIFICATION_BUTTONS)
            if (settings.historyRetentionDays != null) add(BackupPart.HISTORY_RETENTION)
        },
        ruleCount = rules?.size,
    )

    private fun suggestedFileName(): String =
        "amply-backup-${LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE)}.json"

    private data class DecidedImport(
        val backup: AmplyBackup,
        val gestureDowngraded: Boolean,
        val skippedEntries: Int,
    )

    private companion object {
        val TAG = logTag("Backup", "ViewModel")
        val ADAPTER_WAIT = 5.seconds
    }
}
