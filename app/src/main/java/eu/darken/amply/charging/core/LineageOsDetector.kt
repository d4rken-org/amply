package eu.darken.amply.charging.core

/**
 * Detects LineageOS (and close derivatives that keep the property) from `ro.lineage.build.version`
 * (e.g. "23.2"). The value is the ROM version string; presence alone is what gates the Lineage
 * charging-control adapter — the feature has shipped since LineageOS 20 and is not version-scoped.
 * Null on any failure or non-Lineage device; downstream gates treat null as "not LineageOS".
 */
object LineageOsDetector {

    fun detect(): String? = parse(SystemPropertyReader.read(PROPERTY))

    internal fun parse(raw: String?): String? = raw?.trim()?.takeIf { it.isNotEmpty() }

    private const val PROPERTY = "ro.lineage.build.version"
}
