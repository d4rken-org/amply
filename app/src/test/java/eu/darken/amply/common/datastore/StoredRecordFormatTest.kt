package eu.darken.amply.common.datastore

import eu.darken.amply.alarm.core.ChargeAlarmConfig
import eu.darken.amply.charging.core.ChargePolicy
import eu.darken.amply.common.serialization.SerializationModule
import eu.darken.amply.common.theming.ThemeColor
import eu.darken.amply.common.theming.ThemeMode
import eu.darken.amply.common.theming.ThemeState
import eu.darken.amply.common.theming.ThemeStyle
import eu.darken.amply.fullcharge.core.ChargeSessionRecord
import eu.darken.amply.fullcharge.core.InterruptionEvent
import eu.darken.amply.fullcharge.core.InterruptionOutcome
import eu.darken.amply.fullcharge.core.InterruptionReason
import eu.darken.amply.fullcharge.core.RecoveryRecord
import eu.darken.amply.fullcharge.core.WorkProvenance
import eu.darken.amply.main.core.QuickAccessState
import eu.darken.amply.rules.core.BtConnectionSnapshot
import eu.darken.amply.rules.core.ChargeRule
import eu.darken.amply.rules.core.ChargeRuleSet
import eu.darken.amply.rules.core.PlugKind
import eu.darken.amply.rules.core.RuleCondition
import eu.darken.amply.rules.core.RulePhase
import eu.darken.amply.rules.core.RuleRuntimeState
import eu.darken.amply.rules.core.decodeRuleRuntimeState
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test

/**
 * These records are a **stored format**, not just internal types: they outlive the process that
 * wrote them. Pinning the exact JSON here means a rename, a reordered enum or a dropped default
 * fails this test instead of silently resetting a user's charge session, owed restore or theme to
 * defaults on the next launch.
 */
class StoredRecordFormatTest {

    private val json: Json = SerializationModule.json()

    private val provenance = WorkProvenance(token = "tok-a", pid = 42, bootCount = 7, createdAtMillis = 1_000L)

    @Test
    fun `session record encodes to the pinned shape`() {
        val record = ChargeSessionRecord(
            restorePolicy = ChargePolicy.FixedLimit(80),
            startedAtMillis = 1_000L,
            connectedSeen = true,
            provenance = provenance,
            workId = "wid-1",
        )

        json.encodeToString(ChargeSessionRecord.serializer(), record) shouldBe
            """{"restorePolicy":"fixed:80","startedAtMillis":1000,"connectedSeen":true,""" +
            """"provenance":{"token":"tok-a","pid":42,"bootCount":7,"createdAtMillis":1000},""" +
            """"workId":"wid-1","overrideAwaitingReplug":false}"""
    }

    @Test
    fun `session record with replug-grace fields encodes to the pinned shape`() {
        val record = ChargeSessionRecord(
            restorePolicy = ChargePolicy.FixedLimit(80),
            startedAtMillis = 1_000L,
            connectedSeen = true,
            disconnectedAtMillis = 2_000L,
            overrideAwaitingReplug = true,
        )

        json.encodeToString(ChargeSessionRecord.serializer(), record) shouldBe
            """{"restorePolicy":"fixed:80","startedAtMillis":1000,"connectedSeen":true,""" +
            """"disconnectedAtMillis":2000,"overrideAwaitingReplug":true}"""
    }

    /** A record from a build before the replug grace window must decode with the grace fields off. */
    @Test
    fun `pre-grace session records decode with grace defaults`() {
        val stored = """{"restorePolicy":"fixed:80","startedAtMillis":1000,"connectedSeen":true}"""

        val decoded = json.decodeFromString(ChargeSessionRecord.serializer(), stored)
        decoded.disconnectedAtMillis shouldBe null
        decoded.overrideAwaitingReplug shouldBe false
    }

    @Test
    fun `session record decodes from stored JSON`() {
        val stored = """{"restorePolicy":"adaptive","startedAtMillis":5,"connectedSeen":false}"""

        json.decodeFromString(ChargeSessionRecord.serializer(), stored) shouldBe ChargeSessionRecord(
            restorePolicy = ChargePolicy.Adaptive,
            startedAtMillis = 5L,
            connectedSeen = false,
            provenance = null,
            workId = null,
        )
    }

