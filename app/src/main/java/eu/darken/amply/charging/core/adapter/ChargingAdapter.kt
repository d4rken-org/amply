package eu.darken.amply.charging.core.adapter

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.annotation.StringRes
import eu.darken.amply.charging.core.access.AccessBackend
import eu.darken.amply.charging.core.ChargeObservation
import eu.darken.amply.charging.core.ChargePolicy
import eu.darken.amply.charging.core.DeviceInfo
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
     * Prefer Shizuku over direct WSS for writes. Set by adapters whose keys live in the `system`
     * namespace: `WRITE_SECURE_SETTINGS` covers secure/global but not system, so a direct write
     * would silently fail — Shizuku (shell UID) can write system. Reads are unaffected (system is
     * world-readable). Direct WSS is still tried as a last resort, and read-back verification
     * catches a write that did not land.
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

    fun probe(device: DeviceInfo): AdapterSupport
    fun readHardware(context: Context): ChargeObservation? = null
    fun decodeHardware(chargingState: Int, plugged: Boolean): ChargeObservation? = null
    suspend fun read(backend: AccessBackend): ChargeObservation
    suspend fun apply(policy: ChargePolicy, backend: AccessBackend): Boolean

    /** Like [apply], but must produce a real settings mutation even if the values are already configured. */
    suspend fun reapply(policy: ChargePolicy, backend: AccessBackend): Boolean = apply(policy, backend)
    fun nativeSettingsIntent(context: Context): Intent
}
