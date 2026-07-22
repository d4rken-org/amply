package eu.darken.amply.main.ui.widget

import eu.darken.amply.charging.core.ChargePolicy
import eu.darken.amply.charging.core.ChargingState
import eu.darken.amply.charging.core.PendingRequest
import eu.darken.amply.charging.core.SETTLING_WINDOW_MILLIS
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class WidgetDisplayTest {

    private val now = 1_000_000L
    private val target = ChargePolicy.FixedLimit(80)

    @Test
    fun `a resting policy with no session is steady`() {
        val display = widgetDisplay(ChargingState(), sessionActive = false, now = now)
        display.sessionActive shouldBe false
        display.settling shouldBe false
        display.steady shouldBe true
    }

    @Test
    fun `a pending request inside the settling window is settling, not steady`() {
        val state = ChargingState(pending = PendingRequest(target, now))
        val display = widgetDisplay(state, sessionActive = false, now = now + 1_000)
        display.settling shouldBe true
        display.steady shouldBe false
    }

    @Test
    fun `an active session is never steady, even while a request is settling`() {
        val state = ChargingState(pending = PendingRequest(target, now))
        val display = widgetDisplay(state, sessionActive = true, now = now + 1_000)
        display.sessionActive shouldBe true
        display.steady shouldBe false
    }

    @Test
    fun `an expired pending is no longer settling and the widget is steady again`() {
        val state = ChargingState(pending = PendingRequest(target, now))
        val display = widgetDisplay(state, sessionActive = false, now = now + SETTLING_WINDOW_MILLIS)
        display.settling shouldBe false
        display.steady shouldBe true
    }
}
