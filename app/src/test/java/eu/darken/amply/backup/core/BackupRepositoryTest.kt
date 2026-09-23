package eu.darken.amply.backup.core

import eu.darken.amply.alarm.core.ChargeAlarmConfig
import eu.darken.amply.alarm.core.ChargeAlarmStore
import eu.darken.amply.charging.core.ChargePolicy
import eu.darken.amply.charging.core.ChargingPreferences
import eu.darken.amply.common.AppDataStore
import eu.darken.amply.common.datastore.value
import eu.darken.amply.common.serialization.SerializationModule
import eu.darken.amply.common.theming.ThemeColor
import eu.darken.amply.common.theming.ThemeMode
import eu.darken.amply.common.theming.ThemeSettings
import eu.darken.amply.common.theming.ThemeState
import eu.darken.amply.common.theming.ThemeStyle
import eu.darken.amply.fullcharge.core.FullChargeStore
import eu.darken.amply.rules.core.ChargeRule
import eu.darken.amply.rules.core.ChargeRulesStore
import eu.darken.amply.rules.core.FakeBluetoothSource
import eu.darken.amply.rules.core.FakeChargeGateway
import eu.darken.amply.rules.core.FakeUpgradeRepo
import eu.darken.amply.rules.core.PlugKind
import eu.darken.amply.rules.core.RuleApplier
import eu.darken.amply.rules.core.RuleCondition
import eu.darken.amply.rules.core.RulePhase
import eu.darken.amply.rules.core.RuleRuntimeState
import eu.darken.amply.rules.core.bootCount
import eu.darken.amply.rules.core.testDataStore
import eu.darken.amply.rules.core.testPreferences
import eu.darken.amply.rules.core.testQualificationRunStore
import eu.darken.amply.rules.core.testRulesStore
import eu.darken.amply.stats.core.StatsPreferences
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class BackupRepositoryTest {

    @TempDir
    lateinit var tempDir: File

    private val allParts = listOf(
        BackupPart.THEME,
        BackupPart.CHARGE_ALARM,
        BackupPart.RECONNECT_GESTURE,
        BackupPart.NOTIFICATION_BUTTONS,
        BackupPart.HISTORY_RETENTION,
        BackupPart.RULES,
    )

    private val carRule = ChargeRule(
        id = "car",
        label = "Car",
        condition = RuleCondition.BluetoothDevice(address = "AA:BB:CC:DD:EE:FF", name = "Car kit"),
        policyId = ChargePolicy.Unrestricted.stableId,
    )

    private val wirelessRule = ChargeRule(
        id = "wireless",
        enabled = false,
        label = "Nightstand",
        condition = RuleCondition.ChargerType(setOf(PlugKind.WIRELESS)),
        policyId = ChargePolicy.FixedLimit(80).stableId,
    )

    private class Env(val dataStore: AppDataStore) {
        val json = SerializationModule.json()
        val theme = ThemeSettings(dataStore, json)
        val alarm = ChargeAlarmStore(dataStore, json)
        val fullCharge = FullChargeStore(dataStore, json)
        val stats = StatsPreferences(dataStore)
        val preferences: ChargingPreferences = testPreferences(dataStore)
        val rulesStore: ChargeRulesStore = testRulesStore(dataStore)
        val applier = RuleApplier(
            store = rulesStore,
            gateway = FakeChargeGateway(),
            preferences = preferences,
            upgradeRepo = FakeUpgradeRepo(),
            bluetooth = FakeBluetoothSource(),
            bootCountProvider = bootCount(7),
            qualificationRunStore = testQualificationRunStore(dataStore),
        )
        val repo = BackupRepository(theme, alarm, fullCharge, stats, applier)
    }

    private fun fixture(block: suspend Fixture.() -> Unit) = runBlocking {
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        try {
            Fixture(scope, tempDir).block()
        } finally {
            scope.cancel()
        }
    }

    private class Fixture(private val scope: CoroutineScope, private val dir: File) {
        fun env() = Env(testDataStore(scope, dir))
    }

    private suspend fun Env.seedCustomConfig() {
        theme.state.value(ThemeState(ThemeMode.DARK, ThemeStyle.MEDIUM_CONTRAST, ThemeColor.BLUE))
        alarm.setTargetPercent(90)
        alarm.setEnabled(true)
        fullCharge.setQuickFullChargeEnabled(true)
        fullCharge.setQuickFullChargeAnyLevel(true)
        fullCharge.setGestureNotificationPolicies(
            listOf(ChargePolicy.FixedLimit(90).stableId, ChargePolicy.Unrestricted.stableId),
        )
        stats.setRetentionDays(12)
        applier.addRule(carRule)
        applier.addRule(wirelessRule)
    }

    @Test
    fun `snapshot applied onto a fresh store reproduces every allowlisted value`() = fixture {
        val source = env().apply { seedCustomConfig() }
        val destination = env()

        val backup = source.repo.snapshot(nowMillis = 1234L)
        backup.createdAt shouldBe 1234L
        val result = destination.repo.apply(backup)

        result.applied shouldContainExactly allParts
        result.failed shouldBe null
        result.alarmDisabled shouldBe false
        with(destination) {
            theme.state.value() shouldBe ThemeState(ThemeMode.DARK, ThemeStyle.MEDIUM_CONTRAST, ThemeColor.BLUE)
            alarm.configNow() shouldBe ChargeAlarmConfig(enabled = true, targetPercent = 90)
            fullCharge.isQuickFullChargeEnabled() shouldBe true
            fullCharge.isQuickFullChargeAnyLevel() shouldBe true
            fullCharge.gestureNotificationPolicies.value() shouldBe
                listOf(ChargePolicy.FixedLimit(90).stableId, ChargePolicy.Unrestricted.stableId)
            stats.retentionDaysNow() shouldBe 12
            applier.rulesNow() shouldContainExactly listOf(carRule, wirelessRule)
        }
    }

    @Test
    fun `the default notification selection round-trips as an empty list that restores the default`() = fixture {
        val source = env()
        val destination = env().apply {
            fullCharge.setGestureNotificationPolicies(listOf(ChargePolicy.Adaptive.stableId))
        }

        val backup = source.repo.snapshot()
        backup.settings.reconnectNotificationPolicies shouldBe emptyList()

        destination.repo.apply(backup)
        destination.fullCharge.gestureNotificationPolicies.value() shouldBe null
    }

    @Test
    fun `apply leaves excluded keys untouched`() = fixture {
        val source = env().apply { seedCustomConfig() }
        val destination = env()
        val runtime = RuleRuntimeState(
            phase = RulePhase.ACTIVE,
            targetPolicyId = ChargePolicy.Unrestricted.stableId,
            activeRuleId = "old-rule",
            baselinePolicyId = ChargePolicy.FixedLimit(80).stableId,
        )
        with(destination) {
            fullCharge.startSession(restorePolicy = ChargePolicy.FixedLimit(80), startedAtMillis = 42L, workId = "w1")
            preferences.recordRequested(ChargePolicy.FixedLimit(90), persistent = true, nowMillis = 99L)
            rulesStore.updateRuntime { runtime }
            stats.setCaptureEnabled(true)
        }
        val sessionBefore = destination.fullCharge.currentSession()

        destination.repo.apply(source.repo.snapshot()).failed shouldBe null

        with(destination) {
            fullCharge.currentSession() shouldBe sessionBefore
            preferences.protectivePolicyNow() shouldBe ChargePolicy.FixedLimit(90)
            preferences.lastRequestedNow() shouldBe ChargePolicy.FixedLimit(90)
            preferences.lastRequestedAtNow() shouldBe 99L
            rulesStore.runtimeNow() shouldBe runtime
            stats.isCaptureEnabledNow() shouldBe true
        }
    }

    @Test
    fun `importing without the active rule replaces the rules and leaves the runtime alone`() = fixture {
        val destination = env()
        val staleRule = carRule.copy(id = "stale")
        val runtime = RuleRuntimeState(
            phase = RulePhase.ACTIVE,
            targetPolicyId = ChargePolicy.Unrestricted.stableId,
            activeRuleId = staleRule.id,
            baselinePolicyId = ChargePolicy.FixedLimit(80).stableId,
        )
        destination.applier.addRule(staleRule)
        destination.rulesStore.updateRuntime { runtime }

        destination.repo.apply(AmplyBackup(rules = listOf(wirelessRule)))

        destination.applier.rulesNow() shouldContainExactly listOf(wirelessRule)
        destination.rulesStore.runtimeNow() shouldBe runtime
    }

    @Test
    fun `a theme-only backup changes only the theme`() = fixture {
        val destination = env().apply { seedCustomConfig() }
        val before = destination.repo.snapshot(nowMillis = 0L)
        val theme = ThemeState(ThemeMode.LIGHT, ThemeStyle.DEFAULT, ThemeColor.GREEN)

        val result = destination.repo.apply(AmplyBackup(settings = BackupSettings(theme = theme)))

        result.applied shouldContainExactly listOf(BackupPart.THEME)
        result.failed shouldBe null
        destination.repo.snapshot(nowMillis = 0L) shouldBe before.copy(settings = before.settings.copy(theme = theme))
    }

    @Test
    fun `an empty rule list clears the rules and a null one leaves them`() = fixture {
        val kept = env().apply { seedCustomConfig() }
        kept.repo.apply(AmplyBackup(rules = null)).applied.shouldBeEmpty()
        kept.applier.rulesNow() shouldContainExactly listOf(carRule, wirelessRule)

        val cleared = env().apply { seedCustomConfig() }
        cleared.repo.apply(AmplyBackup(rules = emptyList())).applied shouldContainExactly listOf(BackupPart.RULES)
        cleared.applier.rulesNow().shouldBeEmpty()
    }

    @Test
    fun `a disabled imported alarm clears the fired latch and is reported`() = fixture {
        val destination = env().apply {
            alarm.setEnabled(true)
            alarm.setFiredCycle(true)
        }

        val result = destination.repo.apply(
            AmplyBackup(settings = BackupSettings(chargeAlarm = ChargeAlarmConfig(enabled = false, targetPercent = 70))),
        )

        result.alarmDisabled shouldBe true
        destination.alarm.configNow() shouldBe ChargeAlarmConfig(enabled = false, targetPercent = 70)
        destination.alarm.firedCycle() shouldBe false
    }

    @Test
    fun `an enabled imported alarm is not reported as disabled`() = fixture {
        val destination = env()

        val result = destination.repo.apply(
            AmplyBackup(settings = BackupSettings(chargeAlarm = ChargeAlarmConfig(enabled = true, targetPercent = 85))),
        )

        result.alarmDisabled shouldBe false
        destination.alarm.configNow() shouldBe ChargeAlarmConfig(enabled = true, targetPercent = 85)
    }

    @Test
    fun `setGestureNotificationPolicies stores null for an empty list and the ids verbatim otherwise`() = fixture {
        val store = env().fullCharge
        val ids = listOf(ChargePolicy.Unrestricted.stableId, "not-a-policy", ChargePolicy.Unrestricted.stableId)

        store.setGestureNotificationPolicies(ids)
        store.gestureNotificationPolicies.value() shouldBe ids

        store.setGestureNotificationPolicies(emptyList())
        store.gestureNotificationPolicies.value() shouldBe null
    }
}
