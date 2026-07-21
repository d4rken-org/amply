package eu.darken.amply.charging.core

import eu.darken.amply.common.debug.logging.log
import eu.darken.amply.common.debug.logging.logTag

/**
 * Reads Samsung's One UI version from `ro.build.version.oneui` (e.g. 80000 = One UI 8.0,
 * 40100 = One UI 4.1). Uses reflective SystemProperties access because there is no public API;
 * any failure (hidden-API policy, non-Samsung device, unparsable value) yields null, which
 * downstream gates treat as "not qualified".
 */
object OneUiVersionDetector {

    fun detect(): Int? = runCatching {
        val systemProperties = Class.forName("android.os.SystemProperties")
        val get = systemProperties.getMethod("get", String::class.java)
        val raw = get.invoke(null, PROPERTY) as? String
        parse(raw)
    }.onFailure {
        log(TAG) { "One UI version unreadable: $it" }
    }.getOrNull()

    internal fun parse(raw: String?): Int? = raw?.trim()?.takeIf { it.isNotEmpty() }?.toIntOrNull()?.takeIf { it > 0 }

    private const val PROPERTY = "ro.build.version.oneui"
    private val TAG = logTag("Charging", "OneUiVersionDetector")
}
