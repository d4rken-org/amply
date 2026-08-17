package eu.darken.amply.rules.core

import eu.darken.amply.charging.core.BackendKind
import eu.darken.amply.charging.core.ChargeObservation
import eu.darken.amply.charging.core.ChargePolicy
import eu.darken.amply.charging.core.ChargingPreferences
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.longs.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * The applier's own responsibilities: crash-safe write ordering, what a failed write leaves behind,
 * when the Bluetooth snapshot is trustworthy, and the session handoff. What to *decide* is
 * `RuleEngineTest`'s job.
 */
class RuleApplierTest {

    @TempDir
    lateinit var tempDir: File

    private val limit80 = ChargePolicy.FixedLimit(80)
    private val limit90 = ChargePolicy.FixedLimit(90)
    private val adaptive = ChargePolicy.Adaptive
    private val carAddress = "AA:BB:CC:DD:EE:FF"

    private class Fixture(
        val applier: RuleApplier,
        val store: ChargeRulesStore,
        val gateway: FakeChargeGateway,
        val bluetooth: FakeBluetoothSource,
        val upgrade: FakeUpgradeRepo,
        val preferences: ChargingPreferences,
        val scope: CoroutineScope,
    )

    private fun fixture(
        bootCountValue: Int? = 7,
        block: suspend Fixture.() -> Unit,
    ) = runBlocking {
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val dataStore = testDataStore(scope, tempDir)
        val store = testRulesStore(dataStore)
        val preferences = testPreferences(dataStore)
        val gateway = FakeChargeGateway()
        val bluetooth = FakeBluetoothSource()
        val upgrade = FakeUpgradeRepo()
        val applier = RuleApplier(
            store = store,
            gateway = gateway,
            preferences = preferences,
            upgradeRepo = upgrade,
            bluetooth = bluetooth,
            bootCountProvider = bootCount(bootCountValue),
        )
        try {
            Fixture(applier, store, gateway, bluetooth, upgrade, preferences, scope).block()
        } finally {
            scope.cancel()
        }
    }

    private fun btRule(id: String = "car", policy: ChargePolicy = adaptive) = ChargeRule(
        id = id,
        label = id,
        condition = RuleCondition.BluetoothDevice(carAddress),
        policyId = policy.stableId,
    )

    private suspend fun Fixture.connect(address: String = carAddress) {
        applier.onBluetoothConnectionChanged(address, connected = true)
    }

    @Test
    fun `the runtime intent is persisted before the policy write`() = fixture {
        applier.addRule(btRule())
        connect()
        // Sampled from inside the write, which is the only way to prove the ordering: a crash here
        // must leave a pending phase and the baseline, not an unrecorded policy change.
        var atWrite: RuleRuntimeState? = null
        gateway.onWrite = { atWrite = store.runtimeNow() }
        gateway.configured = ChargeObservation.Verified(limit90, BackendKind.SHIZUKU)

        applier.evaluate(plugged = false, plugKind = null, sessionActive = false)

        atWrite!!.phase shouldBe RulePhase.APPLY_PENDING
        atWrite!!.targetPolicyId shouldBe adaptive.stableId
        atWrite!!.baselinePolicyId shouldBe limit90.stableId
        atWrite!!.activeRuleId shouldBe "car"
        // Finalized only after the write came back successful.
        store.runtimeNow().phase shouldBe RulePhase.ACTIVE
        store.runtimeNow().lastApplyFailed shouldBe false
        gateway.writes shouldContainExactly listOf(adaptive)
    }

    @Test
    fun `a failed write keeps the pending phase and flags the failure`() = fixture {
        applier.addRule(btRule())
        connect()
        gateway.writeSucceeds = false

        applier.evaluate(plugged = false, plugKind = null, sessionActive = false)

        val runtime = store.runtimeNow()
        runtime.phase shouldBe RulePhase.APPLY_PENDING
        runtime.lastApplyFailed shouldBe true
        runtime.baselinePolicyId shouldBe limit80.stableId

        // The next tick retries; a success clears both the phase and the flag.
        gateway.writeSucceeds = true
        applier.evaluate(plugged = false, plugKind = null, sessionActive = false)

        store.runtimeNow().phase shouldBe RulePhase.ACTIVE
        store.runtimeNow().lastApplyFailed shouldBe false
        gateway.writes shouldContainExactly listOf(adaptive, adaptive)
    }

