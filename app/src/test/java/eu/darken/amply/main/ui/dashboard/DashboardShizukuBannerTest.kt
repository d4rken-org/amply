package eu.darken.amply.main.ui.dashboard

import android.app.Application
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import eu.darken.amply.R
import eu.darken.amply.charging.core.BackendKind
import eu.darken.amply.charging.core.ChargeObservation
import eu.darken.amply.charging.core.ChargePolicy
import eu.darken.amply.charging.core.ChargingState
import eu.darken.amply.charging.core.access.AccessSnapshot
import eu.darken.amply.charging.core.access.BackendStatus
import eu.darken.amply.charging.core.access.shizuku.ManagerBackend
import eu.darken.amply.common.ca.toCaString
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The banner is one tap target for one action, and which action that is depends on whether the manager is
 * already running. It names the detected manager, or "Shizuku or Porter" when none was identified. The
 * tall qualifier renders the whole list so the banner — which ends it — is composed.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "+h2400dp")
class DashboardShizukuBannerTest {
    @get:Rule
    val compose = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<Application>()

    private fun string(res: Int, vararg args: Any): String = context.getString(res, *args)

    private val anyManager get() = string(R.string.manager_name_any)

    private var opened = 0
    private var allowed = 0

    // A Shizuku-only adapter (OnePlus/ColorOS): control is enabled but writes need Shizuku or Porter,
    // which is not connected — the branch that renders the "… required" banner.
    private fun render(shizukuRunning: Boolean, manager: ManagerBackend? = null) {
        compose.setContent {
            DashboardScreenUnderTest(
                state = DashboardUiState(
                    onboardingComplete = true,
                    charging = ChargingState(
                        controlEnabled = true,
                        writeRequiresShizuku = true,
                        syncVerification = true,
                        access = AccessSnapshot(
                            direct = BackendStatus(
                                available = true,
                                granted = true,
                                detail = "granted".toCaString(),
                            ),
                            shizuku = BackendStatus(
                                available = shizukuRunning,
                                granted = false,
                                detail = "not connected".toCaString(),
                                installed = shizukuRunning || manager != null,
                                manager = manager,
                            ),
                        ),
                        observation = ChargeObservation.Verified(
                            ChargePolicy.FixedLimit(80),
                            BackendKind.DIRECT_WSS,
                        ),
                    ),
                ),
                onOpenShizuku = { opened++ },
                onAllowShizuku = { allowed++ },
            )
        }
    }

    private fun tapBanner(name: String) =
        compose.onNodeWithText(string(R.string.dashboard_shizuku_required_body, name)).performClick()

    @Test
    fun `with no manager identified the card opens the setup and names both`() {
        render(shizukuRunning = false)
        compose.onNodeWithText(string(R.string.dashboard_shizuku_required_title, anyManager)).assertExists()
        compose.onNodeWithText(string(R.string.dashboard_shizuku_open, anyManager)).assertExists()
        compose.onNodeWithText("Open Shizuku or Porter").assertExists()
        tapBanner(anyManager)
        compose.runOnIdle {
            opened shouldBe 1
            allowed shouldBe 0
        }
    }

    @Test
    fun `with Porter installed but not running the card opens Porter`() {
        render(shizukuRunning = false, manager = ManagerBackend.PORTER)
        compose.onNodeWithText("Porter required to change charging").assertExists()
        compose.onNodeWithText("Open Porter").assertExists()
        tapBanner("Porter")
        compose.runOnIdle {
            opened shouldBe 1
            allowed shouldBe 0
        }
    }

    @Test
    fun `with Shizuku running the card asks for access`() {
        render(shizukuRunning = true, manager = ManagerBackend.SHIZUKU)
        compose.onNodeWithText("Shizuku required to change charging").assertExists()
        compose.onNodeWithText(string(R.string.dashboard_shizuku_allow)).assertExists()
        tapBanner("Shizuku")
        compose.runOnIdle {
            allowed shouldBe 1
            opened shouldBe 0
        }
    }
}
