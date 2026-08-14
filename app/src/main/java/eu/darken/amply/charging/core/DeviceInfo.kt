package eu.darken.amply.charging.core

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.UserManager
import android.provider.Settings

data class DeviceInfo(
    val manufacturer: String,
    val model: String,
    val sdk: Int,
    val fingerprint: String,
    val codename: String = "",
    val isPhone: Boolean = true,
    val hasChargingOptimization: Boolean = true,
    val oneUiVersion: Int? = null,
    val hyperOsVersion: Int? = null,
    val oplusRomVersion: Int? = null,
    val lineageOsVersion: String? = null,
    val hasLineageFeature: Boolean = false,
    val hasGrapheneOsPackages: Boolean = false,
    val hasProtectBattery: Boolean = false,
    val hasBatteryChargeLimit: Boolean = false,
    val hasLineageSettingsProvider: Boolean = false,
    val isSystemUser: Boolean = true,
) {
    /**
     * Whether this is a LineageOS build. [lineageOsVersion] alone must never gate this: every
     * `ro.lineage.*` property lives in the SELinux context `custom_version_prop`, which
     * `untrusted_app` cannot read, so on a real LineageOS device the property reads back empty and
     * the version is null (see [LineageOsDetector]). [hasLineageFeature] is the app-readable
     * identity signal; the version is kept as an OR so derivatives that expose the property but not
     * the feature still match, and so unit tests can construct either shape.
     */
    val isLineageOs: Boolean get() = hasLineageFeature || lineageOsVersion != null

    /**
     * Whether this is a GrapheneOS build. There is no system property, feature, or fingerprint
     * marker at all — `getprop` on a real device contains no "graphene" and the fingerprint is
     * stock-Google-shaped (verified on Pixel 9 Pro XL, build 2026080501) — so identity comes from
     * the OS's own core `app.grapheneos.*` system packages, resolved via PackageManager with
     * matching `<queries>` entries. Deliberately NOT OR-ed with [hasBatteryChargeLimit]: this
     * adapter is registered ahead of the live Pixel adapter, and a future stock Pixel shipping a
     * same-named key must not be swallowed as "GrapheneOS".
     */
    val isGrapheneOs: Boolean get() = hasGrapheneOsPackages

    companion object {
        fun current(context: Context? = null) = DeviceInfo(
            manufacturer = Build.MANUFACTURER.orEmpty(),
            model = Build.MODEL.orEmpty(),
            sdk = Build.VERSION.SDK_INT,
            fingerprint = Build.FINGERPRINT.orEmpty(),
            codename = Build.DEVICE.orEmpty(),
            isPhone = context?.packageManager?.hasSystemFeature(PackageManager.FEATURE_TELEPHONY)
                ?: true,
            hasChargingOptimization = context?.let {
                Intent(ACTION_CHARGING_OPTIMIZATION).resolveActivity(it.packageManager) != null
            } ?: false,
            oneUiVersion = OneUiVersionDetector.detect(),
            hyperOsVersion = HyperOsVersionDetector.detect(),
            oplusRomVersion = OplusRomVersionDetector.detect(),
            lineageOsVersion = LineageOsDetector.detect(),
            // The app-readable LineageOS identity signal. System features are not subject to package
            // visibility filtering, so this needs no <queries> entry and no permission — unlike the
            // ro.lineage.* properties, which SELinux denies to untrusted_app. Fail closed.
            hasLineageFeature = context?.let {
                runCatching { it.packageManager.hasSystemFeature(FEATURE_LINEAGE_OS) }
                    .getOrDefault(false)
            } ?: false,
            // GrapheneOS identity via its core system packages (see isGrapheneOs). getApplicationInfo
            // needs the <queries> package entries but no permission; any one package suffices, so a
            // renamed or slimmed component doesn't break detection. The FLAG_SYSTEM check keeps a
            // user-installed APK squatting on the name from spoofing ROM identity. Fail closed.
            hasGrapheneOsPackages = context?.let { ctx ->
                GRAPHENEOS_PACKAGES.any { pkg ->
                    runCatching {
                        val flags = ctx.packageManager.getApplicationInfo(pkg, 0).flags
                        flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
                    }.getOrDefault(false)
                }
            } ?: false,
            hasProtectBattery = context?.let {
                runCatching {
                    Settings.Global.getString(it.contentResolver, KEY_PROTECT_BATTERY) != null
                }.getOrDefault(false)
            } ?: false,
            // GrapheneOS's charge-limit key. NOT a gate: GrapheneOS marks the key @Protected, so
            // this unprivileged read throws SecurityException (→ false) on real GrapheneOS whether
            // the key exists or not — verified via the issue-#49 beta report. Kept as a diagnostic
            // signal only: true would indicate a ROM exposing a same-named key WITHOUT the
            // GrapheneOS restriction, which is worth seeing in a device-support report.
            hasBatteryChargeLimit = context?.let {
                runCatching {
                    Settings.Global.getString(it.contentResolver, KEY_BATTERY_CHARGE_LIMIT) != null
                }.getOrDefault(false)
            } ?: false,
            // Whether LineageOS's private settings provider is installed (the charge-control settings
            // surface). Fail closed; requires the <queries> provider entry so package visibility on
            // API 30+ doesn't false-negative resolution. Provider presence is not HAL-enforcement proof.
            hasLineageSettingsProvider = context?.let {
                runCatching {
                    it.packageManager.resolveContentProvider(LINEAGE_SETTINGS_AUTHORITY, 0) != null
                }.getOrDefault(false)
            } ?: false,
            // Fail closed: this gates device-wide Samsung writes, so an unresolvable UserManager
            // must read as "not the system user". Only the context-less placeholder stays true.
            isSystemUser = context?.let {
                runCatching { it.getSystemService(UserManager::class.java)?.isSystemUser }
                    .getOrNull() ?: false
            } ?: true,
        )

        /** Internal so tests can register a resolver for it instead of duplicating the action string. */
        internal const val ACTION_CHARGING_OPTIMIZATION =
            "com.google.android.settings.intelligence.action.CHARGING_OPTIMIZATION"

        // Shared with the Samsung adapters; duplicated here to keep DeviceInfo dependency-free.
        const val KEY_PROTECT_BATTERY = "protect_battery"

        // Shared with the GrapheneOS adapter; duplicated here to keep DeviceInfo dependency-free.
        const val KEY_BATTERY_CHARGE_LIMIT = "battery_charge_limit"

        /**
         * Core GrapheneOS system packages used as the identity signal — chosen for being integral
         * to the OS (setup wizard, the built-in "Info" app) rather than user-removable extras.
         * Each needs a `<queries>` package entry in the manifest.
         */
        val GRAPHENEOS_PACKAGES = listOf(
            "app.grapheneos.setupwizard",
            "app.grapheneos.info",
        )

        /** Authority of LineageOS's private settings provider (`content://lineagesettings/...`). */
        const val LINEAGE_SETTINGS_AUTHORITY = "lineagesettings"

        /** LineageOS's core platform system feature, declared by `lineageos.platform`. */
        const val FEATURE_LINEAGE_OS = "org.lineageos.android"
    }
}
