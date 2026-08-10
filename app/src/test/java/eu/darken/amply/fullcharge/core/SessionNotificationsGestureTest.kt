package eu.darken.amply.fullcharge.core

import android.app.Application
import android.app.Notification
import android.content.ComponentName
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import eu.darken.amply.R
import eu.darken.amply.charging.core.ChargePolicy
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SessionNotificationsGestureTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun notification(
        decision: QuickFullChargeDecision,
        protectPolicy: ChargePolicy = ChargePolicy.FixedLimit(80),
        anyLevel: Boolean = false,
        limitPercent: Int? = null,
    ): Notification = SessionNotifications.gesture(
        context,
        decision = decision,
        protectPolicy = protectPolicy,
        anyLevel = anyLevel,
        limitPercent = limitPercent,
    )

    private fun actions(
        decision: QuickFullChargeDecision,
        protectPolicy: ChargePolicy = ChargePolicy.FixedLimit(80),
    ): List<Notification.Action> = notification(decision, protectPolicy).actions?.toList().orEmpty()

    private fun targetPolicyOf(action: Notification.Action): String? = shadowOf(action.actionIntent)
        .savedIntent
        .getStringExtra(ChargeSessionService.EXTRA_TARGET_POLICY)

    private fun text(
        decision: QuickFullChargeDecision,
        anyLevel: Boolean = false,
        limitPercent: Int? = null,
    ): String? = notification(decision, anyLevel = anyLevel, limitPercent = limitPercent)
        .extras
        .getCharSequence(Notification.EXTRA_TEXT)
        ?.toString()

    private fun bigText(
        decision: QuickFullChargeDecision,
        anyLevel: Boolean = false,
        limitPercent: Int? = null,
    ): String? = notification(decision, anyLevel = anyLevel, limitPercent = limitPercent)
        .extras
        .getCharSequence(Notification.EXTRA_BIG_TEXT)
        ?.toString()

    @Test
    fun `the three gesture states render three different texts for the same inputs`() {
        val idle = text(QuickFullChargeDecision.IDLE, limitPercent = 80)
        val armed = text(QuickFullChargeDecision.ARMED, limitPercent = 80)
        val waiting = text(QuickFullChargeDecision.WAITING_FOR_RECONNECT, limitPercent = 80)

        setOf(idle, armed, waiting).size shouldBe 3
    }

    @Test
    fun `armed distinguishes any level from a named limit and a generic hold`() {
        val anyLevel = text(QuickFullChargeDecision.ARMED, anyLevel = true)
        val limit = text(QuickFullChargeDecision.ARMED, limitPercent = 80)
        val holding = text(QuickFullChargeDecision.ARMED)

        anyLevel shouldNotBe limit
        anyLevel shouldNotBe holding
        limit shouldNotBe holding
        limit!! shouldContain "80%"
    }

    @Test
    fun `any level takes precedence over a known limit percent in both steady states`() {
        text(QuickFullChargeDecision.ARMED, anyLevel = true, limitPercent = 80) shouldBe
            text(QuickFullChargeDecision.ARMED, anyLevel = true)
        text(QuickFullChargeDecision.IDLE, anyLevel = true, limitPercent = 80) shouldBe
            text(QuickFullChargeDecision.IDLE, anyLevel = true)
    }

    @Test
    fun `armed at a known limit names no percentage once any level qualifies`() {
        // The device sitting at its holding limit is the common state: the caller must pass the
        // condition the gesture fires under, so the any-level copy must not name the limit there.
        val atLimitAnyLevel = text(QuickFullChargeDecision.ARMED, anyLevel = true, limitPercent = 80)
        val atLimitOnly = text(QuickFullChargeDecision.ARMED, limitPercent = 80)

        atLimitAnyLevel shouldBe
            context.getString(R.string.gesture_notification_armed_any_level)
        atLimitOnly shouldBe
            context.getString(R.string.gesture_notification_armed_limit, 80)
        atLimitAnyLevel!! shouldNotContain "80%"
    }

    @Test
    fun `the reconnect countdown ignores the arming basis`() {
        val plain = text(QuickFullChargeDecision.WAITING_FOR_RECONNECT)

        text(QuickFullChargeDecision.WAITING_FOR_RECONNECT, anyLevel = true) shouldBe plain
        text(QuickFullChargeDecision.WAITING_FOR_RECONNECT, limitPercent = 80) shouldBe plain
    }

    @Test
    fun `every state but the countdown carries the disable hint`() {
        val hint = context.getString(R.string.gesture_notification_disable_hint)

        bigText(QuickFullChargeDecision.IDLE)!! shouldContain hint
        bigText(QuickFullChargeDecision.ARMED)!! shouldContain hint
        bigText(QuickFullChargeDecision.WAITING_FOR_RECONNECT) shouldBe null
    }

    @Test
    fun `only the standing states offer the mode actions`() {
        actions(QuickFullChargeDecision.IDLE).size shouldBe 2
        actions(QuickFullChargeDecision.ARMED).size shouldBe 2
        actions(QuickFullChargeDecision.WAITING_FOR_RECONNECT).size shouldBe 0
        actions(QuickFullChargeDecision.TRIGGER).size shouldBe 0
    }

    @Test
    fun `the protect action is labelled after the adapter's protective default`() {
        actions(QuickFullChargeDecision.IDLE, ChargePolicy.FixedLimit(80))[0].title.toString() shouldBe
            context.getString(R.string.gesture_notification_action_protect_fixed, 80)
        actions(QuickFullChargeDecision.IDLE, ChargePolicy.Adaptive)[0].title.toString() shouldBe
            context.getString(R.string.gesture_notification_action_protect_adaptive)
        actions(QuickFullChargeDecision.IDLE, ChargePolicy.PauseAtFull)[0].title.toString() shouldBe
            context.getString(R.string.gesture_notification_action_protect)
    }

    @Test
    fun `the protect action targets the passed policy and the other one always full`() {
        targetPolicyOf(actions(QuickFullChargeDecision.IDLE, ChargePolicy.FixedLimit(80))[0]) shouldBe "fixed:80"
        targetPolicyOf(actions(QuickFullChargeDecision.IDLE, ChargePolicy.Adaptive)[0]) shouldBe "adaptive"
        targetPolicyOf(actions(QuickFullChargeDecision.IDLE)[1]) shouldBe "unrestricted"
    }

    @Test
    fun `both actions are distinct foreground-service intents for the session service`() {
        val (protect, alwaysFull) = actions(QuickFullChargeDecision.ARMED)

        listOf(protect, alwaysFull).forEach { action ->
            val shadow = shadowOf(action.actionIntent)
            shadow.isForegroundService shouldBe true
            shadow.savedIntent.component shouldBe
                ComponentName(context, ChargeSessionService::class.java)
            shadow.savedIntent.action shouldBe ChargeSessionService.ACTION_SET_PERSISTENT_POLICY
        }
        // Extras don't factor into PendingIntent equality — only the distinct request codes keep
        // the second action from overwriting the first one's target.
        protect.actionIntent shouldNotBe alwaysFull.actionIntent
    }

    @Test
    fun `sending an action starts the session service with its policy`() {
        val application: Application = ApplicationProvider.getApplicationContext()
        val (protect, alwaysFull) = actions(QuickFullChargeDecision.IDLE, ChargePolicy.Adaptive)
        shadowOf(application).clearStartedServices()

        protect.actionIntent.send()
        val protectStart = shadowOf(application).nextStartedService
        protectStart.component shouldBe ComponentName(context, ChargeSessionService::class.java)
        protectStart.action shouldBe ChargeSessionService.ACTION_SET_PERSISTENT_POLICY
        protectStart.getStringExtra(ChargeSessionService.EXTRA_TARGET_POLICY) shouldBe "adaptive"

        alwaysFull.actionIntent.send()
        val alwaysFullStart = shadowOf(application).nextStartedService
        alwaysFullStart.action shouldBe ChargeSessionService.ACTION_SET_PERSISTENT_POLICY
        alwaysFullStart.getStringExtra(ChargeSessionService.EXTRA_TARGET_POLICY) shouldBe "unrestricted"
    }
}
