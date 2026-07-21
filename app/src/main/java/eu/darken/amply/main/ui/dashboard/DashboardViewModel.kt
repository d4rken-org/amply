package eu.darken.amply.main.ui.dashboard

import android.app.StatusBarManager
import android.appwidget.AppWidgetManager
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Build
import android.os.SystemClock
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
import eu.darken.amply.fullcharge.core.ServiceDispatch
import eu.darken.amply.main.core.DeviceSupportReport
import eu.darken.amply.main.core.DeviceSupportReporter
import eu.darken.amply.main.core.OnboardingSettings
import eu.darken.amply.main.core.QuickAccessState
import eu.darken.amply.main.core.QuickAccessStore
import eu.darken.amply.main.ui.tile.ChargeTileService
import eu.darken.amply.main.ui.widget.AmplyWidgetReceiver
import eu.darken.amply.main.core.formatReport
import eu.darken.amply.main.core.issueTitle
import eu.darken.amply.main.core.issueUrl
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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
    val quickFullChargeAnyLevel: Boolean = false,
    val deviceReport: DeviceSupportReport? = null,
    val quickAccess: QuickAccessState = QuickAccessState(),
    /** True once the initial widget-presence check has completed; the promo card hides until then. */
    val quickAccessChecked: Boolean = false,
    val tileRequestPending: Boolean = false,
)

