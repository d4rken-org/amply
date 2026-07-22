package eu.darken.amply.charging.core

import android.content.Context
import android.content.Intent
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
    val hasProtectBattery: Boolean = false,
    val hasLineageSettingsProvider: Boolean = false,
    val isSystemUser: Boolean = true,
) {
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
            hasProtectBattery = context?.let {
                runCatching {
                    Settings.Global.getString(it.contentResolver, KEY_PROTECT_BATTERY) != null
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

        private const val ACTION_CHARGING_OPTIMIZATION =
            "com.google.android.settings.intelligence.action.CHARGING_OPTIMIZATION"

        // Shared with the Samsung adapters; duplicated here to keep DeviceInfo dependency-free.
        const val KEY_PROTECT_BATTERY = "protect_battery"

        /** Authority of LineageOS's private settings provider (`content://lineagesettings/...`). */
        const val LINEAGE_SETTINGS_AUTHORITY = "lineagesettings"
    }
}
