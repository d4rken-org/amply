package eu.darken.amply.charging.core.adapter

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import eu.darken.amply.R
import eu.darken.amply.charging.core.BackendKind
import eu.darken.amply.charging.core.ChargeObservation
import eu.darken.amply.charging.core.ChargePolicy
import eu.darken.amply.charging.core.DeviceInfo
import eu.darken.amply.charging.core.access.AccessBackend
import eu.darken.amply.charging.core.access.LineageChargeReadout
import eu.darken.amply.charging.core.access.LineageChargeReader
import eu.darken.amply.charging.core.access.SettingMutation
import eu.darken.amply.charging.core.access.SettingNamespace
import eu.darken.amply.common.ca.toCaString
import eu.darken.amply.stats.core.StatsLimitHitDetector
import javax.inject.Inject
import javax.inject.Singleton

/**
 * LineageOS native Charging Control via its private `content://lineagesettings/system` provider
 * (three keys: `charging_control_enabled`, `charging_control_mode`, `charging_control_charging_limit`).
 * A hard percent cap is `enabled=1` + `mode=3` (LIMIT) + `limit=N`; `enabled=0` is unrestricted.
 * LineageOS's own `ChargingControlController` observes these keys and re-drives the
 * `vendor.lineage.health.IChargingControl` HAL, so an external write is honored.
 *
 * **Reads** are unprivileged and taken as one consistent snapshot ([LineageChargeReader]). **Writes
 * require Shizuku** (the provider's write permission is held only by the shell UID, so
 * `WRITE_SECURE_SETTINGS` cannot write it — [preferShizukuForWrites]). Verified with read-back equality
 * ([VerificationStrategy.SYNC_READBACK]).
 *
 * The gate is **two-layered**, because the two layers answer different questions:
 *
 *  - *Did the ROM accept the configuration?* — the write read-back ([VerificationStrategy.SYNC_READBACK]).
 *  - *Did the hardware act on it?* — observed enforcement. The HAL is device- AND build-dependent (some
 *    builds flip the setting but never limit — the `mIsLimitSet:false` class of bug; oriole exposed the
 *    LIMIT mode bit on LineageOS 20 and dropped it on 23.2 on identical hardware), and no read can
 *    answer it. So this adapter sets [enforcementEvidenceRequired]: it *matches* every LineageOS build
 *    that ships the settings provider, but control stays off until either the maintainer qualified the
 *    codename ([QUALIFIED_CODENAMES], the fast path, still empty) or the user ran a verification on
 *    their own device and the cap was observed holding. See
 *    `charging/core/enforcement/EnforcementVerdictEngine` and the ledger in
 *    `.claude/skills/device-qualification/`.
 *
 * A LineageOS build **without** the provider no longer matches here at all and falls through to
 * [LineageLabAdapter] (generic diagnostics text): keeping provider presence in [probe]'s `matched`
 * preserves the registry's custom-ROM-before-OEM ordering for those devices, at the cost of the more
 * specific "not available on this build" note this adapter used to show them.
 */