    @Test
    fun `recovery record encodes to the pinned shape`() {
        val record = RecoveryRecord(
            target = ChargePolicy.Unrestricted,
            workId = "wid-r",
            provenance = provenance.copy(bootCount = null),
        )

        // Pinned literally, not round-tripped: encode-then-decode with the same serializer stays
        // green through a @SerialName rename that would orphan every already-stored record.
        json.encodeToString(RecoveryRecord.serializer(), record) shouldBe
            """{"target":"unrestricted","workId":"wid-r",""" +
            """"provenance":{"token":"tok-a","pid":42,"createdAtMillis":1000}}"""
    }

    @Test
    fun `recovery record decodes from stored JSON`() {
        val stored = """{"target":"fixed:90","workId":"wid-r"}"""

        json.decodeFromString(RecoveryRecord.serializer(), stored) shouldBe RecoveryRecord(
            target = ChargePolicy.FixedLimit(90),
            workId = "wid-r",
            provenance = null,
        )
    }

    @Test
    fun `every charge policy has a pinned wire value`() {
        val expected = mapOf(
            ChargePolicy.Unrestricted to "unrestricted",
            ChargePolicy.Adaptive to "adaptive",
            ChargePolicy.PauseAtFull to "pause_at_full",
            ChargePolicy.FixedLimit(80) to "fixed:80",
            ChargePolicy.FixedLimit(95) to "fixed:95",
            ChargePolicy.FixedLimit(100) to "fixed:100",
        )

        expected.forEach { (policy, wire) ->
            json.encodeToString(RecoveryRecord.serializer(), RecoveryRecord(target = policy)) shouldBe
                """{"target":"$wire"}"""
            json.decodeFromString(RecoveryRecord.serializer(), """{"target":"$wire"}""").target shouldBe policy
        }
    }

    /**
     * Everything except the policy must be optional. Provenance is diagnostic metadata; letting a
     * missing `pid` collapse the record would drop a restore Amply still owes the user and leave the
     * battery charging unrestricted.
     */
    @Test
    fun `records survive missing auxiliary metadata`() {
        val recovery = """{"target":"fixed:80","provenance":{"token":"old","bootCount":7}}"""
        json.decodeFromString(RecoveryRecord.serializer(), recovery) shouldBe RecoveryRecord(
            target = ChargePolicy.FixedLimit(80),
            workId = null,
            provenance = WorkProvenance(token = "old", pid = -1, bootCount = 7, createdAtMillis = 0L),
        )

        val session = """{"restorePolicy":"adaptive"}"""
        json.decodeFromString(ChargeSessionRecord.serializer(), session) shouldBe ChargeSessionRecord(
            restorePolicy = ChargePolicy.Adaptive,
            startedAtMillis = 0L,
            connectedSeen = false,
        )
    }

    @Test
    fun `interruption enums encode by name`() {
        val event = InterruptionEvent(
            occurredAtMillis = 10L,
            reason = InterruptionReason.USER_STOPPED,
            outcome = InterruptionOutcome.RESTORED_LATE,
            workId = "wid-1",
        )

        json.encodeToString(InterruptionEvent.serializer(), event) shouldBe
            """{"occurredAtMillis":10,"reason":"USER_STOPPED","outcome":"RESTORED_LATE","workId":"wid-1"}"""
    }

    @Test
    fun `theme state encodes by enum name`() {
        val state = ThemeState(
            mode = ThemeMode.DARK,
            style = ThemeStyle.HIGH_CONTRAST,
            color = ThemeColor.BLUE,
        )

        json.encodeToString(ThemeState.serializer(), state) shouldBe
            """{"mode":"DARK","style":"HIGH_CONTRAST","color":"BLUE"}"""
    }

    @Test
    fun `absent fields fall back to defaults`() {
        json.decodeFromString(ThemeState.serializer(), "{}") shouldBe ThemeState()
        json.decodeFromString(QuickAccessState.serializer(), "{}") shouldBe QuickAccessState()
        json.decodeFromString(ChargeAlarmConfig.serializer(), "{}") shouldBe ChargeAlarmConfig()
    }

