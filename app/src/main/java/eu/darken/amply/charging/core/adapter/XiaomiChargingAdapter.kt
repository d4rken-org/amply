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
 * (see docs/XIAOMI_SPIKE_RESULTS.md): 0 = charge fully, 1 = "Intelligent charging" (heuristic
 * 80% hold decided by the OS — adaptive semantics, no hard cap exists). The key is absent in
 * factory state and the OEM UI treats absent as intelligent.
 *
 * v1 is gated to the exact qualified device (Xiaomi 13T on HyperOS 2.0 / version code 816) —
 * `ro.miui.ui.version.code` identifies a software family, not a build, so the model is pinned
 * until more devices are qualified. The secure namespace is per-user while charging hardware is
 * device-wide, so control requires the system user.
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
            device.model.equals(QUALIFIED_MODEL, ignoreCase = true) &&
            device.miuiVersionCode == QUALIFIED_VERSION_CODE
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

        const val QUALIFIED_MODEL = "2306EPN60G"
        const val QUALIFIED_VERSION_CODE = 816
    }
}