@Singleton
class LineageChargingAdapter @Inject constructor(
    private val reader: LineageChargeReader,
) : ChargingAdapter {

    /** Test seam: exercises the gate logic while production ships the empty [QUALIFIED_CODENAMES]. */
    internal constructor(reader: LineageChargeReader, qualifiedCodenames: Set<String>) : this(reader) {
        this.qualifiedCodenames = qualifiedCodenames
    }

    private var qualifiedCodenames: Set<String> = QUALIFIED_CODENAMES

    override val id = "lineageos-chargingcontrol-v1"
    override val displayName = R.string.adapter_name_lineageos.toCaString()

    override val supportedPolicies = SUPPORTED_LIMITS.map { ChargePolicy.FixedLimit(it) } + ChargePolicy.Unrestricted

    override val defaultProtectivePolicy = ChargePolicy.FixedLimit(80)
    override val sessionOverridePolicy = ChargePolicy.Unrestricted
    override val verification = VerificationStrategy.SYNC_READBACK
    override val preferShizukuForWrites = true
    override val enforcementEvidenceRequired = true

    override fun maintainerQualified(device: DeviceInfo) = device.codename in qualifiedCodenames

    /**
     * LineageOS drives AOSP's charge-policy plumbing, so while its Charging Control HAL holds the
     * battery at the limit the public battery broadcast reports
     * [StatsLimitHitDetector.HARDWARE_HOLD_STATE] — `Charging state: 4`, observed on a Pixel 6
     * (`oriole`, LineageOS 23.2) sitting at its cap. That is the corroboration a CONFIRMED verdict
     * needs, because a passive plateau cannot tell a cap hold from a thermal or supply-induced pause.
     *
     * Unplugged the extra is a stale sticky value from the last plug session, so it is not evidence
     * in either direction: null, which the verdict engine reads as "cannot confirm".
     */
    override fun hardwareHoldSignal(chargingStatus: Int?, plugged: Boolean): Boolean? =
        if (plugged) chargingStatus == StatsLimitHitDetector.HARDWARE_HOLD_STATE else null

    override fun probe(device: DeviceInfo): AdapterSupport {
        val matched = device.isLineageOs && device.hasLineageSettingsProvider
        return AdapterSupport(
            matched = matched,
            controlEnabled = matched && device.isSystemUser,
            detail = when {
                !matched -> R.string.adapter_detail_requires_lineageos
                !device.isSystemUser -> R.string.adapter_detail_secondary_user
                else -> R.string.adapter_detail_lineageos_ready
            },
            // Whether this device is worth a report is decided by the enforcement gate (a refutation is),
            // not by the adapter: its keys are fully mapped either way.
            contributionWanted = false,
            // The three charging_control_* keys are already mapped and live in a provider the wizard does
            // not capture, so a guided run always diffs to nothing and cannot be delivered — same reason
            // LineageLabAdapter withholds it. A refuted device contributes through the direct report.
            guidedCaptureUseful = false,
        )
    }

    override suspend fun read(backend: AccessBackend): ChargeObservation = decode(reader.readChargeControl(), backend.kind)

    /** Pure decode of a single consistent snapshot — Verified only for states v1 can restore exactly. */
    private fun decode(readout: LineageChargeReadout, kind: BackendKind): ChargeObservation = when (readout) {
        is LineageChargeReadout.Unreadable -> ChargeObservation.Unknown(readout.reason)
        is LineageChargeReadout.Values -> when (readout.enabled) {
            VALUE_OFF -> ChargeObservation.Verified(ChargePolicy.Unrestricted, kind)
            VALUE_ON -> {
                // Only a canonical tick string is restorable — reject "080"/"+80"/whitespace, which the
                // write boundary and mutations could not reproduce.
                val percent = readout.limit?.takeIf { it in CANONICAL_LIMIT_STRINGS }?.toInt()
                if (readout.mode == MODE_LIMIT && percent != null) {
                    ChargeObservation.Verified(ChargePolicy.FixedLimit(percent), kind)
                } else {
                    // Enabled, but an AUTO/CUSTOM schedule or an off-tick/non-canonical limit v1 cannot
                    // restore exactly → refuse so a session never clobbers the user's native choice.
                    unrecognized(KEY_MODE, "mode=${readout.mode},limit=${readout.limit}")
                }
            }
            // Absent (null) or malformed enabled: refuse rather than guess. Absent-key meaning is
            // characterized on-device (see the qualification ledger) before any relaxation here.
            else -> unrecognized(KEY_ENABLED, readout.enabled)
        }
    }

    override suspend fun apply(policy: ChargePolicy, backend: AccessBackend): Boolean {
        val mutations = mutationsFor(policy) ?: return false
        if (!mutations.all { backend.write(it) }) return false
        // Writes upsert synchronously; require read-back equality (the multi-key transition isn't atomic).
        val observed = read(backend)
        return observed is ChargeObservation.Verified && observed.policy == policy
    }

    /** Ordered writes: limit → mode → enabled, so the observable "on" flip is last with a consistent mode/limit. */
    internal fun mutationsFor(policy: ChargePolicy): List<SettingMutation>? = when (policy) {
        is ChargePolicy.FixedLimit -> if (policy.percent in SUPPORTED_LIMITS) {
            listOf(
                SettingMutation(SettingNamespace.LINEAGE_SYSTEM, KEY_LIMIT, policy.percent.toString()),
                SettingMutation(SettingNamespace.LINEAGE_SYSTEM, KEY_MODE, MODE_LIMIT),
                SettingMutation(SettingNamespace.LINEAGE_SYSTEM, KEY_ENABLED, VALUE_ON),
            )
        } else {
            null
        }
        ChargePolicy.Unrestricted -> listOf(
            SettingMutation(SettingNamespace.LINEAGE_SYSTEM, KEY_ENABLED, VALUE_OFF),
        )
        ChargePolicy.Adaptive, ChargePolicy.PauseAtFull -> null
    }

    override val observedSettingUris
        get() = listOf(
            Uri.parse(SYSTEM_URI),
            Uri.parse("$SYSTEM_URI/$KEY_ENABLED"),
            Uri.parse("$SYSTEM_URI/$KEY_MODE"),
            Uri.parse("$SYSTEM_URI/$KEY_LIMIT"),
        )

    override fun nativeSettingsIntent(context: Context): Intent {
        // LineageOS keeps Charging Control under Settings › Battery; the generic AOSP power-usage screen
        // is the closest resolvable, brittle-ComponentName-free entry point, with Battery Saver as fallback.
        val powerUsage = Intent(Intent.ACTION_POWER_USAGE_SUMMARY).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return if (powerUsage.resolveActivity(context.packageManager) != null) {
            powerUsage
        } else {
            Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    private fun unrecognized(key: String, value: String?) = ChargeObservation.Unknown(
        R.string.charging_reason_value_unrecognized.toCaString(key, value.toString()),
        unrecognizedValue = true,
    )

    companion object {
        const val SYSTEM_URI = "content://lineagesettings/system"
        const val KEY_ENABLED = "charging_control_enabled"
        const val KEY_MODE = "charging_control_mode"
        const val KEY_LIMIT = "charging_control_charging_limit"
        const val VALUE_ON = "1"
        const val VALUE_OFF = "0"
        const val MODE_LIMIT = "3" // LineageOS ChargingControlMode.LIMIT

        /** Discrete percent ticks Amply exposes/writes (LineageOS's native slider spans 70..100). */
        val SUPPORTED_LIMITS = listOf(70, 75, 80, 85, 90, 95)
        private val CANONICAL_LIMIT_STRINGS = SUPPORTED_LIMITS.map { it.toString() }.toSet()

        /**
         * The **maintainer fast path**: device codenames (`Build.DEVICE`) whose charge-control HAL was
         * physically confirmed to enforce the limit, which therefore need no local evidence. **Ships
         * empty** — it is no longer what makes the adapter reachable (every provider-carrying LineageOS
         * build matches now), only what lets a device skip verification. Widen ONLY with a qualified
         * device plus a ledger row (see `.claude/skills/device-qualification/`), and remember that HAL
         * capability is build-scoped: a bare codename here claims every build on that device.
         */
        val QUALIFIED_CODENAMES = emptySet<String>()
    }
}
