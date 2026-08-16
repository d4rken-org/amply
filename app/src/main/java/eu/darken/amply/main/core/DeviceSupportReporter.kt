package eu.darken.amply.main.core

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.provider.Settings
import dagger.hilt.android.qualifiers.ApplicationContext
import eu.darken.amply.BuildConfig
import eu.darken.amply.charging.core.DeviceInfo
import eu.darken.amply.charging.core.SettingProbe
import eu.darken.amply.charging.core.probeSetting
import eu.darken.amply.charging.core.access.LineageHealthSummary
import eu.darken.amply.charging.core.access.SettingsSnapshotSource
import eu.darken.amply.charging.core.adapter.AdapterRegistry
import eu.darken.amply.charging.core.adapter.OnePlusChargingAdapter
import eu.darken.amply.common.AmplyLinks
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Presence of the two mutually-exclusive Oplus (OnePlus/Oppo/Realme) charge-protection keys.
 *
 * Diagnostics only — live Oplus control is gated on the ColorOS 15 ROM version and never consults these. They are
 * collected because that ROM property is otherwise the *sole* Oplus signal in a report, and it reads "none" on every
 * pre-rebrand build, so a report from an unqualified Oppo/OnePlus/Realme device carries nothing at all about the
 * family it just matched. Observed on an Oppo F11 Pro (CPH1969, ColorOS 11), whose report could not distinguish
 * "this ROM has no charge protection" from "we never looked".
 *
 * Deliberately not part of [eu.darken.amply.charging.core.DeviceInfo]: that snapshot is rebuilt on every dashboard
 * refresh from the main thread, and these are two synchronous provider calls that gate nothing.
 */
data class OplusKeyProbes(
    val regular: SettingProbe,
    val smart: SettingProbe,
) {
    companion object {
        val UNPROBED = OplusKeyProbes(SettingProbe.ABSENT, SettingProbe.ABSENT)

        /** Call from an IO context. Reads presence only — never a value. */
        fun read(resolver: ContentResolver) = OplusKeyProbes(
            regular = probeSetting {
                Settings.System.getString(resolver, OnePlusChargingAdapter.KEY_REGULAR)
            },
            smart = probeSetting {
                Settings.System.getString(resolver, OnePlusChargingAdapter.KEY_SMART)
            },
        )
    }
}

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
    val oplusRomVersion: Int?,
    val lineageOsVersion: String?,
    val isLineageOs: Boolean,
    val isGrapheneOs: Boolean,
    /**
     * LineageOS charge-control probe: the bound provider plus the configured mode, which together bound what can
     * be said about HAL limit support. Null when unknown (not LineageOS, or no Shizuku) — never read as a negative.
     */
    val lineageHealth: LineageHealthSummary?,
    /**
     * Unprivileged key-presence probes. Tri-state on purpose: a refused read is not evidence of absence, and
     * these reports are frequently the only evidence available for a device nobody owns.
     */
    val protectBatteryProbe: SettingProbe,
    val batteryChargeLimitProbe: SettingProbe,
    val oplusKeys: OplusKeyProbes,
    /** Provider resolution, not a settings read — it cannot be refused, so it stays a Boolean. */
    val hasLineageSettingsProvider: Boolean,
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
    private val snapshotSource: SettingsSnapshotSource,
) {
    suspend fun collect(): DeviceSupportReport = withContext(Dispatchers.IO) {
        val device = DeviceInfo.current(context)
        val selection = registry.select(device)
        // Only meaningful on LineageOS, and only reachable with Shizuku (dumpsys needs the shell UID). Without it
        // the field stays null and the report is exactly as informative as before — never blocks a contribution.
        val lineageHealth = if (device.isLineageOs) snapshotSource.lineageHealth() else null
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
            oplusRomVersion = device.oplusRomVersion,
            lineageOsVersion = device.lineageOsVersion,
            isLineageOs = device.isLineageOs,
            isGrapheneOs = device.isGrapheneOs,
            lineageHealth = lineageHealth,
            protectBatteryProbe = device.protectBatteryProbe,
            batteryChargeLimitProbe = device.batteryChargeLimitProbe,
            oplusKeys = OplusKeyProbes.read(context.contentResolver),
            hasLineageSettingsProvider = device.hasLineageSettingsProvider,
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
    appendLine("report_schema=10")
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
    appendLine("oplus_rom_version=${report.oplusRomVersion ?: "none"}")
    // is_lineageos is the reliable one: lineageos_version is SELinux-denied to apps on real builds
    // and reads "none" even on LineageOS (see LineageOsDetector).
    appendLine("is_lineageos=${report.isLineageOs}")
    appendLine("lineageos_version=${report.lineageOsVersion ?: "none"}")
    // Identity via core app.grapheneos.* packages — GrapheneOS exposes no property/feature marker
    // and keeps a stock-shaped fingerprint (see DeviceInfo.isGrapheneOs).
    appendLine("is_grapheneos=${report.isGrapheneOs}")
    // Observation, NOT a verdict. Provider selection branches on the configured mode before capability, so a
    // device in a time-based mode reports Deadline having never consulted either limit-capable provider. And
    // there is no negative case: Toggle also accepts MODE_LIMIT and enforces the cap itself. NOT_OBSERVED is
    // resolved by re-running with a limit set; no value here rules a device out, and none proves enforcement.
    appendLine("lineage_cc_provider=${report.lineageHealth?.provider?.name ?: "unknown"}")
    appendLine("lineage_cc_mode=${report.lineageHealth?.mode ?: "unknown"}")
    appendLine("lineage_cc_limit_mechanism=${report.lineageHealth?.limitMechanism?.name ?: "UNKNOWN"}")
    // present|absent|read_denied. "read_denied" means the platform refused the read, so the key may well exist —
    // never read it as evidence the OEM lacks the setting. "absent" means nothing came back, which is usually but
    // not provably a real negative (see SettingProbe).
    appendLine("probe_protect_battery=${report.protectBatteryProbe.reportValue}")
    appendLine("probe_battery_charge_limit=${report.batteryChargeLimitProbe.reportValue}")
    // The Oplus pair. Live control is gated on the ColorOS 15 ROM version, so these decide nothing; they exist so
    // a report from an unqualified Oppo/OnePlus/Realme build says something about the family at all.
    appendLine("probe_regular_charge_protection=${report.oplusKeys.regular.reportValue}")
    appendLine("probe_smart_charge_protection=${report.oplusKeys.smart.reportValue}")
    appendLine("has_lineage_settings_provider=${report.hasLineageSettingsProvider}")
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
