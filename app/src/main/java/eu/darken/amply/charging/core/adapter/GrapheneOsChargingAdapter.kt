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
 *   `0` = off. Hard-wired to 80, no threshold key.
 *
 * **Access is Shizuku-only.** GrapheneOS declares the key `@Protected(read = SYSTEM_UI, readWrite =
 * SETTINGS)` (frameworks_base c30c6393) and its SettingsProvider throws SecurityException on both
 * reads and writes from every other package — including WRITE_SECURE_SETTINGS holders; the check is
 * package-based and runs after the permission check (e87c93a2). The one exemption Amply can use is
 * the shell UID ("ADB is used for testing" in their enforcement), which is exactly how the Shizuku
 * user service executes `settings get/put`. Verified on-device via the issue-#49 beta run: the
 * unprivileged key probe returned false while the same report showed the limit enforcing
 * (charging state 4), i.e. reads fail closed as designed.
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
 * Identity is package-based ([DeviceInfo.isGrapheneOs]). The key's presence is deliberately NOT a
 * gate condition: `@Protected` makes it unprobeable from app context (the unprivileged read is
 * denied whether the key exists or not), so the feature is treated as present on any GrapheneOS
 * build — the Xiaomi-precedent assumption, with the same accepted failure mode: on a hypothetical
 * build without the feature, a shell-UID write can still create the row and read it back, so Amply
 * would claim configured control that no charging component consumes. That is a harmless false
 * claim (no battery hazard — nothing enforces anything), and the hardware decode stays honest
 * (state 4 never appears). Their charge-limit implementation ships in the platform for every
 * Google device (BatteryChargeLimit.isGoogleDevice()), which is every device GrapheneOS supports,
 * so the assumption has no known counterexample. Registered BEFORE the Pixel adapter, whose
 * Google+Pixel probe would otherwise swallow every GrapheneOS device.
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

    // Structural, not a missing signal: this ROM samples the key at plug-session start, and the
    // gesture's override write lands strictly after the replug broadcast it would have to beat. It
    // would read back correctly and change nothing until the *next* replug. See the class doc.
    override val reconnectGestureSupport = ReconnectSupport.NONE

    // Not because the namespace needs it (global is WSS-writable elsewhere) but because the key is
    // @Protected: a direct write throws SecurityException no matter which permissions Amply holds,
    // while the shell UID is exempt. Reads share the restriction; readSyncDirectFirst's direct
    // attempt comes back unreadable and falls through to the Shizuku backend.
    override val preferShizukuForWrites = true

    override val observedSettingUris
        get() = listOf(Settings.Global.getUriFor(KEY_CHARGE_LIMIT))

    override fun probe(device: DeviceInfo): AdapterSupport {
        val matched = device.isGrapheneOs
        return AdapterSupport(
            matched = matched,
            // No key-presence condition: @Protected denies the unprivileged probe regardless of
            // whether the key exists, so presence is assumed on GrapheneOS (see the class doc).
            controlEnabled = matched && device.isSystemUser,
            detail = when {
                !matched -> R.string.adapter_detail_requires_grapheneos
                !device.isSystemUser -> R.string.adapter_detail_secondary_user
                else -> R.string.adapter_detail_grapheneos_ready
            },
            // The keys are fully mapped; there is nothing for a contribution to discover, and the
            // guided wizard's capture (Shizuku, shell UID) would only re-find the known key.
            contributionWanted = false,
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
            // Absence IS the factory off state, by upstream definition: GrapheneOS reads the key
            // through BoolSetting(Scope.GLOBAL, BATTERY_CHARGE_LIMIT, /* default */ false)
            // (frameworks_base c30c6393), so a never-toggled device has no row and charges
            // unrestricted. Decoding it as such is what lets a session run on a fresh install.
            null, VALUE_OFF -> ChargeObservation.Verified(ChargePolicy.Unrestricted, backend.kind)
            VALUE_LIMITED -> ChargeObservation.Verified(ChargePolicy.FixedLimit(LIMIT_PERCENT), backend.kind)
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
