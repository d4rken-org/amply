package eu.darken.amply.charging.core.access

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import eu.darken.amply.R
import eu.darken.amply.charging.core.access.shizuku.ManagerBackend
import eu.darken.amply.charging.core.access.shizuku.PrivilegedManager
import io.kotest.matchers.shouldBe
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Robolectric only to resolve the detail strings; [shizukuBackendStatus] itself is pure. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ShizukuBackendStatusTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun status(manager: PrivilegedManager, available: Boolean, granted: Boolean) =
        shizukuBackendStatus(manager, available, granted)

    private fun BackendStatus.shouldHave(installed: Boolean, available: Boolean, granted: Boolean, detail: String) {
        this.installed shouldBe installed
        this.available shouldBe available
        this.granted shouldBe granted
        this.detail.get(context) shouldBe detail
    }

    private fun text(res: Int, vararg args: Any) = context.getString(res, *args)

    @Test
    fun `nothing installed`() {
        status(PrivilegedManager.NotInstalled, available = false, granted = false)
            .shouldHave(installed = false, available = false, granted = false, text(R.string.access_shizuku_not_installed))
    }

    @Test
    fun `an unreachable manager is installed but not running`() {
        status(PrivilegedManager.Unreachable, available = false, granted = false)
            .shouldHave(installed = true, available = false, granted = false, text(R.string.access_shizuku_not_running))
    }

    @Test
    fun `an installed manager without a connection is not running, for both backends`() {
        for (backend in ManagerBackend.entries) {
            val manager = PrivilegedManager.Installed(backend, "some.manager", connected = false)
            status(manager, available = false, granted = false)
                .shouldHave(installed = true, available = false, granted = false, text(R.string.access_shizuku_not_running))
        }
    }

    @Test
    fun `a connected manager reports its grant, for both backends and an unrecognized package`() {
        val managers = listOf(
            PrivilegedManager.Installed(ManagerBackend.SHIZUKU, "moe.shizuku.privileged.api", connected = true),
            PrivilegedManager.Installed(ManagerBackend.PORTER, "eu.darken.porter", connected = true),
            PrivilegedManager.Installed(ManagerBackend.SHIZUKU, "com.example.renamed.fork", connected = true),
            PrivilegedManager.Installed(ManagerBackend.SHIZUKU, null, connected = true),
        )
        for (manager in managers) {
            status(manager, available = true, granted = false)
                .shouldHave(installed = true, available = true, granted = false, text(R.string.access_shizuku_not_granted))
            status(manager, available = true, granted = true)
                .shouldHave(installed = true, available = true, granted = true, text(R.string.access_shizuku_ready))
            status(manager, available = true, granted = true).ready shouldBe true
        }
    }

    @Test
    fun `a connected manager whose binder stopped answering is not running`() {
        val manager = PrivilegedManager.Installed(ManagerBackend.PORTER, "eu.darken.porter", connected = true)
        status(manager, available = false, granted = false)
            .shouldHave(installed = true, available = false, granted = false, text(R.string.access_shizuku_not_running))
    }

    @Test
    fun `an incompatible manager names the side that needs an update`() {
        val serverOld = PrivilegedManager.Incompatible(
            ManagerBackend.PORTER, "eu.darken.porter", serverTooOld = true, clientTooOld = false,
        )
        status(serverOld, available = false, granted = false).shouldHave(
            installed = true, available = false, granted = false,
            detail = text(R.string.access_manager_server_too_old, "Porter"),
        )

        val clientOld = PrivilegedManager.Incompatible(
            ManagerBackend.SHIZUKU, null, serverTooOld = false, clientTooOld = true,
        )
        status(clientOld, available = false, granted = false).shouldHave(
            installed = true, available = false, granted = false,
            detail = text(R.string.access_manager_client_too_old, "Shizuku"),
        )
    }

    @Test
    fun `only a connected manager can ever be ready`() {
        val neverReady = listOf(
            PrivilegedManager.NotInstalled,
            PrivilegedManager.Unreachable,
            PrivilegedManager.Installed(ManagerBackend.SHIZUKU, "moe.shizuku.privileged.api", connected = false),
            PrivilegedManager.Incompatible(ManagerBackend.PORTER, "eu.darken.porter", serverTooOld = true, clientTooOld = false),
            PrivilegedManager.Incompatible(ManagerBackend.SHIZUKU, null, serverTooOld = false, clientTooOld = true),
        )
        for (manager in neverReady) {
            status(manager, available = true, granted = true).ready shouldBe false
        }
    }
}
