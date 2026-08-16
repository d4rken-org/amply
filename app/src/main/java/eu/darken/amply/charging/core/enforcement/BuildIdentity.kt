package eu.darken.amply.charging.core.enforcement

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.pm.PackageInfoCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import eu.darken.amply.charging.core.DeviceInfo
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Compose the opaque identity a stored [EnforcementEvidence] is scoped to.
 *
 * Deliberately NOT `Build.FINGERPRINT` alone: LineageOS **spoofs the fingerprint to stock**
 * (`google/oriole/oriole:16/…/release-keys`, verified on oriole — see the qualification ledger), so
 * two nightlies with different charge-control behavior share one fingerprint. The build's own
 * incremental id and timestamp move with every ROM build, and the `lineagesettings` provider
 * package's version code moves when the platform component that drives the HAL is updated.
 *
 * Hashed so nothing device-identifying is ever held in a preference or shown in a report; equality is
 * the only thing any caller needs.
 */
internal fun composeBuildIdentity(
    fingerprint: String,
    incremental: String,
    buildTimeMillis: Long,
    settingsProviderVersionCode: Long,
): String {
    val raw = listOf(fingerprint, incremental, buildTimeMillis.toString(), settingsProviderVersionCode.toString())
        .joinToString("|")
    val digest = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray())
    return digest.take(IDENTITY_BYTES).joinToString("") { "%02x".format(it) }
}

/** 8 bytes / 16 hex chars: an equality token, not a cryptographic commitment. */
private const val IDENTITY_BYTES = 8

/** An interface so stores and engines stay unit-testable without the Android build environment. */
interface BuildIdentitySource {
    fun current(): String
}

@Singleton
class DeviceBuildIdentitySource @Inject constructor(
    @ApplicationContext private val context: Context,
) : BuildIdentitySource {

    // Immutable for the process lifetime: every input is fixed at boot, and this is read on every
    // adapter selection.
    private val identity: String by lazy {
        composeBuildIdentity(
            fingerprint = Build.FINGERPRINT.orEmpty(),
            incremental = Build.VERSION.INCREMENTAL.orEmpty(),
            buildTimeMillis = Build.TIME,
            settingsProviderVersionCode = settingsProviderVersionCode(),
        )
    }

    override fun current(): String = identity

    /** 0 when the provider (or its package) can't be resolved — absence is itself part of the identity. */
    private fun settingsProviderVersionCode(): Long = runCatching {
        val packageManager = context.packageManager
        val provider = packageManager.resolveContentProvider(DeviceInfo.LINEAGE_SETTINGS_AUTHORITY, 0)
            ?: return@runCatching 0L
        PackageInfoCompat.getLongVersionCode(
            packageManager.getPackageInfo(provider.packageName, PackageManager.GET_META_DATA),
        )
    }.getOrDefault(0L)
}
