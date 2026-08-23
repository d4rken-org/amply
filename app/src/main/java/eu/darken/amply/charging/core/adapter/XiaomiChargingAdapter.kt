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
 * 80% hold decided by the OS — adaptive semantics, no hard cap exists on HyperOS 2). The key is
 * absent in factory state and the OEM UI treats absent as intelligent.
 *
 * The setting is a HyperOS ROM feature rather than a per-model one, so control is gated to the
 * HyperOS **major version** (2.x, from `ro.mi.os.version.code`) plus the Xiaomi manufacturer
 * (which also covers Redmi/POCO) and the system user (the secure namespace is per-user while
 * charging hardware is device-wide). HyperOS 1 and pre-HyperOS MIUI fall through to the
 * diagnostics-only lab adapter. HyperOS 3 is handled by [XiaomiHyperOs3ChargingAdapter] on
 * qualified devices (same key, added value 2 = "Battery protection"); the unrecognized-value
 * decode below still refuses a `2` safely — the mode does not exist on HyperOS 2.
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

    /**
     * The only adapter whose protective default is conditional ([ChargePolicy.enforcementIsConditional]),
     * and unavoidably so: the HyperOS 2 key domain is `{0,1}`, so Adaptive is the sole protective mode
     * this ROM offers. HyperOS only engages it inside a learned overnight window — a 13T with Adaptive
     * configured and read back charged 59%→100% untouched (2026-08-16) — so the honesty burden lands on
     * presentation, which refuses to claim active protection for it. The HyperOS 3 adapter has an
     * unconditional mode available and defaults to `FixedLimit(80)` instead.
     */
    override val defaultProtectivePolicy = ChargePolicy.Adaptive
    override val verification = VerificationStrategy.SYNC_READBACK

    // NOT because Adaptive is `enforcementIsConditional` — Amply deliberately arms the gesture on a
    // conditional policy elsewhere (Pixel does today, and OnePlus/HyperOS 3 will), because adaptive
    // charging really does hold below full before the usual unplug, which is precisely when a user
    // would reach for the gesture. The reason is narrower and specific to HyperOS 2: Adaptive is the
    // ONLY protective mode this key domain offers, and its hold is unobserved on this generation (a
    // 13T with Intelligent charging configured and verified charged 59%→100% with no hold at all,
    // 2026-08-16). So the gesture would have nothing to lift on EVERY tick, not merely on some
    // configurations, and a permanent foreground notification for a feature that can never do
    // anything is worse than not offering it.
    override val reconnectGestureSupport = ReconnectSupport.NONE

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

    override fun nativeSettingsIntent(context: Context): Intent = xiaomiBatterySettingsIntent(context)

    companion object {
        const val KEY_MODE = "security_pc_secure_protect_mode_key"
        const val VALUE_CHARGE_FULLY = "0"
        const val VALUE_INTELLIGENT = "1"

        // HyperOS 2.x (ro.mi.os.version.code). The mapping was verified on HyperOS 2.0,
        // mirroring the One UI range gates. HyperOS 3 has its own codename-gated adapter.
        const val QUALIFIED_HYPEROS_VERSION = 2
    }
}

/**
 * Xiaomi HyperOS 3 charging protection — same key as HyperOS 2 with an added third mode:
 * 0 = charge fully, 1 = "Intelligent charging" (adaptive), 2 = "Battery protection" (hard cap
 * at 80% — issue #48 demonstrated both-direction hardware enforcement of external shell-UID
 * writes on `tanzanite`, applied synchronously, Settings UI following each write).
 *
 * Unlike HyperOS 2 this gate needs a **qualified-codename allowlist** on top of the major
 * version: mode 2 is NOT HyperOS-3-wide (a Poco F5 `marblein` on HyperOS 3.0.2 carries only
 * modes 0/1), `ro.mi.os.version.code` exposes no minor version, and no runtime probe for
 * mode-2 presence exists — the key is absent in factory state and reading it only returns the
 * current value. Unqualified HyperOS 3 devices fall through to the lab adapter.
 *
 * `dumpsys battery` exposes no hardware hold signal on HyperOS 3 (`status: 2`,
 * `Charging state: 0`, `Charging policy: 0` while demonstrably holding at the cap), so
 * verification is settings read-back only.
 */
@Singleton
class XiaomiHyperOs3ChargingAdapter @Inject constructor() : ChargingAdapter {
    override val id = "xiaomi-hyperos3-v1"
    override val displayName = R.string.adapter_name_xiaomi_protection.toCaString()

    override val supportedPolicies = listOf(
        ChargePolicy.FixedLimit(CAP_PERCENT),
        ChargePolicy.Adaptive,
        ChargePolicy.Unrestricted,
    )

