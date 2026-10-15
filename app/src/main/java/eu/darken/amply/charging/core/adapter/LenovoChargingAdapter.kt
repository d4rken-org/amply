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
 * Lenovo "Battery protection mode" via the `system` key `battery_protected_charge` (qualified remotely on a
 * Lenovo Tab V7 `PB-6505M` / Android 9; see the qualification ledger in .claude/skills/device-qualification/):
 *
 * - `0` — off → [ChargePolicy.Unrestricted]
 * - `1` — hard cap at 60% → [ChargePolicy.FixedLimit] at 60
 *
 * Writes apply live mid-plug-session; there is no known hardware hold signal, so verification is read-back only.
 * An absent key is refused: what it means on this ROM is unverified.
 *
 * Control is gated to the Lenovo manufacturer, a qualified (codename, model) pair, SDK 28, and the system user.
 * No Lenovo ROM marker is known, so the pair and the SDK carry the whole gate. **Writes require Shizuku**: the key
 * is in the `system` namespace, which `WRITE_SECURE_SETTINGS` cannot write.
 */
@Singleton
class LenovoChargingAdapter @Inject constructor() : ChargingAdapter {
    override val id = "lenovo-batteryprotection-v1"
    override val displayName = R.string.adapter_name_lenovo_protection.toCaString()

    override val supportedPolicies = listOf(
        ChargePolicy.FixedLimit(LIMIT_PERCENT),
        ChargePolicy.Unrestricted,
    )

    override val defaultProtectivePolicy = ChargePolicy.FixedLimit(LIMIT_PERCENT)
    override val verification = VerificationStrategy.SYNC_READBACK

    // The cap is real and reads back synchronously; no hold signal is known.
    override val reconnectGestureSupport = ReconnectSupport.ANY_LEVEL_ONLY
    override val preferShizukuForWrites = true

    override fun probe(device: DeviceInfo): AdapterSupport {
        val matched = device.manufacturer.equals(MANUFACTURER, ignoreCase = true) &&
            device.sdk == QUALIFIED_SDK &&
            QUALIFIED_DEVICES.any {
                it.codename.equals(device.codename, ignoreCase = true) &&
                    it.model.equals(device.model, ignoreCase = true)
            }
        return AdapterSupport(
            matched = matched,
            controlEnabled = matched && device.isSystemUser,
            detail = when {
                !matched -> R.string.adapter_detail_requires_lenovo
                !device.isSystemUser -> R.string.adapter_detail_secondary_user
                else -> R.string.adapter_detail_lenovo_ready
            },
            contributionWanted = false,
        )
    }

    override suspend fun read(backend: AccessBackend): ChargeObservation {
        val read = backend.read(SettingNamespace.SYSTEM, KEY)
        if (!read.readable) {
            return ChargeObservation.Unknown(read.error ?: R.string.charging_reason_settings_unreadable.toCaString())
        }
        return when (read.value) {
            VALUE_OFF -> ChargeObservation.Verified(ChargePolicy.Unrestricted, backend.kind)
            VALUE_LIMIT -> ChargeObservation.Verified(ChargePolicy.FixedLimit(LIMIT_PERCENT), backend.kind)
            null -> ChargeObservation.Unknown(
                R.string.charging_reason_lenovo_never_set.toCaString(),
                unrecognizedValue = true,
            )
            else -> ChargeObservation.Unknown(
                R.string.charging_reason_value_unrecognized.toCaString(KEY, read.value),
                unrecognizedValue = true,
            )
        }
    }

    override suspend fun apply(policy: ChargePolicy, backend: AccessBackend): Boolean {
        val value = when (policy) {
            ChargePolicy.FixedLimit(LIMIT_PERCENT) -> VALUE_LIMIT
            ChargePolicy.Unrestricted -> VALUE_OFF
            else -> return false
        }
        if (!backend.write(SettingMutation(SettingNamespace.SYSTEM, KEY, value))) return false
        val observed = read(backend)
        return observed is ChargeObservation.Verified && observed.policy == policy
    }

    override val observedSettingUris
        get() = listOf(Settings.System.getUriFor(KEY))

    override fun nativeSettingsIntent(context: Context): Intent = OemChargingShortcuts.genericBatterySettings(context)

    private data class QualifiedDevice(val codename: String, val model: String)

    companion object {
        const val KEY = "battery_protected_charge"
        const val VALUE_OFF = "0"
        const val VALUE_LIMIT = "1"
        const val LIMIT_PERCENT = 60

        private const val MANUFACTURER = "Lenovo"

        // Android 9, the only build the qualified tablet was tested on.
        const val QUALIFIED_SDK = 28

        // (codename, model) pairs, as Build.DEVICE and Build.MODEL report them.
        private val QUALIFIED_DEVICES = setOf(
            QualifiedDevice(codename = "PB-6505M", model = "Lenovo PB-6505M"),
        )
    }
}
