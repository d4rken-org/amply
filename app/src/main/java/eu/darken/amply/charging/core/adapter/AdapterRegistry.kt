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
    lineage: LineageChargingAdapter,
    lineageLab: LineageLabAdapter,
    pixel: PixelChargingAdapter,
    samsungModern: SamsungModernChargingAdapter,
    samsungLegacy: SamsungLegacyChargingAdapter,
    samsungLab: SamsungLabAdapter,
    xiaomi: XiaomiChargingAdapter,
    xiaomiLab: XiaomiLabAdapter,
    onePlus: OnePlusChargingAdapter,
    onePlusLab: OnePlusLabAdapter,
) {
    // LineageOS adapters come FIRST: a custom ROM changes charging control regardless of the OEM
    // hardware underneath, so a LineageOS build on Samsung/Xiaomi/OnePlus/Pixel must be handled by the
    // Lineage live/lab pair — never swallowed by a manufacturer-based OEM adapter. lineageLab (any
    // LineageOS build) sits right after the live adapter (qualified codenames) and before all OEM
    // adapters. Stock devices have lineageOsVersion == null, so both skip and OEM matching proceeds.
    // Live adapters otherwise match only their verified scopes; same-OEM misses fall to the lab adapters.
    private val adapters = listOf(
        lineage, lineageLab,
        pixel, samsungModern, samsungLegacy, samsungLab, xiaomi, xiaomiLab, onePlus, onePlusLab,
    )

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