    // Battery protection is the only Xiaomi mode with demonstrated hardware enforcement;
    // Intelligent charging's hold remains unobserved on both HyperOS generations.
    override val defaultProtectivePolicy = ChargePolicy.FixedLimit(CAP_PERCENT)
    override val verification = VerificationStrategy.SYNC_READBACK

    // Unlike HyperOS 2 above, mode 2 is a real hard cap with demonstrated hardware enforcement, so
    // there is something for the gesture to lift. No hold signal exists in `dumpsys battery`.
    override val reconnectGestureSupport = ReconnectSupport.ANY_LEVEL_ONLY

    override fun probe(device: DeviceInfo): AdapterSupport {
        val matched = device.manufacturer.equals("Xiaomi", ignoreCase = true) &&
            device.hyperOsVersion == QUALIFIED_HYPEROS_VERSION &&
            device.codename in QUALIFIED_CODENAMES
        return AdapterSupport(
            matched = matched,
            controlEnabled = matched && device.isSystemUser,
            detail = when {
                !matched -> R.string.adapter_detail_requires_xiaomi_hyperos3
                !device.isSystemUser -> R.string.adapter_detail_secondary_user
                // Distinct from the HyperOS 2 string: Battery protection has demonstrated hardware
                // enforcement (issue #48), so the stronger claim stays accurate here.
                else -> R.string.adapter_detail_xiaomi_hyperos3_ready
            },
            contributionWanted = false,
        )
    }

    override suspend fun read(backend: AccessBackend): ChargeObservation {
        val mode = backend.read(SettingNamespace.SECURE, XiaomiChargingAdapter.KEY_MODE)
        if (!mode.readable) {
            return ChargeObservation.Unknown(
                mode.error ?: R.string.charging_reason_settings_unreadable.toCaString(),
            )
        }
        return when (mode.value) {
            // Absent = intelligent, VERIFIED on tanzanite (issue #48, 2026-08-14): deleting the
            // key made the native battery settings fall back to Intelligent charging.
            null, XiaomiChargingAdapter.VALUE_INTELLIGENT ->
                ChargeObservation.Verified(ChargePolicy.Adaptive, backend.kind)

            XiaomiChargingAdapter.VALUE_CHARGE_FULLY ->
                ChargeObservation.Verified(ChargePolicy.Unrestricted, backend.kind)

            VALUE_BATTERY_PROTECTION ->
                ChargeObservation.Verified(ChargePolicy.FixedLimit(CAP_PERCENT), backend.kind)

            else -> ChargeObservation.Unknown(
                R.string.charging_reason_value_unrecognized.toCaString(XiaomiChargingAdapter.KEY_MODE, mode.value),
                unrecognizedValue = true,
            )
        }
    }

    override suspend fun apply(policy: ChargePolicy, backend: AccessBackend): Boolean {
        val value = when (policy) {
            // Value-match, not `is`: the OEM cap is hard-wired to 80, any other percent would
            // be misrepresented by mode 2.
            ChargePolicy.FixedLimit(CAP_PERCENT) -> VALUE_BATTERY_PROTECTION
            ChargePolicy.Adaptive -> XiaomiChargingAdapter.VALUE_INTELLIGENT
            ChargePolicy.Unrestricted -> XiaomiChargingAdapter.VALUE_CHARGE_FULLY
            is ChargePolicy.FixedLimit, ChargePolicy.PauseAtFull -> return false
        }
        if (!backend.write(SettingMutation(SettingNamespace.SECURE, XiaomiChargingAdapter.KEY_MODE, value))) {
            return false
        }
        val observed = read(backend)
        return observed is ChargeObservation.Verified && observed.policy == policy
    }

    override val observedSettingUris
        get() = listOf(Settings.Secure.getUriFor(XiaomiChargingAdapter.KEY_MODE))

    override fun nativeSettingsIntent(context: Context): Intent = xiaomiBatterySettingsIntent(context)

    companion object {
        const val VALUE_BATTERY_PROTECTION = "2"
        const val CAP_PERCENT = 80
        const val QUALIFIED_HYPEROS_VERSION = 3

        /**
         * Widen ONLY with a physically-qualified device plus a Verified-devices ledger row
         * (device-qualification skill). Version-only gating is impossible here: mode 2 is
         * model-/build-dependent within HyperOS 3 and cannot be probed at runtime.
         */
        val QUALIFIED_CODENAMES = setOf("tanzanite")
    }
}

// No exported deep-link to the protection screen was found; the MIUI battery page
// (one tap away from it) resolves via the generic power-usage action.
private fun xiaomiBatterySettingsIntent(context: Context): Intent {
    val specific = Intent(Intent.ACTION_POWER_USAGE_SUMMARY).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    return if (specific.resolveActivity(context.packageManager) != null) {
        specific
    } else {
        Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}
