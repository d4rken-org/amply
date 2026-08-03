package eu.darken.amply.charging.core

/**
 * Best-effort read of the LineageOS ROM version string from `ro.lineage.build.version` (e.g. "23.2"),
 * for diagnostics and device-support reports only.
 *
 * **This is not a LineageOS detector — do not gate on it.** Every `ro.lineage.*` property is labelled
 * `u:object_r:custom_version_prop:s0`, which SELinux denies to `untrusted_app`. `SystemProperties.get`
 * returns an empty string on denial rather than throwing (see [SystemPropertyReader]), so on a real
 * LineageOS device this returns null and is indistinguishable from stock Android. Verified on
 * LineageOS 23.2 / Android 16 (oriole): `avc: denied { read } ... tcontext=custom_version_prop`.
 *
 * Use [DeviceInfo.isLineageOs], which is backed by the app-readable `org.lineageos.android` system
 * feature. Non-null here only on builds that relabel the property, so it stays an OR-input to that
 * flag rather than the sole signal.
 */
object LineageOsDetector {

    fun detect(): String? = parse(SystemPropertyReader.read(PROPERTY))

    internal fun parse(raw: String?): String? = raw?.trim()?.takeIf { it.isNotEmpty() }

    private const val PROPERTY = "ro.lineage.build.version"
}
