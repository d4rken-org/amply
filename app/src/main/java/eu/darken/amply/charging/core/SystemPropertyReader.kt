package eu.darken.amply.charging.core

import eu.darken.amply.common.debug.logging.log
import eu.darken.amply.common.debug.logging.logTag

/**
 * Reflective read of `android.os.SystemProperties` — there is no public API for vendor build
 * properties. Any failure (hidden-API policy, missing property) yields null; callers must treat
 * null as "not qualified", never as a default.
 *
 * **A null here does not mean the property is absent.** SELinux labels properties individually, and
 * `SystemProperties.get` returns an *empty string* when the app's domain lacks read access — it does
 * not throw, so nothing is logged and a denied read is indistinguishable from an unset one. A ROM
 * that hides its identity properties behind a custom label therefore reads exactly like stock. Never
 * use a property as the sole gate for a ROM whose properties you have not confirmed readable from an
 * `untrusted_app` process on real hardware; prefer a system feature or a PackageManager lookup.
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
