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

    @Test
    fun `an awaiting-replug pending is its own state, never settling, and has no expiry`() {
        val state = ChargingState(pending = PendingRequest(target, now, awaitingReplug = true))
        val display = widgetDisplay(state, sessionActive = false, now = now + SETTLING_WINDOW_MILLIS * 100)
        display.settling shouldBe false
        display.awaitingReplug shouldBe true
        display.steady shouldBe false
    }

    @Test
    fun `an active session wins over an awaiting-replug pending`() {
        val state = ChargingState(pending = PendingRequest(target, now, awaitingReplug = true))
        val display = widgetDisplay(state, sessionActive = true, now = now + 1_000)
        display.sessionActive shouldBe true
        display.steady shouldBe false
    }

    private val richState = ChargingState(
        adapterResolved = true,
        defaultProtectivePolicy = target,
        supportedPolicies = listOf(
            target,
            ChargePolicy.FixedLimit(90),
            ChargePolicy.Adaptive,
            ChargePolicy.Unrestricted,
        ),
    )

    @Test
    fun `an unresolved adapter keeps the legacy buttons`() {
        widgetQuickActions(ChargingState(), storedIds = null) shouldBe null
        // Even a state that already carries policies stays legacy until selection has actually run.
        widgetQuickActions(
            richState.copy(adapterResolved = false),
            storedIds = listOf("adaptive"),
        ) shouldBe null
        widgetQuickActions(richState.copy(defaultProtectivePolicy = null), storedIds = null) shouldBe null
    }

    @Test
    fun `a diagnostics-only device keeps the legacy buttons instead of losing them`() {
        // Lab adapters resolve with an empty supported list — rendering from it would drop both
        // persistent-policy buttons the widget shows today.
        widgetQuickActions(
            richState.copy(supportedPolicies = emptyList()),
            storedIds = listOf("adaptive", "unrestricted"),
        ) shouldBe null
        // Same for an adapter that supports exactly one policy: there is no pair to render.
        widgetQuickActions(
            richState.copy(supportedPolicies = listOf(target)),
            storedIds = null,
        ) shouldBe null
    }

    @Test
    fun `a resolved adapter renders the resolver's answer, regardless of how many policies it has`() {
        widgetQuickActions(richState, storedIds = null) shouldBe listOf(target, ChargePolicy.Unrestricted)
        widgetQuickActions(richState, storedIds = listOf("adaptive", "unrestricted")) shouldBe
            listOf(ChargePolicy.Adaptive, ChargePolicy.Unrestricted)

        // Two-policy adapter: the resolver ignores the stored pick, and the widget still leaves
        // the legacy branch (the buttons now carry their target explicitly).
        val binary = richState.copy(supportedPolicies = listOf(target, ChargePolicy.Unrestricted))
        widgetQuickActions(binary, storedIds = listOf("adaptive")) shouldBe
            listOf(target, ChargePolicy.Unrestricted)
    }

    @Test
    fun `a button's target is decoded from its id, and refused when unreadable`() {
        resolveSetPolicyTarget("fixed:80") shouldBe target
        resolveSetPolicyTarget("unrestricted") shouldBe ChargePolicy.Unrestricted
        resolveSetPolicyTarget(null) shouldBe null
        resolveSetPolicyTarget("") shouldBe null
        resolveSetPolicyTarget("fixed:not-a-number") shouldBe null
    }
}
