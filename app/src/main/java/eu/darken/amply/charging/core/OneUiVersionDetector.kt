package eu.darken.amply.charging.core

/**
 * Reads Samsung's One UI version from `ro.build.version.oneui` (e.g. 80000 = One UI 8.0,
 * 40100 = One UI 4.1). Null on any failure or non-Samsung device; downstream gates treat
 * null as "not qualified".
 */
object OneUiVersionDetector {

    fun detect(): Int? = parse(SystemPropertyReader.read(PROPERTY))

    internal fun parse(raw: String?): Int? = raw?.trim()?.takeIf { it.isNotEmpty() }?.toIntOrNull()?.takeIf { it > 0 }

    private const val PROPERTY = "ro.build.version.oneui"
}
