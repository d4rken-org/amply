package eu.darken.amply.main.ui.dashboard

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import eu.darken.amply.R
import eu.darken.amply.charging.core.ChargePolicy
import eu.darken.amply.charging.core.ChargingRepository
import eu.darken.amply.charging.core.ChargingState
import eu.darken.amply.common.debug.logging.Logging
import eu.darken.amply.common.debug.logging.log
import eu.darken.amply.common.debug.logging.logTag
import eu.darken.amply.fullcharge.core.ChargeSessionManager
import eu.darken.amply.fullcharge.core.ChargeSessionRecord
import eu.darken.amply.fullcharge.core.ChargeSessionService
import eu.darken.amply.fullcharge.core.FullChargeStore
import eu.darken.amply.main.core.DeviceSupportReport
import eu.darken.amply.main.core.DeviceSupportReporter
import eu.darken.amply.main.core.OnboardingSettings
import eu.darken.amply.main.core.formatReport
import eu.darken.amply.main.core.issueTitle
import eu.darken.amply.main.core.issueUrl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

data class DashboardUiState(
    val charging: ChargingState = ChargingState(),
    val session: ChargeSessionRecord? = null,
    val onboardingComplete: Boolean? = null,
    val quickFullChargeEnabled: Boolean = false,
    val deviceReport: DeviceSupportReport? = null,
)

@HiltViewModel
class DashboardViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: ChargingRepository,
    private val fullChargeStore: FullChargeStore,
    private val sessionManager: ChargeSessionManager,
    private val onboardingSettings: OnboardingSettings,
    private val deviceSupportReporter: DeviceSupportReporter,
) : ViewModel() {
    private val deviceReport = MutableStateFlow<DeviceSupportReport?>(null)

    val state = combine(
        repository.state,
        fullChargeStore.session,
        onboardingSettings.isComplete,
        fullChargeStore.quickFullChargeEnabled,
        deviceReport,
    ) { charging, session, onboardingComplete, quickFullChargeEnabled, report ->
        DashboardUiState(
            charging = charging,
            session = session,
            onboardingComplete = onboardingComplete,
            quickFullChargeEnabled = quickFullChargeEnabled,
            deviceReport = report,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DashboardUiState())

    val adbGrantCommand: String
        get() = "adb shell pm grant ${context.packageName} android.permission.WRITE_SECURE_SETTINGS"

    init {
        refresh()
    }

    fun refresh() = viewModelScope.launch { repository.refresh() }

    fun completeOnboarding() = viewModelScope.launch { onboardingSettings.complete() }

    fun applyPolicy(policy: ChargePolicy) = viewModelScope.launch {
        log(TAG, Logging.Priority.INFO) { "applyPolicy(${policy.stableId})" }
        if (fullChargeStore.currentSession() != null) sessionManager.cancelWithoutRestore()
        repository.applyPersistent(policy)
    }

    fun startFullCharge() {
        log(TAG, Logging.Priority.INFO) { "startFullCharge()" }
        ContextCompat.startForegroundService(
            context,
            Intent(context, ChargeSessionService::class.java).setAction(ChargeSessionService.ACTION_START),
        )
    }

    fun restoreNow() {
        ContextCompat.startForegroundService(
            context,
            Intent(context, ChargeSessionService::class.java).setAction(ChargeSessionService.ACTION_RESTORE),
        )
    }

    fun setQuickFullChargeEnabled(enabled: Boolean) = viewModelScope.launch {
        log(TAG, Logging.Priority.INFO) { "setQuickFullChargeEnabled($enabled)" }
        fullChargeStore.setQuickFullChargeEnabled(enabled)
        ContextCompat.startForegroundService(
            context,
            Intent(context, ChargeSessionService::class.java).setAction(ChargeSessionService.ACTION_MONITOR),
        )
    }

    fun requestShizukuPermission() = viewModelScope.launch {
        repository.requestShizukuPermission()
    }

    fun grantWriteSecureSettings() = viewModelScope.launch {
        repository.grantWriteSecureSettings()
    }

    fun openNativeSettings() {
        val intent = repository.nativeSettingsIntent() ?: Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS)
        runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            .onFailure {
                context.startActivity(
                    Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
    }

    fun openShizuku() = viewModelScope.launch {
        val launch = repository.shizukuManagerPackage()
            ?.let(context.packageManager::getLaunchIntentForPackage)
        val intent = launch ?: Intent(
            Intent.ACTION_VIEW,
            Uri.parse("https://shizuku.rikka.app/guide/setup/"),
        )
        runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

    fun copyAdbCommand() {
        context.getSystemService(ClipboardManager::class.java).setPrimaryClip(
            ClipData.newPlainText("Amply WSS command", adbGrantCommand),
        )
        Toast.makeText(context, "ADB command copied", Toast.LENGTH_SHORT).show()
    }

    /** Build the device-support report so the confirmation dialog can preview exactly what will be shared. */
    fun prepareDeviceSupportReport() = viewModelScope.launch {
        // Clear first so the dialog shows a loading placeholder and its Copy/Open buttons stay
        // disabled until this specific snapshot is ready — copy/open then reuse this exact value.
        deviceReport.value = null
        deviceReport.value = deviceSupportReporter.collect()
    }

    fun copyDeviceSupportReport() {
        val report = deviceReport.value ?: return
        context.getSystemService(ClipboardManager::class.java).setPrimaryClip(
            ClipData.newPlainText("Amply device report", formatReport(report)),
        )
        Toast.makeText(context, context.getString(R.string.setup_unsupported_report_copied), Toast.LENGTH_SHORT).show()
    }

    fun openDeviceSupportIssue() {
        val report = deviceReport.value ?: return
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(issueUrl(report)))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }.onFailure {
            when (it) {
                is ActivityNotFoundException -> Toast.makeText(
                    context,
                    context.getString(R.string.setup_unsupported_no_browser),
                    Toast.LENGTH_LONG,
                ).show()
                else -> log(TAG, Logging.Priority.WARN) { "Could not open issue URL: ${it.message}" }
            }
        }
    }

    /**
     * Lower-barrier alternative to GitHub: open the user's mail app with a short friendly message and
     * the device report attached as a .txt (not pasted raw into the body).
     */
    fun emailDeviceSupport() = viewModelScope.launch {
        val report = deviceReport.value ?: deviceSupportReporter.collect().also { deviceReport.value = it }
        val attachment = runCatching {
            withContext(Dispatchers.IO) {
                val dir = File(context.cacheDir, "support").apply { mkdirs() }
                val file = File(dir, "amply-device-report.txt")
                file.writeText(formatReport(report))
                FileProvider.getUriForFile(context, "${context.packageName}.files", file)
            }
        }.getOrNull()

        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_EMAIL, arrayOf("support@darken.eu"))
            putExtra(Intent.EXTRA_SUBJECT, issueTitle(report))
            putExtra(Intent.EXTRA_TEXT, context.getString(R.string.setup_unsupported_email_body))
            attachment?.let { putExtra(Intent.EXTRA_STREAM, it) }
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val chooser = Intent.createChooser(intent, context.getString(R.string.setup_unsupported_email_action))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(chooser) }.onFailure {
            Toast.makeText(context, context.getString(R.string.setup_unsupported_no_email), Toast.LENGTH_SHORT).show()
        }
    }

    private companion object {
        val TAG = logTag("Dashboard", "VM")
    }
}
