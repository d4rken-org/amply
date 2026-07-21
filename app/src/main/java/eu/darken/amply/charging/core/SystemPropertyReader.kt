package eu.darken.amply.charging.core

import eu.darken.amply.common.debug.logging.log
import eu.darken.amply.common.debug.logging.logTag

/**
 * Reflective read of `android.os.SystemProperties` — there is no public API for vendor build
 * properties. Any failure (hidden-API policy, missing property) yields null; callers must treat
 * null as "not qualified", never as a default.
 */
object SystemPropertyReader {

    fun read(property: String): String? = runCatching {
        val systemProperties = Class.forName("android.os.SystemProperties")
        val get = systemProperties.getMethod("get", String::class.java)
        (get.invoke(null, property) as? String)?.trim()?.takeIf { it.isNotEmpty() }
    }.onFailure {
        log(TAG) { "System property $property unreadable: $it" }
    }.getOrNull()

    private val TAG = logTag("Charging", "SystemPropertyReader")
}