    @Test
    fun `a successful write stamps the journal's own timestamp, not a fresh clock read`() = fixture {
        // The engine compares these two to spot another component writing past the rules layer, so
        // the stamp must be a COPY. Two independent `now`s could differ by milliseconds and make the
        // layer look overwritten by its own write.
        gateway.journal = preferences
        applier.addRule(btRule())
        connect()

        applier.evaluate(plugged = false, plugKind = null, sessionActive = false)

        val journalAt = preferences.lastRequestedAtNow()
        journalAt shouldBeGreaterThan 0L
        store.runtimeNow().lastWriteAt shouldBe journalAt

        // And the layer must not then read its own write as divergence on the following tick.
        applier.evaluate(plugged = false, plugKind = null, sessionActive = false)
        store.runtimeNow().phase shouldBe RulePhase.ACTIVE
        store.runtimeNow().suspendedRuleIds.shouldBeEmpty()
    }

    @Test
    fun `a connection snapshot from another boot is not evidence`() = fixture(bootCountValue = 8) {
        applier.addRule(btRule())
        // Written during boot 7; this process is in boot 8, so those connections cannot still exist.
        store.updateBtSnapshot { BtConnectionSnapshot(addresses = setOf(carAddress), bootCount = 7) }

        applier.evaluate(plugged = false, plugKind = null, sessionActive = false)

        gateway.writes.shouldBeEmpty()
        store.btSnapshotNow().addresses.shouldBeEmpty()
    }

    @Test
    fun `a missing bluetooth permission reads as nothing connected`() = fixture {
        applier.addRule(btRule())
        connect()
        gateway.configured = ChargeObservation.Verified(limit90, BackendKind.SHIZUKU)
        applier.evaluate(plugged = false, plugKind = null, sessionActive = false)
        store.runtimeNow().phase shouldBe RulePhase.ACTIVE

        // Permission revoked: nothing can observe the device any more, so the rule must let go of
        // the policy rather than hold it on stale evidence.
        bluetooth.permission = false
        applier.evaluate(plugged = false, plugKind = null, sessionActive = false)

        gateway.writes shouldContainExactly listOf(adaptive, limit90)
        store.runtimeNow().phase shouldBe RulePhase.IDLE
    }

    @Test
    fun `reconciliation replaces the snapshot with what the profiles report`() = fixture {
        applier.addRule(btRule())
        connect()
        // The device disconnected while Amply was not running, so the receiver never saw it go.
        bluetooth.live = emptySet()

        applier.evaluate(plugged = false, plugKind = null, sessionActive = false, reconcileBluetooth = true)

        store.btSnapshotNow().addresses.shouldBeEmpty()
        gateway.writes.shouldBeEmpty()
    }

    @Test
    fun `a UI reconcile persists the swept set and reports it fresh`() = fixture {
        // No enabled Bluetooth rule exists yet — an editor filling in its first condition still has
        // to get a real answer, so this path must not take the evaluation path's short-circuit.
        connect("11:22:33:44:55:66")
        bluetooth.live = setOf(carAddress)

        // The swept set comes back with the answer, so a caller can adopt it and declare it fresh in
        // one step instead of waiting for the store's flow to catch up.
        applier.reconcileBluetoothForUi()?.addresses shouldBe setOf(carAddress)

        store.btSnapshotNow().addresses shouldBe setOf(carAddress)
    }

    @Test
    fun `a UI reconcile that cannot sweep reports unavailable and keeps the snapshot`() = fixture {
        connect()
        // All-or-nothing: the source answers null when any profile fails to report.
        bluetooth.live = null

        applier.reconcileBluetoothForUi() shouldBe null

        // Untouched, so the receiver-built set stays on screen rather than a fabricated empty one.
        store.btSnapshotNow().addresses shouldBe setOf(carAddress)
    }

