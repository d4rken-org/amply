package eu.darken.amply.charging.core.adapter

import android.content.Context
import android.content.Intent
import android.net.Uri
import eu.darken.amply.charging.core.access.AccessBackend
import eu.darken.amply.charging.core.ChargeObservation
import eu.darken.amply.charging.core.ChargePolicy
import eu.darken.amply.charging.core.DeviceInfo

data class AdapterSupport(
    val matched: Boolean,
    val controlEnabled: Boolean,
    val detail: String,
    /**
     * Whether an unsupported device of this kind is a useful device-support contribution.
     * True for OEMs Amply wants to add (unknown manufacturers, diagnostics-only lab adapters);
     * false for the live Pixel line, whose gate failures are known device-class limitations.
     */
    val contributionWanted: Boolean = false,
)

interface ChargingAdapter {
    val id: String
    val displayName: String
    val supportedPolicies: List<ChargePolicy>
    val observedSettingUris: List<Uri> get() = emptyList()

    fun probe(device: DeviceInfo): AdapterSupport
    fun readHardware(context: Context): ChargeObservation? = null
    fun decodeHardware(chargingState: Int, plugged: Boolean): ChargeObservation? = null
    suspend fun read(backend: AccessBackend): ChargeObservation
    suspend fun apply(policy: ChargePolicy, backend: AccessBackend): Boolean

    /** Like [apply], but must produce a real settings mutation even if the values are already configured. */
    suspend fun reapply(policy: ChargePolicy, backend: AccessBackend): Boolean = apply(policy, backend)
    fun nativeSettingsIntent(context: Context): Intent
}
