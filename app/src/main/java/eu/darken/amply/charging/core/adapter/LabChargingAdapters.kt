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

    /** See [AdapterSupport.guidedCaptureUseful]; false for a ROM whose control keys are already mapped. */
    protected open val guidedCaptureUseful: Boolean = true

    override fun probe(device: DeviceInfo) = AdapterSupport(
        matched = matches(device),
        controlEnabled = false,
        detail = R.string.adapter_detail_lab_diagnostics,
        contributionWanted = true,
        guidedCaptureUseful = guidedCaptureUseful,
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
}

@Singleton
class XiaomiLabAdapter @Inject constructor() : DisabledLabAdapter() {
    override val id = "xiaomi-lab"
    override val displayName = R.string.adapter_name_xiaomi.toCaString()
    override fun matches(device: DeviceInfo) = device.manufacturer.equals("Xiaomi", ignoreCase = true)
}

@Singleton
class LineageLabAdapter @Inject constructor() : DisabledLabAdapter() {
    override val id = "lineageos-lab"
    override val displayName = R.string.adapter_name_lineageos.toCaString()

    // Reached by a LineageOS build (or a derivative reporting the platform feature) that does NOT ship
    // the `lineagesettings` provider — there is nothing to write, so it stays diagnostics/contribution
    // only. Builds WITH the provider are handled by LineageChargingAdapter, which matches first and
    // holds control back on its own enforcement evidence. Those devices used to see a more specific
    // "not available on this build" note and now get the generic lab text.
    override fun matches(device: DeviceInfo) = device.isLineageOs

    // The three charging_control_* keys are already mapped and live in a provider the wizard does not capture,
    // so a guided run always diffs to nothing and cannot be delivered. The direct report instead carries an
    // observation of which provider LineageOS bound — useful triage context, but it decides nothing: no value
    // qualifies or disqualifies a device, only physical charging observation does.
    override val guidedCaptureUseful = false
}

@Singleton
class OnePlusLabAdapter @Inject constructor() : DisabledLabAdapter() {
    override val id = "oneplus-lab"
    override val displayName = R.string.adapter_name_oneplus.toCaString()

    // The whole Oplus family (OnePlus/Oppo/Realme) on an unqualified ColorOS version. Live control
    // (OnePlusChargingAdapter) is gated to the verified ROM version; everything else lands here for
    // the contribution flow.
    override fun matches(device: DeviceInfo) =
        device.manufacturer.equals("OnePlus", ignoreCase = true) ||
            device.manufacturer.equals("Oppo", ignoreCase = true) ||
            device.manufacturer.equals("realme", ignoreCase = true)
}
