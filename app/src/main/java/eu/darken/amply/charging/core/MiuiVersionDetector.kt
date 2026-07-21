package eu.darken.amply.charging.core

/**
 * Reads Xiaomi's MIUI/HyperOS UI version code from `ro.miui.ui.version.code`
 * (816 = HyperOS 2.0 era on the qualified Xiaomi 13T). Null on any failure or non-Xiaomi
 * device; downstream gates treat null as "not qualified".
 */
object MiuiVersionDetector {

    fun detect(): Int? = parse(SystemPropertyReader.read(PROPERTY))

    internal fun parse(raw: String?): Int? = raw?.trim()?.takeIf { it.isNotEmpty() }?.toIntOrNull()?.takeIf { it > 0 }

    private const val PROPERTY = "ro.miui.ui.version.code"
}
