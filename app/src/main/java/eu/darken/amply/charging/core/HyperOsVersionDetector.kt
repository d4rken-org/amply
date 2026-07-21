package eu.darken.amply.charging.core

/**
 * Reads Xiaomi's HyperOS major version from `ro.mi.os.version.code` (2 = HyperOS 2.x on the
 * qualified Xiaomi 13T; `ro.mi.os.version.name` reads "OS2.0"). This is the true HyperOS ROM
 * version — unlike the frozen legacy `ro.miui.ui.version.code`, it cleanly separates HyperOS
 * generations. Null on any failure or non-HyperOS device (pre-HyperOS MIUI lacks `ro.mi.os.*`);
 * downstream gates treat null as "not qualified".
 */
object HyperOsVersionDetector {

    fun detect(): Int? = parse(SystemPropertyReader.read(PROPERTY))

    internal fun parse(raw: String?): Int? = raw?.trim()?.takeIf { it.isNotEmpty() }?.toIntOrNull()?.takeIf { it > 0 }

    private const val PROPERTY = "ro.mi.os.version.code"
}
