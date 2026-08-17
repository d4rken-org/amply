package eu.darken.amply.rules.ui

import eu.darken.amply.charging.core.ChargePolicy
import eu.darken.amply.rules.core.BondedDevice
import eu.darken.amply.rules.core.PlugKind
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.Test

/**
 * The editor's two pure decisions: what counts as an unsaved change, and which device rows the list
 * shows. Both are worth pinning because their failure modes are silent — a spurious discard
 * confirmation, or a selected device quietly vanishing from a screen that would still save it.
 */
class RuleEditorStateTest {

    private val base = RuleEditorState(
        ruleId = "car",
        label = "Car",
        conditionKind = ConditionKind.BLUETOOTH,
        address = "AA:BB:CC:DD:EE:FF",
        deviceName = "Car audio",
        policy = ChargePolicy.FixedLimit(80),
        supportedPolicies = listOf(ChargePolicy.FixedLimit(80), ChargePolicy.Adaptive),
        bondedDevices = listOf(BondedDevice("AA:BB:CC:DD:EE:FF", "Car audio")),
    )

    @Test
    fun `editing any field the user controls is a change`() {
        val pristine = base.draft()

        base.copy(label = "Car dock").draft() shouldNotBe pristine
        base.copy(conditionKind = ConditionKind.CHARGER).draft() shouldNotBe pristine
        base.copy(address = "11:22:33:44:55:66").draft() shouldNotBe pristine
        base.copy(plugKinds = setOf(PlugKind.AC)).draft() shouldNotBe pristine
        base.copy(policy = ChargePolicy.Adaptive).draft() shouldNotBe pristine
    }

    @Test
    fun `context refreshing underneath the user is not a change`() {
        // All of these arrive from outside while the editor sits open. Counting them as edits would
        // demand a discard confirmation for a change the user never made — the exact way this kind
        // of dialog trains people to dismiss it without reading.
        val pristine = base.draft()

        base.copy(bondedDevices = emptyList()).draft() shouldBe pristine
        base.copy(connectedAddresses = setOf("AA:BB:CC:DD:EE:FF")).draft() shouldBe pristine
        base.copy(freshness = ConnectionFreshness.UNAVAILABLE).draft() shouldBe pristine
        base.copy(bluetoothPermissionMissing = true).draft() shouldBe pristine
        base.copy(supportedPolicies = emptyList()).draft() shouldBe pristine
        base.copy(showDiscardConfirm = true).draft() shouldBe pristine
        // The name travels with the address when a device is picked, but it can also change on its
        // own when the device is renamed in the OS, which is not an edit.
        base.copy(deviceName = "Car stereo").draft() shouldBe pristine
    }

    @Test
    fun `a device that is no longer paired keeps its row and its selection`() {
        val state = base.copy(
            address = "99:88:77:66:55:44",
            deviceName = "Old car",
            bondedDevices = listOf(BondedDevice("AA:BB:CC:DD:EE:FF", "Car audio")),
        )

        val rows = state.deviceRows

        rows.map { it.address } shouldBe listOf("AA:BB:CC:DD:EE:FF", "99:88:77:66:55:44")
        rows.last().selected shouldBe true
        rows.last().unpaired shouldBe true
        rows.last().name shouldBe "Old car"
        // The selection is what Save would keep, so it must never be dropped behind the user's back.
        rows.count { it.selected } shouldBe 1
    }

    @Test
    fun `a paired selected device produces no extra row`() {
        val rows = base.deviceRows

        rows.map { it.address } shouldBe listOf("AA:BB:CC:DD:EE:FF")
        rows.single().selected shouldBe true
        rows.single().unpaired shouldBe false
    }

    @Test
    fun `connected markers need a fresh reading, not just a set`() {
        val connected = base.copy(connectedAddresses = setOf("AA:BB:CC:DD:EE:FF"))

        // A set from a sweep that never completed says nothing about now.
        connected.copy(freshness = ConnectionFreshness.UNKNOWN).deviceRows.single().connected shouldBe false
        connected.copy(freshness = ConnectionFreshness.UNAVAILABLE).deviceRows.single().connected shouldBe false
        connected.copy(freshness = ConnectionFreshness.FRESH).deviceRows.single().connected shouldBe true
    }

    @Test
    fun `address comparisons ignore case`() {
        val state = base.copy(
            address = "aa:bb:cc:dd:ee:ff",
            connectedAddresses = setOf("AA:BB:CC:DD:EE:FF"),
            freshness = ConnectionFreshness.FRESH,
        )

        val row = state.deviceRows.single()
        row.selected shouldBe true
        row.connected shouldBe true
    }
}
