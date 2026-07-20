package eu.darken.amply.charging.core.adapter

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
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
    samsung: SamsungLabAdapter,
    onePlus: OnePlusLabAdapter,
) {
    private val adapters = listOf(pixel, samsung, onePlus)

    fun select(device: DeviceInfo = DeviceInfo.current(context)): AdapterSelection {
        val match = adapters.firstNotNullOfOrNull { adapter ->
            adapter.probe(device).takeIf { it.matched }?.let { adapter to it }
        }
        return match?.let { AdapterSelection(it.first, it.second) } ?: AdapterSelection(
            adapter = null,
            support = AdapterSupport(
                matched = false,
                controlEnabled = false,
                detail = "No charging adapter is known for this device",
                contributionWanted = true,
            ),
        )
    }
}