@HiltViewModel
class DashboardViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: ChargingRepository,
    private val fullChargeStore: FullChargeStore,
    private val sessionManager: ChargeSessionManager,
    private val onboardingSettings: OnboardingSettings,
    private val deviceSupportReporter: DeviceSupportReporter,
    private val quickAccessStore: QuickAccessStore,
) : ViewModel() {
    private val deviceReport = MutableStateFlow<DeviceSupportReport?>(null)
    private val quickAccessChecked = MutableStateFlow(false)
    private val tileRequestPending = MutableStateFlow(false)
    private var lastPinRequestAt = 0L

    // The typed combine overloads stop at five flows; the two gesture booleans are pre-combined.
    private val gestureFlags = combine(
        fullChargeStore.quickFullChargeEnabled,
        fullChargeStore.quickFullChargeAnyLevel,
    ) { enabled, anyLevel -> enabled to anyLevel }

    val state = combine(
        combine(
            repository.state,
            fullChargeStore.session,
            onboardingSettings.isComplete,
            gestureFlags,
            deviceReport,
        ) { charging, session, onboardingComplete, (quickFullChargeEnabled, quickFullChargeAnyLevel), report ->
            DashboardUiState(
                charging = charging,
                session = session,
                onboardingComplete = onboardingComplete,
                quickFullChargeEnabled = quickFullChargeEnabled,
                quickFullChargeAnyLevel = quickFullChargeAnyLevel,
                deviceReport = report,
            )
        },
        quickAccessStore.state,
        quickAccessChecked,
        tileRequestPending,
    ) { base, quickAccess, checked, tilePending ->
        base.copy(
            quickAccess = quickAccess,
            quickAccessChecked = checked,
            tileRequestPending = tilePending,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DashboardUiState())

    val adbGrantCommand: String
        get() = "adb shell pm grant ${context.packageName} android.permission.WRITE_SECURE_SETTINGS"

    init {
        refresh()
    }

    fun refresh() = viewModelScope.launch { repository.refresh() }

    /**
     * Foreground-entry nudge: after a force-stop, no receiver or sticky restart revives the
     * session service, so each resume checks for leftover work (persisted session, pending
     * recovery, enabled gesture) and pokes the service with ACTION_CHECK. The returned job is
     * cancelled on pause so the store reads can't complete after backgrounding and trigger a
     * foreground-service start from the background.
     */
    fun nudgeChargeService(): Job = viewModelScope.launch {
        try {
            // The first run in a new boot keeps conservative BOOT (restore) semantics even from
            // here: a launch of an app that stayed stopped across a reboot races the deferred
            // BOOT_COMPLETED delivery, and seeding the boot count first would downgrade both
            // paths to CHECK and resume a stale pre-reboot session.
            val bootCount = ServiceDispatch.currentBootCount(context)
            val trigger = ServiceDispatch.bootTrigger(
                currentBootCount = bootCount,
                lastSeenBootCount = fullChargeStore.lastSeenBootCount(),
            )
            val action = ServiceDispatch.startAction(
                trigger = trigger,
                sessionExists = fullChargeStore.currentSession() != null,
                pendingRecovery = fullChargeStore.pendingRecoveryTarget() != null,
                gestureEnabled = fullChargeStore.isQuickFullChargeEnabled(),
            )
            if (action != null) {
                log(TAG) { "Foreground nudge (trigger=$trigger): starting service with $action" }
                ContextCompat.startForegroundService(context, ServiceDispatch.startIntent(context, action))
            }
            // Record only after a start that didn't throw, so a failed dispatch is retried with
            // unchanged semantics on the next resume.
            bootCount?.let { fullChargeStore.setLastSeenBootCount(it) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Pause-cancellation is a best effort; if backgrounding still races the start
            // (ForegroundServiceStartNotAllowedException), the next resume retries.
            log(TAG, Logging.Priority.WARN) { "Foreground nudge failed: ${e.message}" }
        }
    }

    fun completeOnboarding() = viewModelScope.launch { onboardingSettings.complete() }

    fun applyPolicy(policy: ChargePolicy) = viewModelScope.launch {
        log(TAG, Logging.Priority.INFO) { "applyPolicy(${policy.stableId})" }
        if (fullChargeStore.currentSession() != null) sessionManager.cancelWithoutRestore()
        repository.applyPersistent(policy)
        // The persistent policy is an any-level arming input; nudge a running gesture monitor so
        // arming and notification copy react now instead of on the next broadcast/30s poll.
        if (fullChargeStore.isQuickFullChargeEnabled()) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, ChargeSessionService::class.java)
                    .setAction(ChargeSessionService.ACTION_MONITOR),
            )
        }
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

    fun setQuickFullChargeAnyLevel(enabled: Boolean) = viewModelScope.launch {
        log(TAG, Logging.Priority.INFO) { "setQuickFullChargeAnyLevel($enabled)" }
        fullChargeStore.setQuickFullChargeAnyLevel(enabled)
        // Nudge a running monitor so the notification copy and arming reflect the change now
        // instead of on the next broadcast; a stopped/ineligible service just stops itself again.
        ContextCompat.startForegroundService(
            context,
            Intent(context, ChargeSessionService::class.java).setAction(ChargeSessionService.ACTION_MONITOR),
        )
    }

    /**
     * Best-effort widget discovery for the quick-access promotion. Deliberately separate from
     * [refresh]: that runs on every battery broadcast, and a binder/DataStore failure here must
     * never affect charging state. Until the first successful check the promo card stays hidden —
     * offering "Add widget" before knowing one exists could create a duplicate.
     */
    fun refreshQuickAccessPresence() = viewModelScope.launch {
        runCatching {
            val widgetIds = withContext(Dispatchers.Default) {
                AppWidgetManager.getInstance(context)
                    .getAppWidgetIds(ComponentName(context, AmplyWidgetReceiver::class.java))
            }
            if (widgetIds.isNotEmpty()) quickAccessStore.markWidgetAdded()
            quickAccessChecked.value = true
        }.onFailure {
            // Stay "unchecked" so the card keeps hidden; the next resume retries.
            log(TAG, Logging.Priority.WARN) { "Quick-access widget check failed: ${it.message}" }
        }
    }

    fun dismissQuickAccess() = viewModelScope.launch { quickAccessStore.dismiss() }

    fun requestPinWidget() {
        // The pin dialog is modal once shown, but rapid taps before it appears would queue
        // multiple launcher requests — swallow them.
        val now = SystemClock.elapsedRealtime()
        if (now - lastPinRequestAt < 1_000) return
        lastPinRequestAt = now

        // No success PendingIntent: the launcher accepting the request doesn't mean placement, so
        // confirmation comes from the presence re-check on the next resume instead.
        val requested = runCatching {
            val manager = AppWidgetManager.getInstance(context)
            manager.isRequestPinAppWidgetSupported &&
                manager.requestPinAppWidget(ComponentName(context, AmplyWidgetReceiver::class.java), null, null)
        }.onFailure {
            log(TAG, Logging.Priority.WARN) { "requestPinAppWidget failed: ${it.message}" }
        }.getOrDefault(false)

        if (!requested) {
            Toast.makeText(
                context,
                context.getString(R.string.dashboard_quickaccess_widget_manual),
                Toast.LENGTH_LONG,
            ).show()
        }
    }

    fun requestAddTile() {
        if (Build.VERSION.SDK_INT < 33) {
            // No add-tile API before Tiramisu (reachable: legacy Samsung support covers One UI 4/5).
            Toast.makeText(
                context,
                context.getString(R.string.dashboard_quickaccess_tile_manual),
                Toast.LENGTH_LONG,
            ).show()
            return
        }
        // SystemUI rejects overlapping requests with TILE_ADD_REQUEST_ERROR_REQUEST_IN_PROGRESS;
        // the flag also disables the button until the callback resolves.
        if (!tileRequestPending.compareAndSet(expect = false, update = true)) return
        runCatching {
            context.getSystemService(StatusBarManager::class.java).requestAddTileService(
                ComponentName(context, ChargeTileService::class.java),
                context.getString(R.string.tile_label),
                Icon.createWithResource(context, R.drawable.ic_launcher_monochrome),
                ContextCompat.getMainExecutor(context),
            ) { result ->
                tileRequestPending.value = false
                when (result) {
                    StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED,
                    StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED,
                    -> viewModelScope.launch { quickAccessStore.markTileAdded() }
                    // User declined or closed the dialog — keep the card, no nagging toast.
                    StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_NOT_ADDED -> Unit
                    else -> {
                        log(TAG, Logging.Priority.WARN) { "requestAddTileService result: $result" }
                        Toast.makeText(
                            context,
                            context.getString(R.string.dashboard_quickaccess_tile_manual),
                            Toast.LENGTH_LONG,
                        ).show()
                    }
                }
            }
        }.onFailure {
            tileRequestPending.value = false
            log(TAG, Logging.Priority.WARN) { "requestAddTileService failed: ${it.message}" }
            Toast.makeText(
                context,
                context.getString(R.string.dashboard_quickaccess_tile_manual),
                Toast.LENGTH_LONG,
            ).show()
        }
    }

    fun requestShizukuPermission() = viewModelScope.launch {
        repository.requestShizukuPermission()
    }

    fun grantWriteSecureSettings() = viewModelScope.launch {
        // The status card's result message can be scrolled off-screen while the user watches the setup
        // card, so surface a failure locally too. The repository returns the refreshed permission state
        // (WSS actually granted), so false is a real failure — not a lost pm-grant reply.
        val granted = repository.grantWriteSecureSettings()
        if (!granted) {
            Toast.makeText(
                context,
                context.getString(R.string.dashboard_wss_grant_failed_toast),
                Toast.LENGTH_LONG,
            ).show()
        }
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
        Toast.makeText(context, context.getString(R.string.dashboard_adb_copied), Toast.LENGTH_SHORT).show()
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
     * the device report attached as a .txt. If the attachment can't be created, the report is inlined
     * into the body instead, so the email always carries the details the copy promises.
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

        val body = buildString {
            append(context.getString(R.string.setup_unsupported_email_body))
            if (attachment == null) {
                // No attachment could be created; inline the report so it isn't lost.
                append("\n\n")
                append(formatReport(report))
            }
        }
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_EMAIL, arrayOf("support@darken.eu"))
            putExtra(Intent.EXTRA_SUBJECT, issueTitle(report))
            putExtra(Intent.EXTRA_TEXT, body)
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
