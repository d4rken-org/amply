package eu.darken.amply.fullcharge.core

import android.app.Notification
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import eu.darken.amply.R
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SessionNotificationsGestureTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun text(
        decision: QuickFullChargeDecision,
        anyLevel: Boolean = false,
        limitPercent: Int? = null,
    ): String? = SessionNotifications
        .gesture(context, decision = decision, anyLevel = anyLevel, limitPercent = limitPercent)
        .extras
        .getCharSequence(Notification.EXTRA_TEXT)
        ?.toString()

    private fun bigText(
        decision: QuickFullChargeDecision,
        anyLevel: Boolean = false,
        limitPercent: Int? = null,
    ): String? = SessionNotifications
        .gesture(context, decision = decision, anyLevel = anyLevel, limitPercent = limitPercent)
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
}
