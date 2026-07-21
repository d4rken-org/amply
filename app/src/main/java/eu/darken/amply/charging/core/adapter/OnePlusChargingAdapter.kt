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
import eu.darken.amply.charging.core.access.SettingRead
import eu.darken.amply.common.ca.toCaString
import javax.inject.Inject
import javax.inject.Singleton

/**
 * ColorOS / OxygenOS (Oplus) charging protection via two mutually-exclusive `system` keys
 * (verified on a OnePlus Nord CE4 Lite / ColorOS 15; see the qualification ledger in
 * .claude/rules/privileged-access.md):
 *
 * - `regular_charge_protection_switch_state` — "Charging limit": a hard cap that keeps the
 *   battery at 80% while charging → [ChargePolicy.FixedLimit] at 80.
 * - `smart_charge_protection_switch_state` — "Smart charging": adaptive, defers to 100% until
 *   shortly before you need it → [ChargePolicy.Adaptive].
 *
 * The OEM enforces mutual exclusion (enabling one disables the other), so the two toggles form a
 * clean three-way selection: Charging limit, Smart charging, or neither (Unrestricted). The
 * paired `_status` mirrors follow the switch automatically, so only the `_switch_state` keys are
 * written. The threshold is a fixed 80% with no user control.
 *
 * The setting is a ColorOS ROM feature shared across the Oplus family (OnePlus/Oppo/Realme), so
 * control is gated to the ROM major version (`ro.build.version.oplusrom`, which is Oplus-exclusive
 * and thus also the family signal) plus the system user. **Writes require Shizuku**: these keys
 * are in the `system` namespace, which `WRITE_SECURE_SETTINGS` cannot write.
 */
@Singleton
class OnePlusChargingAdapter @Inject constructor() : ChargingAdapter {
    override val id = "oplus-coloros15-v1"
    override val displayName = R.string.adapter_name_oplus_protection.toCaString()

    override val supportedPolicies = listOf(
        ChargePolicy.FixedLimit(LIMIT_PERCENT),
        ChargePolicy.Adaptive,
        ChargePolicy.Unrestricted,
    )

    override val defaultProtectivePolicy = ChargePolicy.FixedLimit(LIMIT_PERCENT)
    override val verification = VerificationStrategy.SYNC_READBACK
    override val preferShizukuForWrites = true

    override fun probe(device: DeviceInfo): AdapterSupport {
        val matched = device.oplusRomVersion == QUALIFIED_OPLUS_VERSION
        return AdapterSupport(
            matched = matched,
            controlEnabled = matched && device.isSystemUser,
            detail = when {
                !matched -> R.string.adapter_detail_requires_oplus
                !device.isSystemUser -> R.string.adapter_detail_secondary_user
                else -> R.string.adapter_detail_oplus_ready
            },
            contributionWanted = false,
        )
    }

    override suspend fun read(backend: AccessBackend): ChargeObservation {
        val regular = backend.read(SettingNamespace.SYSTEM, KEY_REGULAR)
        val smart = backend.read(SettingNamespace.SYSTEM, KEY_SMART)
        if (!regular.readable || !smart.readable) {
            return ChargeObservation.Unknown(
                regular.error ?: smart.error ?: R.string.charging_reason_settings_unreadable.toCaString(),
            )
        }
        val regularOn = regular.enabled() ?: return unrecognized(KEY_REGULAR, regular.value)
        val smartOn = smart.enabled() ?: return unrecognized(KEY_SMART, smart.value)
        return when {
            // Mutual exclusion is OEM-enforced; both on means an external writer left an
            // inconsistent state Amply should not overwrite.
            regularOn && smartOn -> ChargeObservation.Unknown(
                R.string.charging_reason_values_unrecognized.toCaString(
                    KEY_REGULAR, regular.value.toString(), KEY_SMART, smart.value.toString(),
                ),
                unrecognizedValue = true,
            )
            regularOn -> ChargeObservation.Verified(ChargePolicy.FixedLimit(LIMIT_PERCENT), backend.kind)
            smartOn -> ChargeObservation.Verified(ChargePolicy.Adaptive, backend.kind)
            else -> ChargeObservation.Verified(ChargePolicy.Unrestricted, backend.kind)
        }
    }

    override suspend fun apply(policy: ChargePolicy, backend: AccessBackend): Boolean {
        // Write the "off" key first to avoid a transient both-on state during the transition.
        val changes = when (policy) {
            ChargePolicy.FixedLimit(LIMIT_PERCENT) -> listOf(KEY_SMART to OFF, KEY_REGULAR to ON)
            ChargePolicy.Adaptive -> listOf(KEY_REGULAR to OFF, KEY_SMART to ON)
            ChargePolicy.Unrestricted -> listOf(KEY_REGULAR to OFF, KEY_SMART to OFF)
            else -> return false
        }
        val mutations = changes.map { (key, value) -> SettingMutation(SettingNamespace.SYSTEM, key, value) }
        if (!mutations.all { backend.write(it) }) return false
        val observed = read(backend)
        return observed is ChargeObservation.Verified && observed.policy == policy
    }

    override val observedSettingUris
        get() = listOf(
            Settings.System.getUriFor(KEY_REGULAR),
            Settings.System.getUriFor(KEY_SMART),
        )

    override fun nativeSettingsIntent(context: Context): Intent {
        val specific = Intent(Intent.ACTION_POWER_USAGE_SUMMARY).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return if (specific.resolveActivity(context.packageManager) != null) {
            specific
        } else {
            Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    /** null value or "0" = off, "1" = on, anything else = unrecognized (returns null). */
    private fun SettingRead.enabled(): Boolean? = when (value) {
        null, OFF -> false
        ON -> true
        else -> null
    }

    private fun unrecognized(key: String, value: String?) = ChargeObservation.Unknown(
        R.string.charging_reason_value_unrecognized.toCaString(key, value.toString()),
        unrecognizedValue = true,
    )

    companion object {
        const val KEY_REGULAR = "regular_charge_protection_switch_state"
        const val KEY_SMART = "smart_charge_protection_switch_state"
        const val ON = "1"
        const val OFF = "0"
        const val LIMIT_PERCENT = 80

        // ColorOS/OxygenOS 15 (ro.build.version.oplusrom). Verified on OnePlus; assumed across the
        // Oplus family. A future v16 stays unqualified until checked, mirroring the other gates.
        const val QUALIFIED_OPLUS_VERSION = 15
    }
}
