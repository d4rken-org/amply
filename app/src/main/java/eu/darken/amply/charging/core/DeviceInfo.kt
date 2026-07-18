package eu.darken.amply.charging.core

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build

data class DeviceInfo(
    val manufacturer: String,
    val model: String,
    val sdk: Int,
    val fingerprint: String,
    val isPhone: Boolean = true,
    val hasChargingOptimization: Boolean = true,
) {
    companion object {
        fun current(context: Context? = null) = DeviceInfo(
            manufacturer = Build.MANUFACTURER.orEmpty(),
            model = Build.MODEL.orEmpty(),
            sdk = Build.VERSION.SDK_INT,
            fingerprint = Build.FINGERPRINT.orEmpty(),
            isPhone = context?.packageManager?.hasSystemFeature(PackageManager.FEATURE_TELEPHONY)
                ?: true,
            hasChargingOptimization = context?.let {
                Intent(ACTION_CHARGING_OPTIMIZATION).resolveActivity(it.packageManager) != null
            } ?: false,
        )

        private const val ACTION_CHARGING_OPTIMIZATION =
            "com.google.android.settings.intelligence.action.CHARGING_OPTIMIZATION"
    }
}