    @Test
    fun `charge rules encode to the pinned shape, with an explicit condition discriminator`() {
        val set = ChargeRuleSet(
            rules = listOf(
                ChargeRule(
                    id = "car",
                    label = "Car dock",
                    condition = RuleCondition.BluetoothDevice("AA:BB:CC:DD:EE:FF", "Car"),
                    policyId = ChargePolicy.Unrestricted.stableId,
                ),
                ChargeRule(
                    id = "desk",
                    enabled = false,
                    condition = RuleCondition.ChargerType(setOf(PlugKind.AC, PlugKind.DOCK)),
                    policyId = ChargePolicy.FixedLimit(80).stableId,
                ),
            ),
        )

        json.encodeToString(ChargeRuleSet.serializer(), set) shouldBe
            """{"rules":[""" +
            """{"id":"car","enabled":true,"label":"Car dock",""" +
            """"condition":{"type":"bluetooth","address":"AA:BB:CC:DD:EE:FF","name":"Car"},""" +
            """"policyId":"unrestricted"},""" +
            """{"id":"desk","enabled":false,"label":"",""" +
            """"condition":{"type":"charger","types":["AC","DOCK"]},""" +
            """"policyId":"fixed:80"}]}"""
    }

    @Test
    fun `the rules runtime encodes to the pinned shape`() {
        val runtime = RuleRuntimeState(
            phase = RulePhase.ACTIVE,
            targetPolicyId = "unrestricted",
            activeRuleId = "car",
            baselinePolicyId = "fixed:80",
            suspendedRuleIds = setOf("desk"),
            lastWriteAt = 1_700L,
        )

        json.encodeToString(RuleRuntimeState.serializer(), runtime) shouldBe
            """{"phase":"ACTIVE","targetPolicyId":"unrestricted","activeRuleId":"car",""" +
            """"baselinePolicyId":"fixed:80","suspendedRuleIds":["desk"],"lastApplyFailed":false,""" +
            """"lastWriteAt":1700}"""

        json.encodeToString(RuleRuntimeState.serializer(), RuleRuntimeState()) shouldBe
            """{"phase":"IDLE","suspendedRuleIds":[],"lastApplyFailed":false,"lastWriteAt":0}"""
    }

    @Test
    fun `the bluetooth snapshot encodes to the pinned shape`() {
        json.encodeToString(
            BtConnectionSnapshot.serializer(),
            BtConnectionSnapshot(addresses = setOf("AA:BB:CC:DD:EE:FF"), bootCount = 7),
        ) shouldBe """{"addresses":["AA:BB:CC:DD:EE:FF"],"bootCount":7}"""

        json.encodeToString(BtConnectionSnapshot.serializer(), BtConnectionSnapshot()) shouldBe
            """{"addresses":[]}"""
    }

    /**
     * The runtime carries **owed restore work** — the user's own policy a rule replaced. One
     * unreadable field must never take the baseline with it, or the battery stays on the rule's
     * policy with nothing left that knows to put it back.
     */
    @Test
    fun `the rules runtime decodes field by field`() {
        // A phase name from a future build, alongside a perfectly good baseline.
        decodeRuleRuntimeState("""{"phase":"SOMETHING_NEW","activeRuleId":"car","baselinePolicyId":"fixed:90"}""")
            .let {
                // Conservative: still owning the policy is the only reading that cannot lose work.
                it.phase shouldBe RulePhase.ACTIVE
                it.baselinePolicyId shouldBe "fixed:90"
            }

        // Wrong types for the auxiliary fields; the transition itself survives intact.
        decodeRuleRuntimeState(
            """{"phase":"RESTORE_PENDING","targetPolicyId":"adaptive",""" +
                """"suspendedRuleIds":"nope","lastApplyFailed":"yes","lastWriteAt":"soon"}""",
        ).let {
            it.phase shouldBe RulePhase.RESTORE_PENDING
            it.targetPolicyId shouldBe "adaptive"
            it.suspendedRuleIds shouldBe emptySet()
            it.lastApplyFailed shouldBe false
            // 0 reads as "never written by the rules layer", which only ever makes the divergence
            // check more conservative — it cannot claim a write it has no timestamp for.
            it.lastWriteAt shouldBe 0L
        }

        decodeRuleRuntimeState(
            """{"phase":"ACTIVE","activeRuleId":"car","lastWriteAt":1700}""",
        ).lastWriteAt shouldBe 1_700L

        // Only unparseable JSON loses the whole record.
        decodeRuleRuntimeState("not json at all") shouldBe RuleRuntimeState()
        decodeRuleRuntimeState(null) shouldBe RuleRuntimeState()
        // Nothing recorded at all reads as idle, not as a phantom activation.
        decodeRuleRuntimeState("{}") shouldBe RuleRuntimeState()
    }

    @Test
    fun `unknown fields from a newer build are ignored`() {
        val stored = """{"mode":"LIGHT","futureSetting":{"deeply":["nested"]}}"""

        json.decodeFromString(ThemeState.serializer(), stored) shouldBe ThemeState(mode = ThemeMode.LIGHT)
    }
}
