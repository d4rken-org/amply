package eu.darken.amply.diagnostics.core

import eu.darken.amply.charging.core.DeviceInfo
import eu.darken.amply.charging.core.access.SettingNamespace
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test

class ContributionReportTest {

    private fun secure(key: String) = SettingId(SettingNamespace.SECURE, key)
    private fun global(key: String) = SettingId(SettingNamespace.GLOBAL, key)
    private fun obs(label: String, vararg pairs: Pair<SettingId, String>) =
        ModeObservation(label, 0L, pairs.toMap())

    private val device = DeviceInfo(manufacturer = "Samsung", model = "SM-X210", sdk = 36, fingerprint = "fp")

    private fun report(
        session: RawWizardSession,
        approved: Set<SettingId> = emptySet(),
        adapterId: String? = "samsung-lab",
    ) = buildReviewedReport(
        session = session,
        approvedRedacted = approved,
        device = device,
        appVersion = "1.0",
        adapterId = adapterId,
        romVersion = "One UI 8",
        featureName = "Protect battery",
        effects = emptyList(),
        notes = "",
        createdAtEpochMs = 0L,
    )

    @Test
    fun `unchanged keys are excluded from the matrix`() {
        val matrix = deriveMatrix(
            listOf(
                obs("off", secure("x") to "same", global("protect_battery") to "0"),
                obs("on", secure("x") to "same", global("protect_battery") to "1"),
            ),
        )
        matrix.map { it.id } shouldContainExactly listOf(global("protect_battery"))
    }

    @Test
    fun `known charge key with in-domain values auto-discloses`() {
        deriveMatrix(
            listOf(obs("off", global("protect_battery") to "0"), obs("max", global("protect_battery") to "1")),
        ).single().disclosure shouldBe Disclosure.AUTO
    }

    @Test
    fun `known key with an unexpected value stays redacted`() {
        deriveMatrix(
            listOf(obs("a", global("protect_battery") to "0"), obs("b", global("protect_battery") to "9")),
        ).single().disclosure shouldBe Disclosure.REDACTED
    }

    @Test
    fun `unknown key is redacted`() {
        deriveMatrix(
            listOf(
                obs("a", secure("lock_screen_owner_info") to "hi"),
                obs("b", secure("lock_screen_owner_info") to "bye"),
            ),
        ).single().disclosure shouldBe Disclosure.REDACTED
    }

    @Test
    fun `redacted rows are absent from the report unless approved`() {
        val text = formatContributionReport(
            report(
                RawWizardSession(
                    listOf(
                        obs("a", global("protect_battery") to "0", secure("lock_screen_owner_info") to "SECRET-A"),
                        obs("b", global("protect_battery") to "1", secure("lock_screen_owner_info") to "SECRET-B"),
                    ),
                ),
            ),
        )
        text shouldContain "protect_battery"
        text shouldNotContain "lock_screen_owner_info"
        text shouldNotContain "SECRET"
    }

    @Test
    fun `approved redacted row is included`() {
        val id = secure("some_unknown_key")
        val built = report(RawWizardSession(listOf(obs("a", id to "v1"), obs("b", id to "v2"))), approved = setOf(id))
        formatContributionReport(built) shouldContain "some_unknown_key = v1 | v2"
        built.withheldRowCount shouldBe 0
    }

    @Test
    fun `multiline values are collapsed to one line`() {
        val id = secure("charge_optimization_mode")
        // "1\nINJECTED" is out of domain → redacted; approve it so we can inspect the sanitized output.
        val built = report(RawWizardSession(listOf(obs("a", id to "0"), obs("b", id to "1\nINJECTED"))), approved = setOf(id))
        formatContributionReport(built) shouldNotContain "\nINJECTED"
    }

    @Test
    fun `issue body fence is longer than any backtick run in content`() {
        val id = secure("weird")
        val built = report(RawWizardSession(listOf(obs("a", id to "```"), obs("b", id to "````"))), approved = setOf(id))
        contributionIssueBody(built) shouldContain "`````" // 5 backticks > the 4-run in content
    }

    @Test
    fun `small report yields a launchable url`() {
        val delivery = contributionIssueDelivery(
            report(RawWizardSession(listOf(obs("off", global("protect_battery") to "0"), obs("max", global("protect_battery") to "1")))),
        )
        delivery.shouldBeInstanceOf<IssueDelivery.Url>().url shouldContain "/issues/new?title="
    }

    @Test
    fun `oversize report reports too large`() {
        val ids = (0 until 500).map { secure("candidate_key_$it") }
        val session = RawWizardSession(
            listOf(
                ModeObservation("a", 0L, ids.associateWith { "0" }),
                ModeObservation("b", 0L, ids.associateWith { "1" }),
            ),
        )
        contributionIssueDelivery(report(session, approved = ids.toSet()))
            .shouldBeInstanceOf<IssueDelivery.TooLarge>()
    }
}
