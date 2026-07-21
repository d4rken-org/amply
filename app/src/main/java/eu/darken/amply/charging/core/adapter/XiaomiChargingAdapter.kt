package eu.darken.amply.charging.core.adapter

import android.content.Context
import android.content.Intent
import android.provider.Settings
import eu.darken.amply.R
import eu.darken.amply.charging.core.ChargeObservation
import eu.darken.amply.charging.core.ChargePolicy
import eu.darken.amply.charging.core.DeviceInfo
import eu.darken.amply.charging.core.access.AccessBackend
import eu.darken.amply.charging.core.access.SettingMutation
import eu.darken.amply.charging.core.access.SettingNamespace
import eu.darken.amply.common.ca.toCaString
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Xiaomi HyperOS charging protection via `secure/security_pc_secure_protect_mode_key`
 * 0 = charge fully, 1 = "Intelligent charging" (heuristic
 * 80% hold decided by the OS — adaptive semantics, no hard cap exists). The key is absent in
 * factory state and the OEM UI treats absent as intelligent.
 *
 * The setting is a HyperOS ROM feature rather than a per-model one, so control is gated to the
 * HyperOS **major version** (2.x, from `ro.mi.os.version.code`) plus the Xiaomi manufacturer
 * (which also covers Redmi/POCO) and the system user (the secure namespace is per-user while
 * charging hardware is device-wide). HyperOS 1, pre-HyperOS MIUI, and a future HyperOS 3 fall
 * through to the diagnostics-only lab adapter until qualified.
 *
 * Assumption (deliberate): the feature is treated as present on any HyperOS 2
 * device. A HyperOS 2 device that genuinely lacks Battery protection also reports the key absent,
 * so Amply would show a verified Adaptive state and a control the OS ignores. This is a false
 * claim of control, not a battery hazard, and only affects the subset of HyperOS 2 devices
 * without the feature.
 */
@Singleton
class XiaomiChargingAdapter @Inject constructor() : ChargingAdapter {
    override val id = "xiaomi-hyperos2-v1"
    override val displayName = R.string.adapter_name_xiaomi_protection.toCaString()

    override val supportedPolicies = listOf(
        ChargePolicy.Adaptive,
        ChargePolicy.Unrestricted,
    )

    override val defaultProtectivePolicy = ChargePolicy.Adaptive
    override val verification = VerificationStrategy.SYNC_READBACK

    override fun probe(device: DeviceInfo): AdapterSupport {
        val matched = device.manufacturer.equals("Xiaomi", ignoreCase = true) &&
            device.hyperOsVersion == QUALIFIED_HYPEROS_VERSION
        return AdapterSupport(
            matched = matched,
            controlEnabled = matched && device.isSystemUser,
            detail = when {
                !matched -> R.string.adapter_detail_requires_xiaomi
                !device.isSystemUser -> R.string.adapter_detail_secondary_user
                else -> R.string.adapter_detail_xiaomi_ready
            },
            contributionWanted = false,
        )
    }

    override suspend fun read(backend: AccessBackend): ChargeObservation {
        val mode = backend.read(SettingNamespace.SECURE, KEY_MODE)
        if (!mode.readable) {
            return ChargeObservation.Unknown(
                mode.error ?: R.string.charging_reason_settings_unreadable.toCaString(),
            )
        }
        return when (mode.value) {
            // Factory state: the key materializes on first change; the OEM UI treats absent as
            // intelligent charging (verified on the qualified device).
            null, VALUE_INTELLIGENT -> ChargeObservation.Verified(ChargePolicy.Adaptive, backend.kind)
            VALUE_CHARGE_FULLY -> ChargeObservation.Verified(ChargePolicy.Unrestricted, backend.kind)
            else -> ChargeObservation.Unknown(
                R.string.charging_reason_value_unrecognized.toCaString(KEY_MODE, mode.value),
                unrecognizedValue = true,
            )
        }
    }

    override suspend fun apply(policy: ChargePolicy, backend: AccessBackend): Boolean {
        val value = when (policy) {
            ChargePolicy.Adaptive -> VALUE_INTELLIGENT
            ChargePolicy.Unrestricted -> VALUE_CHARGE_FULLY
            is ChargePolicy.FixedLimit, ChargePolicy.PauseAtFull -> return false
        }
        if (!backend.write(SettingMutation(SettingNamespace.SECURE, KEY_MODE, value))) return false
        // The key is synchronously readable; require read-back equality like the other
        // sync-readback adapters.
        val observed = read(backend)
        return observed is ChargeObservation.Verified && observed.policy == policy
    }

    override val observedSettingUris
        get() = listOf(Settings.Secure.getUriFor(KEY_MODE))

    override fun nativeSettingsIntent(context: Context): Intent {
        // No exported deep-link to the protection screen was found; the MIUI battery page
        // (one tap away from it) resolves via the generic power-usage action.
        val specific = Intent(Intent.ACTION_POWER_USAGE_SUMMARY).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return if (specific.resolveActivity(context.packageManager) != null) {
            specific
        } else {
            Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    companion object {
        const val KEY_MODE = "security_pc_secure_protect_mode_key"
        const val VALUE_CHARGE_FULLY = "0"
        const val VALUE_INTELLIGENT = "1"

        // HyperOS 2.x (ro.mi.os.version.code). The mapping was verified on HyperOS 2.0; a future
        // HyperOS 3 stays unqualified until checked, mirroring the One UI range gates.
        const val QUALIFIED_HYPEROS_VERSION = 2
    }
}
