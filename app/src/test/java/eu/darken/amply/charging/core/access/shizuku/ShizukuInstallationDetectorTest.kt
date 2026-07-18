package eu.darken.amply.charging.core.access.shizuku

import android.content.pm.PackageManager
import android.content.pm.PermissionInfo
import com.google.common.truth.Truth.assertThat
import org.junit.Test

@Suppress("DEPRECATION")
class ShizukuInstallationDetectorTest {
    @Test
    fun `returns the package declaring the Shizuku permission`() {
        val result = resolveShizukuManagerPackage {
            PermissionInfo().apply { packageName = "com.example.shizuku.fork" }
        }

        assertThat(result).isEqualTo("com.example.shizuku.fork")
    }

    @Test
    fun `returns null when the Shizuku permission is absent`() {
        val result = resolveShizukuManagerPackage {
            throw PackageManager.NameNotFoundException()
        }

        assertThat(result).isNull()
    }

    @Test
    fun `rejects a blank permission owner`() {
        val result = resolveShizukuManagerPackage {
            PermissionInfo().apply { packageName = "" }
        }

        assertThat(result).isNull()
    }
}
