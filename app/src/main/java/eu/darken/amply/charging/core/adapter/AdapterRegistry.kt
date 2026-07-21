package eu.darken.amply.charging.core.adapter

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import eu.darken.amply.R
import eu.darken.amply.charging.core.DeviceInfo
import javax.inject.Inject
import javax.inject.Singleton

data class AdapterSelection(
    val adapter: ChargingAdapter?,
    val support: AdapterSupport,
)

@Singleton
class AdapterRegistry @Inject constructor(
    @ApplicationContext private val context: Context,
    pixel: PixelChargingAdapter,
    samsungModern: SamsungModernChargingAdapter,
    samsungLegacy: SamsungLegacyChargingAdapter,
    samsungLab: SamsungLabAdapter,
    xiaomi: XiaomiChargingAdapter,
    xiaomiLab: XiaomiLabAdapter,
    onePlus: OnePlusLabAdapter,
) {
    // Live adapters match only their verified device/version scopes; other devices of the same
    // OEM fall through to the diagnostics-only lab adapters.
    private val adapters = listOf(pixel, samsungModern, samsungLegacy, samsungLab, xiaomi, xiaomiLab, onePlus)

    fun select(device: DeviceInfo = DeviceInfo.current(context)): AdapterSelection {
        val match = adapters.firstNotNullOfOrNull { adapter ->
            adapter.probe(device).takeIf { it.matched }?.let { adapter to it }
        }
        return match?.let { AdapterSelection(it.first, it.second) } ?: AdapterSelection(
            adapter = null,
            support = AdapterSupport(
                matched = false,
                controlEnabled = false,
                detail = R.string.adapter_detail_none,
                contributionWanted = true,
            ),
        )
    }
}
