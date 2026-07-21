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
 * Samsung One UI battery protection via world-readable `global` settings. Value mapping was
 * verified on-device (see docs/SAMSUNG_SPIKE_RESULTS.md): writes apply synchronously and the
 * Settings UI reflects external changes immediately; only writes need WSS, reads are unprivileged.
 *
 * The keys are device-wide while Amply's session state is per Android user, so control is gated
 * to the system user.
 */
abstract class SamsungChargingAdapter : ChargingAdapter {

    abstract val supportedOneUiRange: IntRange

    override val verification = VerificationStrategy.SYNC_READBACK

    override fun probe(device: DeviceInfo): AdapterSupport {
        val oneUi = device.oneUiVersion
        val matched = device.manufacturer.equals("Samsung", ignoreCase = true) &&
            oneUi != null && oneUi in supportedOneUiRange
        return AdapterSupport(
            matched = matched,
            controlEnabled = matched && device.hasProtectBattery && device.isSystemUser,
            detail = when {
                !matched -> R.string.adapter_detail_requires_samsung
                !device.hasProtectBattery -> R.string.adapter_detail_samsung_no_key
                !device.isSystemUser -> R.string.adapter_detail_secondary_user
                else -> R.string.adapter_detail_samsung_ready
            },
            contributionWanted = matched && !device.hasProtectBattery,
        )
    }

    override suspend fun apply(policy: ChargePolicy, backend: AccessBackend): Boolean {
        val changes = mutationsFor(policy) ?: return false
        if (!changes.all { backend.write(it) }) return false
        // Two-key transitions are not atomic and command success does not guarantee the final
        // configuration; the keys are synchronously readable, so require read-back equality.
        val observed = read(backend)
        return observed is ChargeObservation.Verified && observed.policy == policy
    }

    /** Ordered writes for [policy], or null if the policy is unsupported by this generation. */
    protected abstract fun mutationsFor(policy: ChargePolicy): List<SettingMutation>?

