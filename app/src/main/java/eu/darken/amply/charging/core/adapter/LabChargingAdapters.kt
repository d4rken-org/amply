package eu.darken.amply.charging.core.adapter

import android.content.Context
import android.content.Intent
import android.provider.Settings
import eu.darken.amply.charging.core.access.AccessBackend
import eu.darken.amply.charging.core.ChargeObservation
import eu.darken.amply.charging.core.ChargePolicy
import eu.darken.amply.charging.core.DeviceInfo
import javax.inject.Inject
import javax.inject.Singleton

abstract class DisabledLabAdapter : ChargingAdapter {
    override val supportedPolicies = emptyList<ChargePolicy>()

    abstract fun matches(device: DeviceInfo): Boolean

    override fun probe(device: DeviceInfo) = AdapterSupport(
        matched = matches(device),
        controlEnabled = false,
        detail = "Detected for diagnostics only; no unverified writes are exposed",
        contributionWanted = true,
    )

    override suspend fun read(backend: AccessBackend) =
        ChargeObservation.Unsupported("This OEM adapter is diagnostics-only")

    override suspend fun apply(policy: ChargePolicy, backend: AccessBackend) = false

    override fun nativeSettingsIntent(context: Context) =
        Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}

@Singleton
class SamsungLabAdapter @Inject constructor() : DisabledLabAdapter() {
    override val id = "samsung-lab"
    override val displayName = "Samsung (diagnostics)"
    override fun matches(device: DeviceInfo) = device.manufacturer.equals("Samsung", ignoreCase = true)

    companion object {
        val CANDIDATE_KEYS = setOf("protect_battery", "battery_protection_threshold")
    }
}

@Singleton
class OnePlusLabAdapter @Inject constructor() : DisabledLabAdapter() {
    override val id = "oneplus-lab"
    override val displayName = "OnePlus/Oppo (diagnostics)"
    override fun matches(device: DeviceInfo) =
        device.manufacturer.equals("OnePlus", ignoreCase = true) ||
            device.manufacturer.equals("Oppo", ignoreCase = true)

    companion object {
        val CANDIDATE_KEYS = setOf("regular_charge_protection_switch_state")
    }
}
