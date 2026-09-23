package eu.darken.amply.backup.core

import eu.darken.amply.alarm.core.ChargeAlarmConfig
import eu.darken.amply.common.theming.ThemeMode
import eu.darken.amply.common.theming.ThemeState
import eu.darken.amply.rules.core.ChargeRule
import eu.darken.amply.rules.core.PlugKind
import eu.darken.amply.rules.core.RuleCondition
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class BackupDecisionsTest {

    private fun rule(id: String, enabled: Boolean = true, policyId: String = "fixed:80") = ChargeRule(
        id = id,
        enabled = enabled,
        label = id,
        condition = RuleCondition.ChargerType(types = setOf(PlugKind.AC)),
        policyId = policyId,
    )

    private fun backup(
        alarm: ChargeAlarmConfig? = null,
        gesture: Boolean? = null,
        rules: List<ChargeRule>? = null,
    ) = AmplyBackup(
        settings = BackupSettings(
            theme = ThemeState(mode = ThemeMode.DARK),
            chargeAlarm = alarm,
            reconnectGestureEnabled = gesture,
            reconnectGestureAnyLevel = true,
            historyRetentionDays = 7,
        ),
        rules = rules,
    )

    @Test
    fun `enabled gesture is downgraded where the destination cannot run it`() {
        val source = backup(gesture = true)

        source.withDestinationCapability(gestureAvailable = false) shouldBe CapabilityOutcome(
            backup = source.copy(settings = source.settings.copy(reconnectGestureEnabled = false)),
            gestureDowngraded = true,
        )
    }

    @Test
    fun `gesture is left alone when available, disabled or absent`() {
        listOf(
            backup(gesture = true) to true,
            backup(gesture = false) to false,
            backup(gesture = null) to false,
            backup(gesture = false) to true,
        ).forEach { (source, available) ->
            source.withDestinationCapability(gestureAvailable = available) shouldBe
                CapabilityOutcome(source, gestureDowngraded = false)
        }
    }

    @Test
    fun `needs notifications for each enabled trigger`() {
        backup(alarm = ChargeAlarmConfig(enabled = true)).needsNotifications() shouldBe true
        backup(gesture = true).needsNotifications() shouldBe true
        backup(rules = listOf(rule("a", enabled = false), rule("b"))).needsNotifications() shouldBe true
    }

    @Test
    fun `needs no notifications when every trigger is off or absent`() {
        backup().needsNotifications() shouldBe false
        backup(
            alarm = ChargeAlarmConfig(enabled = false),
            gesture = false,
            rules = listOf(rule("a", enabled = false)),
        ).needsNotifications() shouldBe false
        backup(rules = emptyList()).needsNotifications() shouldBe false
    }

    @Test
    fun `denied notifications switch every trigger off`() {
        val source = backup(
            alarm = ChargeAlarmConfig(enabled = true, targetPercent = 90),
            gesture = true,
            rules = listOf(rule("a"), rule("b", enabled = false)),
        )

        val denied = source.withNotificationsDenied()

        denied shouldBe source.copy(
            settings = source.settings.copy(
                chargeAlarm = ChargeAlarmConfig(enabled = false, targetPercent = 90),
                reconnectGestureEnabled = false,
            ),
            rules = listOf(rule("a", enabled = false), rule("b", enabled = false)),
        )
        denied.needsNotifications() shouldBe false
    }

    @Test
    fun `denied notifications leave absent fields absent`() {
        val source = backup()

        source.withNotificationsDenied() shouldBe source
    }

    @Test
    fun `unsupported rules include unparseable policy ids`() {
        val rules = listOf(
            rule("a", policyId = "fixed:80"),
            rule("b", policyId = "adaptive"),
            rule("c", policyId = "future_mode"),
            rule("d", policyId = "fixed:999"),
        )

        countUnsupportedRules(rules, supportedPolicyIds = setOf("fixed:80", "unrestricted")) shouldBe 3
        countUnsupportedRules(rules, supportedPolicyIds = setOf("fixed:80", "adaptive")) shouldBe 2
        countUnsupportedRules(emptyList(), supportedPolicyIds = emptySet()) shouldBe 0
    }
}
