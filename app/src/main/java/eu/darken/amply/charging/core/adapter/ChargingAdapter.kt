package eu.darken.amply.charging.core.adapter

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.annotation.StringRes
import eu.darken.amply.charging.core.access.AccessBackend
import eu.darken.amply.charging.core.ChargeObservation
import eu.darken.amply.charging.core.ChargePolicy
import eu.darken.amply.charging.core.DeviceInfo
import eu.darken.amply.charging.core.enforcement.EnforcementStatus
import eu.darken.amply.common.ca.CaString

data class AdapterSupport(
    val matched: Boolean,
    val controlEnabled: Boolean,
    @param:StringRes val detail: Int,
    /**
     * Whether an unsupported device of this kind is a useful device-support contribution.
     * True for OEMs Amply wants to add (unknown manufacturers, diagnostics-only lab adapters);
     * false for the live Pixel line, whose gate failures are known device-class limitations.
     */
    val contributionWanted: Boolean = false,
    /**
     * Whether the guided settings-diff wizard can discover anything here. False when the control keys are already
     * mapped, so toggling modes produces an empty diff and the wizard dead-ends at delivery — true of LineageOS,
     * whose keys are known and whose only open question (does the charging HAL actually enforce the cap?) no
     * settings capture can answer. Those devices contribute via the direct report instead, which carries the
     * HAL-capability probe. Independent of [contributionWanted]: the contribution is still wanted, just not by
     * this instrument.
     */
    val guidedCaptureUseful: Boolean = true,
    /**
     * Where this device sits on the "does the hardware actually enforce the cap" question, or **null**
     * when the question does not apply to this adapter (every adapter but the ones setting
     * [ChargingAdapter.enforcementEvidenceRequired]). Deliberately nullable rather than a blanket
     * "qualified" default: labelling the lab and unsupported adapters CONFIRMED would claim exactly
     * what they cannot.
     *
     * Filled in by [AdapterRegistry], not by the probes — the probes see immutable device information,
     * while this depends on stored evidence and an opt-in.
     */
    val enforcement: EnforcementStatus? = null,
)

/** How an adapter's applied configuration can be confirmed. */
enum class VerificationStrategy {
    /** Writes take effect asynchronously; only a hardware signal proves the target is active (Pixel). */
    ASYNC_HARDWARE,

    /** Writes apply immediately and the configured values can be read back directly (Samsung global keys). */
    SYNC_READBACK,
}

interface ChargingAdapter {
    val id: String
    val displayName: CaString
    val supportedPolicies: List<ChargePolicy>
    val observedSettingUris: List<Uri> get() = emptyList()

    /** Policy a temporary full-charge session writes while it is active. */
    val sessionOverridePolicy: ChargePolicy get() = ChargePolicy.Unrestricted

    /** Protective fallback when no restorable policy can be observed or the stored one is unsupported. */
    val defaultProtectivePolicy: ChargePolicy get() = ChargePolicy.FixedLimit(80)

    val verification: VerificationStrategy get() = VerificationStrategy.ASYNC_HARDWARE

    /** Whether the powered→unpowered reconnect gesture's hardware preconditions exist on this adapter. */
    val reconnectGestureSupported: Boolean get() = false

    /**
     * Prefer Shizuku over direct WSS for writes. Two adapter classes set it: keys in the `system`
     * namespace (OnePlus/ColorOS — `WRITE_SECURE_SETTINGS` covers secure/global but not system, so
     * a direct write silently fails while reads stay world-readable), and per-key access-controlled
     * keys (GrapheneOS's `@Protected` charge limit — the provider rejects reads AND writes from
     * everyone but its own system packages and the shell UID, WSS or not; there the direct read
     * comes back unreadable and the sync-read fallback consults Shizuku). Direct WSS is still tried
     * as a last resort, and read-back verification catches a write that did not land.
     */
    val preferShizukuForWrites: Boolean get() = false

    /**
     * The ROM's charging service samples the configured policy only at the START of a plug session:
     * a write made while external power is present has no hardware effect until the next
     * unplug→replug (GrapheneOS). Reads stay synchronous ([VerificationStrategy.SYNC_READBACK] —
     * the readback truthfully reports the *configured* value); this flag changes what a settled
     * write means (pending-until-replug instead of the settling window) and arms the session
     * engine's disconnect grace window.
     */
    val policyLatchesAtPlug: Boolean get() = false

    /**
     * Whether the charging hardware is currently EXPECTED to confirm [policy]: the policy class is
     * one this hardware reliably reports, the evidence channel is live (plugged — unplugged sticky
     * values are stale), and nothing is masking the signal (thermal throttling). Drives the
     * "hardware never confirmed" warning: expected-and-missing is the contradiction worth showing.
     * False by default — sync-readback adapters verify via settings and plug-latched adapters
     * legitimately confirm only at the next plug session, so neither carries an expectation.
     */
    fun confirmationExpected(policy: ChargePolicy, chargingStatus: Int?, plugged: Boolean): Boolean =
        false

    /**
     * Whether control on this adapter must be justified by **observed hardware enforcement** on the
     * user's own device, not by the settings read-back alone. Set where the setting can be written
     * and read back perfectly while the charging HAL never limits (LineageOS): such a device is a
     * CANDIDATE with control OFF until either a maintainer qualified it ([maintainerQualified]) or a
     * user-started verification observed the cap holding. Defaulted, so no other adapter changes.
     */
    val enforcementEvidenceRequired: Boolean get() = false

    /**
     * The maintainer fast path for [enforcementEvidenceRequired] adapters: a device physically
     * qualified against the protocol in the `device-qualification` skill, which needs no local
     * evidence. False everywhere else, including on adapters that require no evidence at all.
     */
    fun maintainerQualified(device: DeviceInfo): Boolean = false

    fun probe(device: DeviceInfo): AdapterSupport
    fun readHardware(context: Context): ChargeObservation? = null
    fun decodeHardware(chargingState: Int, plugged: Boolean): ChargeObservation? = null
    suspend fun read(backend: AccessBackend): ChargeObservation
    suspend fun apply(policy: ChargePolicy, backend: AccessBackend): Boolean

    /** Like [apply], but must produce a real settings mutation even if the values are already configured. */
    suspend fun reapply(policy: ChargePolicy, backend: AccessBackend): Boolean = apply(policy, backend)
    fun nativeSettingsIntent(context: Context): Intent
}
