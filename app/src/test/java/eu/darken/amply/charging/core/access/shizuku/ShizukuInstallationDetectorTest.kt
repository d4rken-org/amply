package eu.darken.amply.charging.core.access.shizuku

import android.content.pm.PackageManager
import android.content.pm.PermissionInfo
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

@Suppress("DEPRECATION")
class ShizukuInstallationDetectorTest {
    @Test
    fun `returns the package declaring the Shizuku permission`() {
        val result = resolveShizukuManagerPackage {
            PermissionInfo().apply { packageName = "com.example.shizuku.fork" }
        }

        result shouldBe "com.example.shizuku.fork"
    }

    @Test
    fun `returns null when the Shizuku permission is absent`() {
        val result = resolveShizukuManagerPackage {
            throw PackageManager.NameNotFoundException()
        }

        result shouldBe null
    }

    @Test
    fun `rejects a blank permission owner`() {
        val result = resolveShizukuManagerPackage {
            PermissionInfo().apply { packageName = "" }
        }

        result shouldBe null
    }
}
