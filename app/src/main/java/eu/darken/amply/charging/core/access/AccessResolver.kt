package eu.darken.amply.charging.core.access

import javax.inject.Inject
import javax.inject.Singleton

data class AccessSnapshot(
    val direct: BackendStatus,
    val shizuku: BackendStatus,
) {
    val canControl: Boolean get() = direct.ready || shizuku.ready
    val canVerify: Boolean get() = shizuku.ready
    val label: String
        get() = when {
            direct.ready && shizuku.ready -> "WSS + Shizuku"
            direct.ready -> "WRITE_SECURE_SETTINGS"
            shizuku.ready -> "Shizuku"
            else -> "Setup required"
        }
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
