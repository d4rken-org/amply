package eu.darken.amply.charging.core.adapter

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.provider.Settings
import eu.darken.amply.R
import eu.darken.amply.charging.core.BackendKind
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
 * GrapheneOS charging control via its own single key (remote qualification on a Pixel 9 Pro XL
 * `komodo`, GrapheneOS 2026080501 / Android 17, issue #49; see the qualification ledger in
 * .claude/skills/device-qualification/):
 *
 * - `global battery_charge_limit` — "Limit to 80%": `1` = hard 80% cap (with bypass charging),
 *   `0` = off. Hard-wired to 80, no threshold key. World-readable; writes need only WSS.
 *
 * The defining quirk is [policyLatchesAtPlug]: GrapheneOS samples the key only at the START of a
 * plug session. An external write updates the Settings UI live but has no hardware effect until
 * the next unplug→replug (the native toggle applies live because Settings pokes the charging
 * service directly — which also means an observed external change is a real, live user choice and
 * the session watcher's cancel-without-restore stays correct). Consequences wired through the
 * capability flag: pending-until-replug verification, the session replug grace window, and
 * `reapply == apply` (there is no observer to re-trigger; the plug event is the trigger).
 *
 * While the limit is enforcing, the device reports the same hardware signal as stock Pixel
 * (`EXTRA_CHARGING_STATUS` = 4, battery status NOT_CHARGING at the cap), so [decodeHardware]
 * provides real enforcement evidence — the only proof that beats a merely-configured readback on
 * this ROM. The reconnect gesture stays unsupported: its override write lands strictly after the
 * replug broadcast, which this ROM has already sampled past.
 *
 * Identity is package-based ([DeviceInfo.isGrapheneOs]); capability is the key's presence — the OS
 * ships the toggle exactly where its implementation works, and GrapheneOS is a single vendor on a
 * narrow Pixel-only device set, so no codename allowlist is needed. Registered BEFORE the Pixel
 * adapter, whose Google+Pixel probe would otherwise swallow every GrapheneOS device.
 */
@Singleton
class GrapheneOsChargingAdapter @Inject constructor() : ChargingAdapter {
    override val id = "grapheneos-chargelimit-v1"
    override val displayName = R.string.adapter_name_grapheneos.toCaString()

    override val supportedPolicies = listOf(
        ChargePolicy.FixedLimit(LIMIT_PERCENT),
        ChargePolicy.Unrestricted,
    )

    override val verification = VerificationStrategy.SYNC_READBACK
    override val policyLatchesAtPlug = true

    override val observedSettingUris
        get() = listOf(Settings.Global.getUriFor(KEY_CHARGE_LIMIT))

    override fun probe(device: DeviceInfo): AdapterSupport {
        val matched = device.isGrapheneOs
        return AdapterSupport(
            matched = matched,
            controlEnabled = matched && device.hasBatteryChargeLimit && device.isSystemUser,
            detail = when {
                !matched -> R.string.adapter_detail_requires_grapheneos
                !device.hasBatteryChargeLimit -> R.string.adapter_detail_grapheneos_no_key
                !device.isSystemUser -> R.string.adapter_detail_secondary_user
                else -> R.string.adapter_detail_grapheneos_ready
            },
            // A GrapheneOS build without the key is exactly what the contribution wizard can map
            // (e.g. a future release that moves or renames it).
            contributionWanted = matched && !device.hasBatteryChargeLimit,
        )
    }

    override suspend fun read(backend: AccessBackend): ChargeObservation {
        val limit = backend.read(SettingNamespace.GLOBAL, KEY_CHARGE_LIMIT)
        if (!limit.readable) {
            return ChargeObservation.Unknown(
                limit.error ?: R.string.charging_reason_settings_unreadable.toCaString(),
            )
        }
        return when (limit.value) {
            VALUE_OFF -> ChargeObservation.Verified(ChargePolicy.Unrestricted, backend.kind)
            VALUE_LIMITED -> ChargeObservation.Verified(ChargePolicy.FixedLimit(LIMIT_PERCENT), backend.kind)
            // Includes absence: the gate required the key, so a missing value mid-run is an anomaly,
            // and the factory-absent state is unverified — refuse rather than guess (a session start
            // refuses on this instead of overwriting a state it cannot restore).
            else -> ChargeObservation.Unknown(
                R.string.charging_reason_value_unrecognized.toCaString(
                    KEY_CHARGE_LIMIT, limit.value.toString(),
                ),
                unrecognizedValue = true,
            )
        }
    }

    override suspend fun apply(policy: ChargePolicy, backend: AccessBackend): Boolean {
        val value = when (policy) {
            ChargePolicy.FixedLimit(LIMIT_PERCENT) -> VALUE_LIMITED
            ChargePolicy.Unrestricted -> VALUE_OFF
            else -> return false
        }
        if (!backend.write(SettingMutation(SettingNamespace.GLOBAL, KEY_CHARGE_LIMIT, value))) return false
        // Command success does not guarantee the final configuration; require read-back equality.
        // This verifies the CONFIGURED value only — enforcement follows at the next plug session
        // (policyLatchesAtPlug), which the pending-until-replug state tracks.
        val observed = read(backend)
        return observed is ChargeObservation.Verified && observed.policy == policy
    }

    override fun readHardware(context: Context): ChargeObservation? {
        val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val plugged = (battery?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0
        val chargingState = battery?.getIntExtra(BatteryManager.EXTRA_CHARGING_STATUS, STATE_INVALID)
            ?: STATE_INVALID
        return decodeHardware(chargingState, plugged)
    }

    /**
     * Not shared with the Pixel decode on purpose: Pixel maps state 5 to a verified Adaptive
     * profile, which this adapter neither supports nor could restore. Everything GrapheneOS was
     * observed to report (4 while holding, 1 otherwise) decodes the same way as on stock Pixel.
     */
    override fun decodeHardware(chargingState: Int, plugged: Boolean): ChargeObservation? {
        // Unplugged, the sticky broadcast retains its last powered value — never evidence.
        if (!plugged) return null
        return when (chargingState) {
            STATE_LONG_LIFE -> ChargeObservation.Verified(
                ChargePolicy.FixedLimit(LIMIT_PERCENT),
                BackendKind.BATTERY_HARDWARE,
            )
            STATE_NORMAL -> ChargeObservation.Unknown(R.string.charging_reason_hw_normal.toCaString())
            STATE_TOO_COLD -> ChargeObservation.Unknown(R.string.charging_reason_hw_too_cold.toCaString())
            STATE_TOO_HOT -> ChargeObservation.Unknown(R.string.charging_reason_hw_too_hot.toCaString())
            else -> ChargeObservation.Unknown(R.string.charging_reason_hw_unrecognized.toCaString())
        }
    }

    override fun nativeSettingsIntent(context: Context): Intent {
        // AOSP Settings → Battery hosts GrapheneOS's "Charging optimization"; never the Google
        // Settings-Intelligence action, which does not exist on this ROM.
        val specific = Intent(Intent.ACTION_POWER_USAGE_SUMMARY).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return if (specific.resolveActivity(context.packageManager) != null) {
            specific
        } else {
            Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    companion object {
        const val KEY_CHARGE_LIMIT = DeviceInfo.KEY_BATTERY_CHARGE_LIMIT
        const val VALUE_LIMITED = "1"
        const val VALUE_OFF = "0"
        const val LIMIT_PERCENT = 80

        // BatteryManager.EXTRA_CHARGING_STATUS values (hidden extra; same encoding as stock Pixel).
        private const val STATE_INVALID = 0
        private const val STATE_NORMAL = 1
        private const val STATE_TOO_COLD = 2
        private const val STATE_TOO_HOT = 3
        private const val STATE_LONG_LIFE = 4
    }
}
