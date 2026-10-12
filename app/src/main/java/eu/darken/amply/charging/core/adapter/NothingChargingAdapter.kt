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
 * Nothing OS "Battery health" charge limit via the `system` key `nt_battery_health` (qualified remotely on a
 * Nothing Phone (3a) Pro, issue #111; see the qualification ledger in .claude/skills/device-qualification/):
 *
 * - `0` — off → [ChargePolicy.Unrestricted]
 * - `2` — hard cap at 70% → [ChargePolicy.FixedLimit] at 70
 * - `3` — hard cap at 80% → [ChargePolicy.FixedLimit] at 80
 *
 * Writes apply live mid-plug-session; there is no hardware hold signal, so verification is read-back only.
 * `1` ("smart charging") and `4` (90%) have no hardware evidence, and an absent key is refused too, so all three
 * read as unrecognized rather than being mapped to a policy.
 *
 * Control is gated to the Nothing manufacturer, the `com.nothing.feature` system feature, a qualified
 * (codename, model) pair, SDK 36 as the Nothing OS 4.x proxy (no app-readable Nothing OS version signal exists),
 * and the system user. The feature marker is what separates Nothing OS from an aftermarket ROM for the same
 * hardware (a PixelOS port for the `asteroids` tree), which reports the same manufacturer, model, codename and
 * SDK without the Nothing OS charge feature. **Writes require Shizuku**: the key is in the `system` namespace,
 * which `WRITE_SECURE_SETTINGS` cannot write.
 */
@Singleton
class NothingChargingAdapter @Inject constructor() : ChargingAdapter {
    override val id = "nothing-os4-v1"
    override val displayName = R.string.adapter_name_nothing_protection.toCaString()

    override val supportedPolicies = listOf(
        ChargePolicy.FixedLimit(80),
        ChargePolicy.FixedLimit(70),
        ChargePolicy.Unrestricted,
    )

    override val defaultProtectivePolicy = ChargePolicy.FixedLimit(80)
    override val verification = VerificationStrategy.SYNC_READBACK

    // The caps are real and read back synchronously; Nothing OS publishes no hold signal.
    override val reconnectGestureSupport = ReconnectSupport.ANY_LEVEL_ONLY
    override val preferShizukuForWrites = true

    override fun probe(device: DeviceInfo): AdapterSupport {
        val matched = device.manufacturer.equals(MANUFACTURER, ignoreCase = true) &&
            device.hasNothingFeature &&
            device.sdk == QUALIFIED_SDK &&
            QUALIFIED_DEVICES.any {
                it.codename.equals(device.codename, ignoreCase = true) &&
                    it.model.equals(device.model, ignoreCase = true)
            }
        return AdapterSupport(
            matched = matched,
            controlEnabled = matched && device.isSystemUser,
            detail = when {
                !matched -> R.string.adapter_detail_requires_nothing
                !device.isSystemUser -> R.string.adapter_detail_secondary_user
                else -> R.string.adapter_detail_nothing_ready
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
            VALUE_LIMIT_70 -> ChargeObservation.Verified(ChargePolicy.FixedLimit(70), backend.kind)
            VALUE_LIMIT_80 -> ChargeObservation.Verified(ChargePolicy.FixedLimit(80), backend.kind)
            // Absent: what a missing key means on Nothing OS is unverified. Reading it as off would let a charge
            // rule record Unrestricted as its baseline and later write "0" over a possibly implicit factory mode.
            null -> ChargeObservation.Unknown(
                R.string.charging_reason_nothing_never_set.toCaString(),
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
            ChargePolicy.FixedLimit(80) -> VALUE_LIMIT_80
            ChargePolicy.FixedLimit(70) -> VALUE_LIMIT_70
            ChargePolicy.Unrestricted -> VALUE_OFF
            else -> return false
        }
        if (!backend.write(SettingMutation(SettingNamespace.SYSTEM, KEY, value))) return false
        val observed = read(backend)
        return observed is ChargeObservation.Verified && observed.policy == policy
    }

    override val observedSettingUris
        get() = listOf(Settings.System.getUriFor(KEY))

    override fun nativeSettingsIntent(context: Context): Intent {
        val specific = Intent(Intent.ACTION_POWER_USAGE_SUMMARY).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return if (specific.resolveActivity(context.packageManager) != null) {
            specific
        } else {
            Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    private data class QualifiedDevice(val codename: String, val model: String)

    companion object {
        const val KEY = "nt_battery_health"
        const val VALUE_OFF = "0"
        const val VALUE_LIMIT_70 = "2"
        const val VALUE_LIMIT_80 = "3"

        private const val MANUFACTURER = "Nothing"

        // Android 16, standing in for Nothing OS 4.x.
        const val QUALIFIED_SDK = 36

        // (codename, model) pairs: qualifying the Phone (3a) ("Asteroids", "A059") later must not cross-admit models.
        private val QUALIFIED_DEVICES = setOf(
            QualifiedDevice(codename = "Asteroids", model = "A059P"),
        )
    }
}
