package eu.darken.amply.charging.core.adapter

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.provider.Settings
import eu.darken.amply.BuildConfig
import eu.darken.amply.charging.core.access.AccessBackend
import eu.darken.amply.charging.core.access.SettingMutation
import eu.darken.amply.charging.core.access.SettingNamespace
import eu.darken.amply.charging.core.BackendKind
import eu.darken.amply.charging.core.ChargeObservation
import eu.darken.amply.charging.core.ChargePolicy
import eu.darken.amply.charging.core.DeviceInfo
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PixelChargingAdapter @Inject constructor() : ChargingAdapter {
    override val id = "google-pixel-lab-v1"
    override val displayName = "Google Pixel charging optimization"
    override val supportedPolicies = listOf(
        ChargePolicy.FixedLimit(80),
        ChargePolicy.Adaptive,
        ChargePolicy.Unrestricted,
    )
    override val observedSettingUris
        get() = listOf(
            Settings.Secure.getUriFor(KEY_MODE),
            Settings.Secure.getUriFor(KEY_ADAPTIVE),
        )

    override fun probe(device: DeviceInfo): AdapterSupport {
        val pixel = device.manufacturer.equals("Google", ignoreCase = true) &&
            device.model.startsWith("Pixel", ignoreCase = true)
        val supportedModel = isSupportedPixelModel(device.model)
        return AdapterSupport(
            matched = pixel,
            controlEnabled = pixel &&
                device.sdk >= MIN_SUPPORTED_SDK &&
                supportedModel &&
                device.isPhone &&
                device.hasChargingOptimization &&
                BuildConfig.ENABLE_PIXEL_LAB_ADAPTER,
            detail = when {
                !pixel -> "Requires a supported Google Pixel phone"
                device.sdk < MIN_SUPPORTED_SDK -> "Requires Android 15 or newer"
                !supportedModel -> "The 80% limit is officially supported on Pixel 6a and newer phones"
                !device.isPhone -> "Pixel tablets are not supported"
                !device.hasChargingOptimization -> "Google's charging-optimization controller is not available"
                !BuildConfig.ENABLE_PIXEL_LAB_ADAPTER -> "Adapter disabled in this build"
                else -> "Supported Pixel capability detected; charging hardware may take about 15 seconds to react"
            },
        )
    }

    override fun readHardware(context: Context): ChargeObservation? {
        val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            ?: return null
        val plugged = battery.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
        val chargingState = battery.getIntExtra(
            BatteryManager.EXTRA_CHARGING_STATUS,
            CHARGING_STATE_INVALID,
        )
        return decodeHardwareState(chargingState, plugged)
    }

    override fun decodeHardware(chargingState: Int, plugged: Boolean): ChargeObservation? =
        decodeHardwareState(chargingState, plugged)

    override suspend fun read(backend: AccessBackend): ChargeObservation {
        val mode = backend.read(SettingNamespace.SECURE, KEY_MODE)
        val adaptive = backend.read(SettingNamespace.SECURE, KEY_ADAPTIVE)
        if (!mode.readable || !adaptive.readable) {
            return ChargeObservation.Unknown(mode.error ?: adaptive.error ?: "Settings are not readable")
        }
        val policy = when {
            mode.value == "1" -> ChargePolicy.FixedLimit(80)
            mode.value == "0" && adaptive.value == "1" -> ChargePolicy.Adaptive
            mode.value == "0" -> ChargePolicy.Unrestricted
            else -> return ChargeObservation.Unknown(
                "Unrecognized Pixel values: $KEY_MODE=${mode.value}, $KEY_ADAPTIVE=${adaptive.value}",
            )
        }
        return ChargeObservation.Verified(policy, backend.kind)
    }

    override suspend fun apply(policy: ChargePolicy, backend: AccessBackend): Boolean {
        val changes = when (policy) {
            is ChargePolicy.FixedLimit -> {
                if (policy.percent != 80) return false
                listOf(
                    SettingMutation(SettingNamespace.SECURE, KEY_ADAPTIVE, "0"),
                    SettingMutation(SettingNamespace.SECURE, KEY_MODE, "1"),
                )
            }
            ChargePolicy.Unrestricted -> listOf(
                SettingMutation(SettingNamespace.SECURE, KEY_MODE, "0"),
                SettingMutation(SettingNamespace.SECURE, KEY_ADAPTIVE, "0"),
            )
            ChargePolicy.Adaptive -> listOf(
                SettingMutation(SettingNamespace.SECURE, KEY_MODE, "0"),
                SettingMutation(SettingNamespace.SECURE, KEY_ADAPTIVE, "1"),
            )
        }
        return changes.all { backend.write(it) }
    }

    override suspend fun reapply(policy: ChargePolicy, backend: AccessBackend): Boolean {
        // A same-value write from Amply's own package does not fire the settings content
        // observer, so Settings Intelligence's policy worker never runs (verified on Pixel 7a /
        // Android 16). Force a real change on the worker's trigger key before applying the
        // target configuration; both keys and all values stay within the write allowlist.
        val inverseMode = if (policy is ChargePolicy.FixedLimit) "0" else "1"
        if (!backend.write(SettingMutation(SettingNamespace.SECURE, KEY_MODE, inverseMode))) return false
        return apply(policy, backend)
    }

    override fun nativeSettingsIntent(context: Context): Intent {
        val specific = Intent().setComponent(
            ComponentName(
                "com.google.android.settings.intelligence",
                "com.google.android.settings.intelligence.modules.battery.impl.chargingoptimization.ChargingOptimizationActivity",
            ),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return if (specific.resolveActivity(context.packageManager) != null) {
            specific
        } else {
            Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    companion object {
        const val KEY_MODE = "charge_optimization_mode"
        const val KEY_ADAPTIVE = "adaptive_charging_enabled"
        private const val MIN_SUPPORTED_SDK = 35

        private const val CHARGING_STATE_INVALID = 0
        private const val CHARGING_STATE_NORMAL = 1
        private const val CHARGING_STATE_TOO_COLD = 2
        private const val CHARGING_STATE_TOO_HOT = 3
        private const val CHARGING_STATE_LONG_LIFE = 4
        private const val CHARGING_STATE_ADAPTIVE = 5

        internal fun isSupportedPixelModel(model: String): Boolean {
            if (model.equals("Pixel 6a", ignoreCase = true)) return true
            if (model.equals("Pixel Fold", ignoreCase = true)) return true
            val generation = PIXEL_GENERATION.matchEntire(model)?.groupValues?.get(1)?.toIntOrNull()
            return generation != null && generation >= 7
        }

        // The sticky battery broadcast retains its last powered value, so the charging-state
        // extra only reflects the current HAL policy while external power is present.
        internal fun decodeHardwareState(chargingState: Int, plugged: Boolean): ChargeObservation? {
            if (!plugged) return null
            return when (chargingState) {
                CHARGING_STATE_NORMAL -> ChargeObservation.Unknown(
                    "Charging hardware is normal; without Shizuku, Amply cannot distinguish unrestricted from inactive adaptive charging",
                )
                CHARGING_STATE_TOO_COLD -> ChargeObservation.Unknown(
                    "Charging is currently temperature-limited because the battery is too cold",
                )
                CHARGING_STATE_TOO_HOT -> ChargeObservation.Unknown(
                    "Charging is currently temperature-limited because the battery is too hot",
                )
                CHARGING_STATE_LONG_LIFE -> ChargeObservation.Verified(
                    ChargePolicy.FixedLimit(80),
                    BackendKind.BATTERY_HARDWARE,
                )
                CHARGING_STATE_ADAPTIVE -> ChargeObservation.Verified(
                    ChargePolicy.Adaptive,
                    BackendKind.BATTERY_HARDWARE,
                )
                else -> ChargeObservation.Unknown("Android did not report a recognized charging-hardware state")
            }
        }

        private val PIXEL_GENERATION = Regex("""Pixel (\d+)(?:\D.*)?""", RegexOption.IGNORE_CASE)
    }
}
