package eu.darken.amply.main.ui.dashboard

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
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
import eu.darken.amply.main.core.OnboardingSettings
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class DashboardUiState(
    val charging: ChargingState = ChargingState(),
    val session: ChargeSessionRecord? = null,
    val onboardingComplete: Boolean? = null,
    val quickFullChargeEnabled: Boolean = false,
)

@HiltViewModel
class DashboardViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: ChargingRepository,
    private val fullChargeStore: FullChargeStore,
    private val sessionManager: ChargeSessionManager,
    private val onboardingSettings: OnboardingSettings,
) : ViewModel() {
    val state = combine(
        repository.state,
        fullChargeStore.session,
        onboardingSettings.isComplete,
        fullChargeStore.quickFullChargeEnabled,
    ) { charging, session, onboardingComplete, quickFullChargeEnabled ->
        DashboardUiState(
            charging = charging,
            session = session,
            onboardingComplete = onboardingComplete,
            quickFullChargeEnabled = quickFullChargeEnabled,
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

    private companion object {
        val TAG = logTag("Dashboard", "VM")
    }
}
