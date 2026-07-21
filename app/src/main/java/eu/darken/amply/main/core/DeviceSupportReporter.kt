package eu.darken.amply.main.core

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import dagger.hilt.android.qualifiers.ApplicationContext
import eu.darken.amply.BuildConfig
import eu.darken.amply.charging.core.DeviceInfo
import eu.darken.amply.charging.core.adapter.AdapterRegistry
import eu.darken.amply.common.AmplyLinks
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Immutable snapshot of best-effort, non-privileged device metadata used to ask the developer to add
 * charge-control support for an OEM. Deliberately carries no OEM charging-setting values — those come
 * only from the Shizuku-gated diagnostics tool, never from production reads.
 */
data class DeviceSupportReport(
    val manufacturer: String,
    val brand: String,
    val model: String,
    val device: String,
    val product: String,
    val fingerprint: String,
    val sdkInt: Int,
    val release: String,
    val isPhone: Boolean,
    val hasChargingOptimization: Boolean,
    val oneUiVersion: Int?,
    val hyperOsVersion: Int?,
    val hasProtectBattery: Boolean,
    val adapterId: String?,
    val adapterMatched: Boolean,
    val adapterControlEnabled: Boolean,
    val contributionWanted: Boolean,
    val batteryChargingStatus: Int,
    val batteryPlugged: Boolean,
    val appVersionName: String,
    val appVersionCode: Int,
    val flavor: String,
    val buildType: String,
)

@Singleton
class DeviceSupportReporter @Inject constructor(
    @ApplicationContext private val context: Context,
    private val registry: AdapterRegistry,
) {
    suspend fun collect(): DeviceSupportReport = withContext(Dispatchers.IO) {
        val device = DeviceInfo.current(context)
        val selection = registry.select(device)
        val battery = runCatching {
            context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        }.getOrNull()
        DeviceSupportReport(
            manufacturer = sanitizeReportValue(Build.MANUFACTURER),
            brand = sanitizeReportValue(Build.BRAND),
            model = sanitizeReportValue(Build.MODEL),
            device = sanitizeReportValue(Build.DEVICE),
            product = sanitizeReportValue(Build.PRODUCT),
            fingerprint = sanitizeReportValue(Build.FINGERPRINT, MAX_FINGERPRINT),
            sdkInt = Build.VERSION.SDK_INT,
            release = sanitizeReportValue(Build.VERSION.RELEASE),
            isPhone = device.isPhone,
            hasChargingOptimization = device.hasChargingOptimization,
            oneUiVersion = device.oneUiVersion,
            hyperOsVersion = device.hyperOsVersion,
            hasProtectBattery = device.hasProtectBattery,
            adapterId = selection.adapter?.id,
            adapterMatched = selection.support.matched,
            adapterControlEnabled = selection.support.controlEnabled,
            contributionWanted = selection.support.contributionWanted,
            batteryChargingStatus = battery?.getIntExtra(BatteryManager.EXTRA_CHARGING_STATUS, -1) ?: -1,
            batteryPlugged = (battery?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0,
            appVersionName = BuildConfig.VERSION_NAME,
            appVersionCode = BuildConfig.VERSION_CODE,
            flavor = BuildConfig.FLAVOR,
            buildType = BuildConfig.BUILD_TYPE,
        )
    }

    companion object {
        const val ISSUE_BASE_URL = "${AmplyLinks.ISSUES}/new"
        private const val MAX_VALUE = 120
        private const val MAX_FINGERPRINT = 200
    }
}

private val CONTROL_CHARS = Regex("\\p{Cntrl}+")

/** Collapse control characters (incl. CR/LF) to a single space and bound length, so a value stays one line. */
internal fun sanitizeReportValue(value: String?, max: Int = 120): String {
    val cleaned = value.orEmpty().replace(CONTROL_CHARS, " ").trim()
    return if (cleaned.length > max) cleaned.take(max) + "…" else cleaned
}

/** Deterministic, single stable schema. Keep field order fixed so reports are diff-friendly. */
internal fun formatReport(report: DeviceSupportReport): String = buildString {
    appendLine("Amply device-support request")
    appendLine("report_schema=5")
    appendLine("app_version=${report.appVersionName} (${report.appVersionCode})")
    appendLine("distribution=${report.flavor}/${report.buildType}")
    appendLine("manufacturer=${report.manufacturer}")
    appendLine("brand=${report.brand}")
    appendLine("model=${report.model}")
    appendLine("device=${report.device}")
    appendLine("product=${report.product}")
    appendLine("fingerprint=${report.fingerprint}")
    appendLine("android_sdk=${report.sdkInt}")
    appendLine("android_release=${report.release}")
    appendLine("is_phone=${report.isPhone}")
    appendLine("has_charging_optimization=${report.hasChargingOptimization}")
    appendLine("one_ui_version=${report.oneUiVersion ?: "none"}")
    appendLine("hyperos_version=${report.hyperOsVersion ?: "none"}")
    appendLine("has_protect_battery=${report.hasProtectBattery}")
    appendLine("adapter=${report.adapterId ?: "none"}")
    appendLine("adapter_matched=${report.adapterMatched}")
    appendLine("adapter_control_enabled=${report.adapterControlEnabled}")
    appendLine("contribution_wanted=${report.contributionWanted}")
    appendLine("battery_charging_status=${report.batteryChargingStatus}")
    append("battery_plugged=${report.batteryPlugged}")
}

internal fun issueTitle(report: DeviceSupportReport): String =
    "[Device support] ${report.manufacturer} ${report.model}".trim()

internal fun issueBody(report: DeviceSupportReport): String = buildString {
    appendLine("Thanks for helping add support for your device. The details below were collected on-device.")
    appendLine()
    appendLine("### Device")
    appendLine()
    appendLine("```")
    appendLine(formatReport(report))
    appendLine("```")
    appendLine()
    append("### What is the manufacturer's battery-protection feature called on this device? (optional)")
}

internal fun issueUrl(report: DeviceSupportReport): String {
    val title = URLEncoder.encode(issueTitle(report), Charsets.UTF_8.name())
    val body = URLEncoder.encode(issueBody(report), Charsets.UTF_8.name())
    return "${DeviceSupportReporter.ISSUE_BASE_URL}?title=$title&body=$body"
}
