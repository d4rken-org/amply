package eu.darken.amply.main.ui.dashboard

import eu.darken.amply.charging.core.ChargeObservation
import eu.darken.amply.charging.core.ChargingState
import eu.darken.amply.charging.core.access.AccessSnapshot
import eu.darken.amply.charging.core.access.BackendStatus
import eu.darken.amply.common.ca.toCaString
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class DashboardAccessMonitorTest {

    private fun status(ready: Boolean) =
        BackendStatus(available = ready, granted = ready, detail = "x".toCaString())

    private fun monitor(
        onboardingComplete: Boolean? = true,
        onDashboard: Boolean = true,
        observation: ChargeObservation = ChargeObservation.NeedsSetup("setup".toCaString()),
        directReady: Boolean = false,
        shizukuReady: Boolean = false,
    ) = shouldMonitorAccess(
        state = DashboardUiState(
            onboardingComplete = onboardingComplete,
            charging = ChargingState(
                observation = observation,
                access = AccessSnapshot(direct = status(directReady), shizuku = status(shizukuReady)),
            ),
        ),
        onDashboard = onDashboard,
    )

    @Test
    fun `polls while the setup card is up awaiting WSS`() {
        monitor() shouldBe true
    }

    @Test
    fun `Shizuku ready but WSS missing still polls for an external adb grant`() {
        // Gating on canControl would wrongly stop here; the setup card stays up until direct WSS is ready.
        monitor(shizukuReady = true) shouldBe true
    }

    @Test
    fun `stops once durable WSS is granted`() {
        monitor(directReady = true) shouldBe false
    }

    @Test
    fun `does not poll on unsupported devices`() {
        // canControl never flips here, so an activity-wide poll would run forever.
        monitor(observation = ChargeObservation.Unsupported("nope".toCaString())) shouldBe false
    }

    @Test
    fun `does not poll during onboarding`() {
        monitor(onboardingComplete = false) shouldBe false
        monitor(onboardingComplete = null) shouldBe false
    }

    @Test
    fun `does not poll off the dashboard`() {
        monitor(onDashboard = false) shouldBe false
    }

    @Test
    fun `polls during initial load before the first access snapshot`() {
        shouldMonitorAccess(
            state = DashboardUiState(
                onboardingComplete = true,
                charging = ChargingState(observation = ChargeObservation.Unknown("loading".toCaString())),
            ),
            onDashboard = true,
        ) shouldBe true
    }
}
