package eu.darken.amply.charging.core.access

import eu.darken.amply.R
import eu.darken.amply.common.ca.CaString
import eu.darken.amply.common.ca.toCaString
import javax.inject.Inject
import javax.inject.Singleton

data class AccessSnapshot(
    val direct: BackendStatus,
    val shizuku: BackendStatus,
) {
    val canControl: Boolean get() = direct.ready || shizuku.ready
    val canVerify: Boolean get() = shizuku.ready
    val label: CaString
        get() = when {
            direct.ready && shizuku.ready -> R.string.access_label_wss_shizuku
            direct.ready -> R.string.access_label_wss
            shizuku.ready -> R.string.access_label_shizuku
            else -> R.string.access_label_setup_required
        }.toCaString()
}

@Singleton
class AccessResolver @Inject constructor(
    val direct: DirectSettingsBackend,
    val shizuku: ShizukuSettingsBackend,
) {
    suspend fun snapshot() = AccessSnapshot(
        direct = direct.status(),
        shizuku = shizuku.status(),
    )

    suspend fun writeBackend(): AccessBackend? {
        val state = snapshot()
        return when {
            state.direct.ready -> direct
            state.shizuku.ready -> shizuku
            else -> null
        }
    }

    suspend fun readBackend(): AccessBackend? {
        val state = snapshot()
        return when {
            state.shizuku.ready -> shizuku
            state.direct.ready -> direct
            else -> null
        }
    }
}