    @Test
    fun `a UI reconcile without permission answers empty, and answers it confidently`() = fixture {
        connect()
        bluetooth.permission = false

        // Not a failure: with no permission "nothing is observable" is the same answer the
        // evaluation path acts on, so the editor may state it rather than showing it as unknown.
        val resolved = applier.reconcileBluetoothForUi()

        resolved shouldNotBe null
        resolved!!.addresses.shouldBeEmpty()
        store.btSnapshotNow().addresses.shouldBeEmpty()
    }

    @Test
    fun `a lapsed entitlement blocks the write entirely`() = fixture {
        applier.addRule(btRule())
        connect()
        upgrade.pro = false

        applier.evaluate(plugged = false, plugKind = null, sessionActive = false)

        gateway.writes.shouldBeEmpty()
        store.runtimeNow().phase shouldBe RulePhase.IDLE
    }

    @Test
    fun `suspending the cohort drops rule ownership without a write`() = fixture {
        applier.addRule(btRule("car"))
        applier.addRule(btRule("car2"))
        applier.addRule(
            ChargeRule(
                id = "desk",
                label = "desk",
                condition = RuleCondition.BluetoothDevice("11:22:33:44:55:66"),
                policyId = limit90.stableId,
            ),
        )
        connect()
        applier.evaluate(plugged = false, plugKind = null, sessionActive = false)
        store.runtimeNow().phase shouldBe RulePhase.ACTIVE
        gateway.writes.clear()

        applier.suspendMatchingCohort(plugged = false, plugKind = null)

        val runtime = store.runtimeNow()
        // Every matching rule, not just the winner — the runner-up would otherwise re-apply at once.
        runtime.suspendedRuleIds shouldBe setOf("car", "car2")
        runtime.phase shouldBe RulePhase.IDLE
        // Adopting the user's write means NOT restoring: their choice is the intended end state.
        gateway.writes.shouldBeEmpty()

        // And no rule may take the policy back while a suspended one still matches.
        applier.evaluate(plugged = false, plugKind = null, sessionActive = false)
        gateway.writes.shouldBeEmpty()
    }

    @Test
    fun `the session handoff reads the baseline and only then drops ownership`() = fixture {
        applier.addRule(btRule())
        connect()
        gateway.configured = ChargeObservation.Verified(limit90, BackendKind.SHIZUKU)
        applier.evaluate(plugged = false, plugKind = null, sessionActive = false)

        // The session is handed the user's real baseline, not the rule's override.
        applier.readActiveBaseline() shouldBe limit90

        applier.clearActiveAfterSessionPersist()

        applier.readActiveBaseline() shouldBe null
        store.runtimeNow().phase shouldBe RulePhase.IDLE
        // No write: the session owns the policy now.
        gateway.writes shouldContainExactly listOf(adaptive)
    }

    @Test
    fun `an active session leaves the rules layer alone`() = fixture {
        applier.addRule(btRule())
        connect()

        applier.evaluate(plugged = false, plugKind = null, sessionActive = true)

        gateway.writes.shouldBeEmpty()
        store.runtimeNow().phase shouldBe RulePhase.IDLE
    }

    @Test
    fun `the service is required while a rule is enabled or work is owed`() = fixture {
        applier.isServiceRequired() shouldBe false

        applier.addRule(btRule())
        applier.isServiceRequired() shouldBe true

        applier.setRuleEnabled("car", false)
        applier.isServiceRequired() shouldBe false

        // A suspension cohort still has to be watched for its rules to stop matching.
        store.updateRuntime { it.copy(suspendedRuleIds = setOf("car")) }
        applier.isServiceRequired() shouldBe true
    }

    @Test
    fun `reordering moves a rule one position and clamps at the ends`() = fixture {
        applier.addRule(btRule("a"))
        applier.addRule(btRule("b"))
        applier.addRule(btRule("c"))

        applier.moveRule("c", up = true)
        store.rulesNow().map { it.id } shouldContainExactly listOf("a", "c", "b")

        applier.moveRule("a", up = true)
        store.rulesNow().map { it.id } shouldContainExactly listOf("a", "c", "b")

        applier.moveRule("a", up = false)
        store.rulesNow().map { it.id } shouldContainExactly listOf("c", "a", "b")
    }
}
