package eu.darken.amply.charging.core.adapter

import android.content.Context
import android.content.Intent
import android.provider.Settings
import eu.darken.amply.R
import eu.darken.amply.charging.core.access.AccessBackend
import eu.darken.amply.charging.core.ChargeObservation
import eu.darken.amply.charging.core.ChargePolicy
import eu.darken.amply.charging.core.DeviceInfo
import eu.darken.amply.common.ca.toCaString
import javax.inject.Inject
import javax.inject.Singleton

abstract class DisabledLabAdapter : ChargingAdapter {
    override val supportedPolicies = emptyList<ChargePolicy>()

    abstract fun matches(device: DeviceInfo): Boolean

    override fun probe(device: DeviceInfo) = AdapterSupport(
        matched = matches(device),
        controlEnabled = false,
        detail = R.string.adapter_detail_lab_diagnostics,
        contributionWanted = true,
    )

    override suspend fun read(backend: AccessBackend) =
        ChargeObservation.Unsupported(R.string.charging_reason_lab_diagnostics_only.toCaString())

    override suspend fun apply(policy: ChargePolicy, backend: AccessBackend) = false

    override fun nativeSettingsIntent(context: Context): Intent {
        // Prefer the system battery-usage screen, which on most OEM skins is the entry point that
        // also holds the built-in charge-protection toggle; fall back to Battery Saver settings
        // where it isn't resolvable. Both are generic AOSP actions — no brittle OEM ComponentNames.
        val powerUsage = Intent(Intent.ACTION_POWER_USAGE_SUMMARY).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return if (powerUsage.resolveActivity(context.packageManager) != null) {
            powerUsage
        } else {
            Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }
}

@Singleton
class SamsungLabAdapter @Inject constructor() : DisabledLabAdapter() {
    override val id = "samsung-lab"
    override val displayName = R.string.adapter_name_samsung.toCaString()
    override fun matches(device: DeviceInfo) = device.manufacturer.equals("Samsung", ignoreCase = true)

    companion object {
        val CANDIDATE_KEYS = setOf("protect_battery", "battery_protection_threshold")
    }
}

@Singleton
class XiaomiLabAdapter @Inject constructor() : DisabledLabAdapter() {
    override val id = "xiaomi-lab"
    override val displayName = R.string.adapter_name_xiaomi.toCaString()
    override fun matches(device: DeviceInfo) = device.manufacturer.equals("Xiaomi", ignoreCase = true)

    companion object {
        val CANDIDATE_KEYS = setOf("security_pc_secure_protect_mode_key")
    }
}

@Singleton
class OnePlusLabAdapter @Inject constructor() : DisabledLabAdapter() {
    override val id = "oneplus-lab"
    override val displayName = R.string.adapter_name_oneplus.toCaString()
    override fun matches(device: DeviceInfo) =
        device.manufacturer.equals("OnePlus", ignoreCase = true) ||
            device.manufacturer.equals("Oppo", ignoreCase = true)

    companion object {
        val CANDIDATE_KEYS = setOf("regular_charge_protection_switch_state")
    }
}
