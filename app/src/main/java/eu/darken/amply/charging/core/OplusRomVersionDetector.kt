package eu.darken.amply.charging.core

/**
 * Reads the ColorOS/OxygenOS (Oplus) ROM major version from `ro.build.version.oplusrom`
 * (e.g. "V15.0.0" → 15). This property is present across the whole Oplus family — OnePlus,
 * Oppo, and Realme — and absent on other OEMs, so it doubles as the family signal. Null on any
 * failure or non-Oplus device; downstream gates treat null as "not qualified".
 */
object OplusRomVersionDetector {

    fun detect(): Int? = parse(SystemPropertyReader.read(PROPERTY))

    internal fun parse(raw: String?): Int? {
        val trimmed = raw?.trim()?.removePrefix("V")?.removePrefix("v")?.takeIf { it.isNotEmpty() } ?: return null
        return trimmed.substringBefore('.').toIntOrNull()?.takeIf { it > 0 }
    }

    private const val PROPERTY = "ro.build.version.oplusrom"
}