    override fun nativeSettingsIntent(context: Context): Intent {
        val specific = Intent(ACTION_BATTERY_PROTECTION).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return if (specific.resolveActivity(context.packageManager) != null) {
            specific
        } else {
            Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    companion object {
        const val KEY_PROTECT_BATTERY = DeviceInfo.KEY_PROTECT_BATTERY
        const val KEY_THRESHOLD = "battery_protection_threshold"

        /** Samsung Device Care's battery-protection screen (verified exported on One UI 8). */
        const val ACTION_BATTERY_PROTECTION = "com.samsung.android.sm.ACTION_BATTERY_PROTECTION"

        const val VALUE_OFF = "0"
        const val VALUE_MAXIMUM = "1"
        const val VALUE_PAUSE_AT_FULL = "3"
    }
}

@Singleton
class SamsungModernChargingAdapter @Inject constructor() : SamsungChargingAdapter() {
    override val id = "samsung-oneui8-v1"
    override val displayName = R.string.adapter_name_samsung_protection.toCaString()

    // One UI 8.x only: the 3=Standard mapping and the threshold slider were verified on 8.0.
    // One UI 6/7 (Basic/Adaptive/Maximum era) and future 9.x are unverified and must fall
    // through to the diagnostics-only lab adapter.
    override val supportedOneUiRange = 80000..89999

    override val supportedPolicies = listOf(
        ChargePolicy.FixedLimit(80),
        ChargePolicy.FixedLimit(85),
        ChargePolicy.FixedLimit(90),
        ChargePolicy.FixedLimit(95),
        ChargePolicy.PauseAtFull,
        ChargePolicy.Unrestricted,
    )

    // Reaches 100% while keeping Samsung's own pause/resume protection as a safety net in case
    // Amply cannot restore (force-stop, crash before boot recovery).
    override val sessionOverridePolicy = ChargePolicy.PauseAtFull

    override val observedSettingUris
        get() = listOf(
            Settings.Global.getUriFor(KEY_PROTECT_BATTERY),
            Settings.Global.getUriFor(KEY_THRESHOLD),
        )

    override suspend fun read(backend: AccessBackend): ChargeObservation {
        val protect = backend.read(SettingNamespace.GLOBAL, KEY_PROTECT_BATTERY)
        if (!protect.readable) {
            return ChargeObservation.Unknown(
                protect.error ?: R.string.charging_reason_settings_unreadable.toCaString(),
            )
        }
        return when (protect.value) {
            VALUE_OFF -> ChargeObservation.Verified(ChargePolicy.Unrestricted, backend.kind)
            VALUE_PAUSE_AT_FULL -> ChargeObservation.Verified(ChargePolicy.PauseAtFull, backend.kind)
            VALUE_MAXIMUM -> {
                val threshold = backend.read(SettingNamespace.GLOBAL, KEY_THRESHOLD)
                if (!threshold.readable) {
                    return ChargeObservation.Unknown(
                        threshold.error ?: R.string.charging_reason_settings_unreadable.toCaString(),
                    )
                }
                // Absent means the slider was never moved (80); anything present must be a valid tick.
                val raw = threshold.value
                val percent = if (raw == null) THRESHOLD_DEFAULT else raw.toIntOrNull()
                if (percent != null && percent in THRESHOLD_DOMAIN) {
                    ChargeObservation.Verified(ChargePolicy.FixedLimit(percent), backend.kind)
                } else {
                    ChargeObservation.Unknown(
                        R.string.charging_reason_value_unrecognized.toCaString(
                            KEY_THRESHOLD, threshold.value.toString(),
                        ),
                        unrecognizedValue = true,
                    )
                }
            }
            else -> ChargeObservation.Unknown(
                R.string.charging_reason_value_unrecognized.toCaString(
                    KEY_PROTECT_BATTERY, protect.value.toString(),
                ),
                unrecognizedValue = true,
            )
        }
    }

    override fun mutationsFor(policy: ChargePolicy): List<SettingMutation>? = when (policy) {
        is ChargePolicy.FixedLimit -> if (policy.percent in THRESHOLD_DOMAIN) {
            // Threshold first so the mode write is the last observable change.
            listOf(
                SettingMutation(SettingNamespace.GLOBAL, KEY_THRESHOLD, policy.percent.toString()),
                SettingMutation(SettingNamespace.GLOBAL, KEY_PROTECT_BATTERY, VALUE_MAXIMUM),
            )
        } else {
            null
        }
        ChargePolicy.PauseAtFull -> listOf(
            SettingMutation(SettingNamespace.GLOBAL, KEY_PROTECT_BATTERY, VALUE_PAUSE_AT_FULL),
        )
        ChargePolicy.Unrestricted -> listOf(
            SettingMutation(SettingNamespace.GLOBAL, KEY_PROTECT_BATTERY, VALUE_OFF),
        )
        ChargePolicy.Adaptive -> null
    }

    companion object {
        const val THRESHOLD_DEFAULT = 80
        val THRESHOLD_DOMAIN = setOf(80, 85, 90, 95)
    }
}

@Singleton
class SamsungLegacyChargingAdapter @Inject constructor() : SamsungChargingAdapter() {
    override val id = "samsung-legacy-v1"
    override val displayName = R.string.adapter_name_samsung_protection.toCaString()

    // One UI 4.x/5.x: plain protect-battery toggle with a fixed 85% cap (verified on 4.1).
    // One UI 3.x and older are unverified; 6.x+ belongs to other generations.
    override val supportedOneUiRange = 40000..59999

    override val supportedPolicies = listOf(
        ChargePolicy.FixedLimit(LEGACY_LIMIT),
        ChargePolicy.Unrestricted,
    )

    override val defaultProtectivePolicy = ChargePolicy.FixedLimit(LEGACY_LIMIT)

    override val observedSettingUris
        get() = listOf(Settings.Global.getUriFor(KEY_PROTECT_BATTERY))

    override suspend fun read(backend: AccessBackend): ChargeObservation {
        val protect = backend.read(SettingNamespace.GLOBAL, KEY_PROTECT_BATTERY)
        if (!protect.readable) {
            return ChargeObservation.Unknown(
                protect.error ?: R.string.charging_reason_settings_unreadable.toCaString(),
            )
        }
        return when (protect.value) {
            VALUE_OFF -> ChargeObservation.Verified(ChargePolicy.Unrestricted, backend.kind)
            VALUE_MAXIMUM -> ChargeObservation.Verified(ChargePolicy.FixedLimit(LEGACY_LIMIT), backend.kind)
            else -> ChargeObservation.Unknown(
                R.string.charging_reason_value_unrecognized.toCaString(
                    KEY_PROTECT_BATTERY, protect.value.toString(),
                ),
                unrecognizedValue = true,
            )
        }
    }

    override fun mutationsFor(policy: ChargePolicy): List<SettingMutation>? = when (policy) {
        ChargePolicy.FixedLimit(LEGACY_LIMIT) -> listOf(
            SettingMutation(SettingNamespace.GLOBAL, KEY_PROTECT_BATTERY, VALUE_MAXIMUM),
        )
        ChargePolicy.Unrestricted -> listOf(
            SettingMutation(SettingNamespace.GLOBAL, KEY_PROTECT_BATTERY, VALUE_OFF),
        )
        else -> null
    }

    companion object {
        const val LEGACY_LIMIT = 85
    }
}
