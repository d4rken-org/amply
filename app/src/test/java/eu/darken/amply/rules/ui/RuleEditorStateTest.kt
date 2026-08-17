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
    fun `an accepted save takes the Save control out of play`() {
        // The draft is still perfectly saveable — that is the point: without the second condition a
        // second tap during the write would create a second rule under a second id, because the
        // applier's mutex can be held for seconds by a Bluetooth sweep.
        base.canSave shouldBe true
        base.canSaveNow shouldBe true

        val saving = base.copy(isSaving = true)
        saving.canSave shouldBe true
        saving.canSaveNow shouldBe false
    }

    @Test
    fun `an incomplete draft cannot be saved either way`() {
        val noPolicy = base.copy(policy = null)
        noPolicy.canSave shouldBe false
        noPolicy.canSaveNow shouldBe false

        val noDevice = base.copy(address = null)
        noDevice.canSaveNow shouldBe false

        // A charger condition needs at least one type, or it would be a rule that matches nothing.
        val noChargerType = base.copy(conditionKind = ConditionKind.CHARGER, plugKinds = emptySet())
        noChargerType.canSaveNow shouldBe false
        noChargerType.copy(plugKinds = setOf(PlugKind.AC)).canSaveNow shouldBe true
    }

    @Test
    fun `saving is not an edit`() {
        // Otherwise accepting a save would itself mark the draft dirty.
        base.copy(isSaving = true).draft() shouldBe base.draft()
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
    fun `only a newer snapshot revision may replace the displayed set`() {
        // The sweep's point read and the store's flow deliver snapshots by different routes and are
        // not ordered against each other, so without this an older read arriving second would
        // overwrite a newer set — permanently, until the next Bluetooth event.
        val applied = base.withSnapshot(revision = 5, addresses = setOf("AA:BB:CC:DD:EE:FF"))

        applied.connectedAddresses shouldBe setOf("AA:BB:CC:DD:EE:FF")
        applied.appliedSnapshotRevision shouldBe 5L

        // Older loses.
        applied.withSnapshot(revision = 4, addresses = emptySet()) shouldBe applied
        // Equal loses too: it is the same write, arriving twice by two routes.
        applied.withSnapshot(revision = 5, addresses = emptySet()) shouldBe applied

        val newer = applied.withSnapshot(revision = 6, addresses = setOf("11:22:33:44:55:66"))
        newer.connectedAddresses shouldBe setOf("11:22:33:44:55:66")
        newer.appliedSnapshotRevision shouldBe 6L
    }

    @Test
    fun `a legacy revision-0 snapshot is still adoptable`() {
        // A snapshot persisted before the revision field existed decodes as 0 and KEEPS that
        // revision while its content is unchanged (an unchanged sweep does not bump it). The
        // tracker therefore starts below 0, or connected devices on an upgraded install would
        // never show their marker despite a successful sweep.
        val applied = base.withSnapshot(revision = 0, addresses = setOf("AA:BB:CC:DD:EE:FF"))

        applied.connectedAddresses shouldBe setOf("AA:BB:CC:DD:EE:FF")
        applied.appliedSnapshotRevision shouldBe 0L
    }

    @Test
    fun `adopting a snapshot is not an edit`() {
        // Otherwise a device connecting while the editor is open would demand a discard confirmation.
        base.withSnapshot(revision = 3, addresses = setOf("AA:BB:CC:DD:EE:FF")).draft() shouldBe base.draft()
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
