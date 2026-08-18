package eu.darken.amply.charging.core.adapter

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.test.core.app.ApplicationProvider
import eu.darken.amply.R
import eu.darken.amply.charging.core.ChargePolicy
import eu.darken.amply.charging.core.DeviceInfo
import eu.darken.amply.charging.core.SettingProbe
import eu.darken.amply.charging.core.access.LineageChargeReadout
import eu.darken.amply.charging.core.access.LineageChargeReader
import eu.darken.amply.charging.core.enforcement.BuildIdentitySource
import eu.darken.amply.charging.core.enforcement.EnforcementEvidence
import eu.darken.amply.charging.core.enforcement.EnforcementEvidenceState
import eu.darken.amply.charging.core.enforcement.EnforcementEvidenceStore
import eu.darken.amply.charging.core.enforcement.EnforcementStatus
import eu.darken.amply.charging.core.enforcement.EnforcementVerdict
import eu.darken.amply.charging.core.enforcement.EnforcementVerdictEngine
import eu.darken.amply.charging.core.qualification.QualificationEvidence
import eu.darken.amply.charging.core.qualification.QualificationEvidenceState
import eu.darken.amply.charging.core.qualification.QualificationOutcomeRecord
import eu.darken.amply.charging.core.qualification.QualificationProtocol
import eu.darken.amply.common.AppDataStore
import eu.darken.amply.common.ca.toCaString
import eu.darken.amply.common.serialization.SerializationModule
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Registry-level selection: the first matched adapter wins, so the Samsung adapters' matched
 * flags must be version-family-specific or they would swallow the lab fallthrough.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AdapterRegistrySelectionTest {

    // Reads aren't exercised at the registry level (only probe); a stub reader suffices, and the test seam
    // supplies a qualified codename so the live-selection/ordering paths stay covered.
    private val stubReader = object : LineageChargeReader {
        override suspend fun readChargeControl() = LineageChargeReadout.Unreadable("unused".toCaString())
    }

    private val registry = AdapterRegistry(
        context = ApplicationProvider.getApplicationContext(),
        lineage = LineageChargingAdapter(stubReader, setOf("oriole")),
        lineageLab = LineageLabAdapter(),
        grapheneOs = GrapheneOsChargingAdapter(),
        pixel = PixelChargingAdapter(),
        samsungModern = SamsungModernChargingAdapter(),
        samsungLegacy = SamsungLegacyChargingAdapter(),
        samsungLab = SamsungLabAdapter(),
        xiaomi = XiaomiChargingAdapter(),
        xiaomiHyperOs3 = XiaomiHyperOs3ChargingAdapter(),
        xiaomiLab = XiaomiLabAdapter(),
        onePlus = OnePlusChargingAdapter(),
        onePlusLab = OnePlusLabAdapter(),
    )

    /**
     * Every case that isn't about the enforcement gate runs with no stored evidence and no started
     * verification — the shipped default on a fresh install.
     */
    private fun select(
        device: DeviceInfo,
        evidence: EnforcementEvidenceState = EnforcementEvidenceState.Absent,
        verificationStarted: Boolean = false,
        qualification: QualificationEvidenceState = QualificationEvidenceState.Absent,
    ) = registry.select(device, evidence, qualification, verificationStarted)

    private fun qualificationPass(
        adapterId: String = "lineageos-chargingcontrol-v1",
        exercisedPolicies: List<String> = listOf(
            ChargePolicy.FixedLimit(70).stableId,
            ChargePolicy.FixedLimit(85).stableId,
        ),
    ) = QualificationEvidenceState.Present(
        QualificationEvidence(
            adapterId = adapterId,
            buildIdentity = "build-a",
            protocolVersion = QualificationProtocol.PROTOCOL_VERSION,
            outcome = QualificationOutcomeRecord.PASSED,
            capPercent = 70,
            exercisedPolicies = exercisedPolicies,
        ),
    )

    private fun lineageEvidence(
        verdict: EnforcementVerdict,
        adapterId: String = "lineageos-chargingcontrol-v1",
    ) = EnforcementEvidenceState.Present(
        EnforcementEvidence(
            adapterId = adapterId,
            buildIdentity = "build-a",
            algorithmVersion = EnforcementVerdictEngine.ALGORITHM_VERSION,
            verdict = verdict,
            capPercent = 80,
            observedPercent = 80,
            observedAtWallMillis = 1_000L,
        ),
    )

    private fun samsung(oneUi: Int?) = DeviceInfo(
        manufacturer = "samsung",
        model = "SM-TEST",
        sdk = 36,
        fingerprint = "test",
        oneUiVersion = oneUi,
        protectBatteryProbe = SettingProbe.PRESENT,
        isSystemUser = true,
    )

    @Test
    fun `one ui 8 selects the modern adapter with control`() {
        val selection = select(samsung(80000))
        selection.adapter?.id shouldBe "samsung-oneui8-v1"
        selection.support.controlEnabled shouldBe true
    }

    @Test
    fun `one ui 4 and 5 select the legacy adapter`() {
        select(samsung(40100)).adapter?.id shouldBe "samsung-legacy-v1"
        select(samsung(50100)).adapter?.id shouldBe "samsung-legacy-v1"
    }

    @Test
    fun `unverified one ui versions fall through to the diagnostics lab adapter`() {
        listOf(30000, 60000, 61000, 70000, 90000, null).forEach { oneUi ->
            val selection = select(samsung(oneUi))
            selection.adapter?.id shouldBe "samsung-lab"
            selection.support.controlEnabled shouldBe false
        }
    }

    @Test
    fun `pixel still selects the pixel adapter`() {
        val selection = select(
            DeviceInfo("Google", "Pixel 8", 36, "test", hasChargingOptimization = true),
        )
        selection.adapter?.id shouldBe "google-pixel-lab-v1"
    }

    private fun graphene(
        packages: Boolean = true,
        key: Boolean = true,
        systemUser: Boolean = true,
    ) = DeviceInfo(
        manufacturer = "Google",
        model = "Pixel 9 Pro XL",
        sdk = 37,
        fingerprint = "test",
        codename = "komodo",
        hasChargingOptimization = false,
        hasGrapheneOsPackages = packages,
        batteryChargeLimitProbe = if (key) SettingProbe.PRESENT else SettingProbe.ABSENT,
        isSystemUser = systemUser,
    )

    @Test
    fun `grapheneos selects its live adapter ahead of the pixel adapter`() {
        // Without the ordering, the Pixel probe (any Google/Pixel*) would swallow the device as a
        // matched-but-diagnostics-only stock Pixel.
        val selection = select(graphene())
        selection.adapter?.id shouldBe "grapheneos-chargelimit-v1"
        selection.support.controlEnabled shouldBe true
    }

    @Test
    fun `grapheneos keeps control without the key probe`() {
        // The real-device shape: @Protected denies the unprivileged probe, so the key always reads
        // absent from app context (issue-#49 beta report) — control must not gate on it.
        val selection = select(graphene(key = false))
        selection.adapter?.id shouldBe "grapheneos-chargelimit-v1"
        selection.support.controlEnabled shouldBe true
        selection.support.contributionWanted shouldBe false
    }

    @Test
    fun `a stock pixel with the key present is not treated as grapheneos`() {
        // Identity is packages-only: the key alone must fall through to the Pixel adapter.
        val selection = select(
            graphene(packages = false).copy(hasChargingOptimization = true),
        )
        selection.adapter?.id shouldBe "google-pixel-lab-v1"
    }

    @Test
    fun `lineageos wins over the graphene adapter for lineage builds on pixel hardware`() {
        // Both are ROM-identity adapters; a LineageOS Pixel carries the Lineage feature and no
        // graphene packages, so ordering only matters for hypothetical both-signal devices — the
        // Lineage pair stays first.
        select(lineageWithDeniedProperty("komodo", "Google")).adapter?.id shouldBe "lineageos-chargingcontrol-v1"
    }

    @Test
    fun `any HyperOS 2 xiaomi selects the live adapter`() {
        val selection = select(
            DeviceInfo("Xiaomi", "2306EPN60G", 35, "test", hyperOsVersion = 2, isSystemUser = true),
        )
        selection.adapter?.id shouldBe "xiaomi-hyperos2-v1"
        selection.support.controlEnabled shouldBe true

        // A different HyperOS 2 model selects the live adapter too (ROM-version gate, not model).
        select(
            DeviceInfo("Xiaomi", "23078PND5G", 35, "test", hyperOsVersion = 2, isSystemUser = true),
        ).adapter?.id shouldBe "xiaomi-hyperos2-v1"
    }

    @Test
    fun `non-HyperOS-2 xiaomi devices fall through to the xiaomi lab adapter`() {
        select(
            DeviceInfo("Xiaomi", "2306EPN60G", 35, "test", hyperOsVersion = 1),
        ).adapter?.id shouldBe "xiaomi-lab"
        // HyperOS 3 without a qualified codename falls through via the hyperos3 allowlist.
        select(
            DeviceInfo("Xiaomi", "2306EPN60G", 35, "test", hyperOsVersion = 3),
        ).adapter?.id shouldBe "xiaomi-lab"
        select(
            DeviceInfo("Xiaomi", "M2101K6G", 33, "test"),
        ).adapter?.id shouldBe "xiaomi-lab"
    }

    @Test
    fun `a qualified HyperOS 3 codename selects the hyperos3 adapter with control`() {
        val selection = select(
            DeviceInfo("Xiaomi", "24117RN76G", 36, "test", codename = "tanzanite", hyperOsVersion = 3, isSystemUser = true),
        )
        selection.adapter?.id shouldBe "xiaomi-hyperos3-v1"
        selection.support.controlEnabled shouldBe true
    }

    @Test
    fun `an unqualified HyperOS 3 codename falls through to the xiaomi lab adapter`() {
        // marblein (HyperOS 3.0.2) carries only the two HyperOS-2-style modes — the reason the
        // hyperos3 gate is a codename allowlist and not version-only.
        select(
            DeviceInfo("Xiaomi", "23049PCD8I", 35, "test", codename = "marblein", hyperOsVersion = 3, isSystemUser = true),
        ).adapter?.id shouldBe "xiaomi-lab"
        // A future HyperOS major falls through too, even on a qualified codename.
        select(
            DeviceInfo("Xiaomi", "24117RN76G", 37, "test", codename = "tanzanite", hyperOsVersion = 4, isSystemUser = true),
        ).adapter?.id shouldBe "xiaomi-lab"
    }

    @Test
    fun `ColorOS 15 oplus devices select the live adapter across the family`() {
        listOf("OnePlus", "OPPO", "realme").forEach { manufacturer ->
            val selection = select(
                DeviceInfo(manufacturer, "CPH2621", 35, "test", oplusRomVersion = 15, isSystemUser = true),
            )
            selection.adapter?.id shouldBe "oplus-coloros15-v1"
            selection.support.controlEnabled shouldBe true
        }
    }

    @Test
    fun `unqualified oplus devices fall through to the oneplus lab adapter`() {
        select(
            DeviceInfo("OnePlus", "CPH2621", 35, "test", oplusRomVersion = 14),
        ).adapter?.id shouldBe "oneplus-lab"
        // No oplus ROM property (older device / non-Oplus that still reports the brand) → lab.
        select(
            DeviceInfo("OnePlus", "CPH2621", 34, "test"),
        ).adapter?.id shouldBe "oneplus-lab"
        select(
            DeviceInfo("realme", "RMX3999", 34, "test"),
        ).adapter?.id shouldBe "oneplus-lab"
    }

    private fun lineage(
        codename: String = "oriole",
        manufacturer: String = "Google",
        provider: Boolean = true,
        systemUser: Boolean = true,
        version: String? = "23.2",
        feature: Boolean = false,
    ) = DeviceInfo(
        manufacturer = manufacturer,
        model = "TEST",
        sdk = 36,
        fingerprint = "test",
        codename = codename,
        lineageOsVersion = version,
        hasLineageFeature = feature,
        hasLineageSettingsProvider = provider,
        isSystemUser = systemUser,
    )

    /**
     * A real LineageOS device as the app actually sees it: `ro.lineage.*` is labelled
     * `custom_version_prop` and denied to `untrusted_app`, so the version reads back null and only the
     * `org.lineageos.android` system feature is observable. Verified on LineageOS 23.2 / Android 16
     * (oriole). Gating on the version alone silently routed these devices to the OEM adapters.
     */
    private fun lineageWithDeniedProperty(
        codename: String = "oriole",
        manufacturer: String = "Google",
    ) = lineage(codename = codename, manufacturer = manufacturer, version = null, feature = true)

    @Test
    fun `a maintainer-qualified lineageos codename keeps control without local evidence`() {
        val selection = select(lineage(codename = "oriole"))
        selection.adapter?.id shouldBe "lineageos-chargingcontrol-v1"
        selection.support.controlEnabled shouldBe true
        selection.support.enforcement shouldBe EnforcementStatus.CONFIRMED
    }

    @Test
    fun `a lineageos device without the provider falls through to the lab adapter`() {
        // There is nothing to write without the provider, so the live adapter no longer matches at all —
        // those devices get the generic lab diagnostics text instead of the old provider-specific note.
        val selection = select(lineage(codename = "oriole", provider = false))
        selection.adapter?.id shouldBe "lineageos-lab"
        selection.support.controlEnabled shouldBe false
        selection.support.enforcement shouldBe null
    }

    @Test
    fun `a secondary user on a qualified lineageos device disables control`() {
        val selection = select(lineage(codename = "oriole", systemUser = false))
        selection.adapter?.id shouldBe "lineageos-chargingcontrol-v1"
        selection.support.controlEnabled shouldBe false
    }

    @Test
    fun `a secondary user is never offered an opt-in that changes nothing`() {
        // The probe's own gate (the keys are device-wide, sessions are per-user) is not something the
        // opt-in can answer: the recorder refuses to observe off the system user and canApply stays
        // false, so accepting the build would enable nothing. Enforcement stays unset, and the
        // specific probe reason survives instead of being replaced by the gate's text.
        listOf(
            EnforcementEvidenceState.Absent,
            EnforcementEvidenceState.Loading,
            EnforcementEvidenceState.Corrupt,
            lineageEvidence(EnforcementVerdict.REFUTED),
        ).forEach { evidence ->
            listOf(false, true).forEach { started ->
                val support = select(
                    lineage(codename = "raven", systemUser = false),
                    evidence = evidence,
                    verificationStarted = started,
                ).support
                support.controlEnabled shouldBe false
                support.enforcement shouldBe null
                support.detail shouldBe R.string.adapter_detail_secondary_user
            }
        }
    }

    @Test
    fun `an unqualified lineageos device is a candidate with control off`() {
        // The device is now reachable by the live adapter (that is the point of the widening), but until
        // enforcement is observed it must not be handed controls that present as protection.
        val selection = select(lineage(codename = "raven")) // Pixel 6 Pro, never qualified
        selection.adapter?.id shouldBe "lineageos-chargingcontrol-v1"
        selection.support.controlEnabled shouldBe false
        selection.support.enforcement shouldBe EnforcementStatus.CANDIDATE
        selection.support.detail shouldBe R.string.adapter_detail_enforcement_candidate
    }

    @Test
    fun `accepting an unconfirmed build enables control without claiming enforcement`() {
        val selection = select(lineage(codename = "raven"), verificationStarted = true)
        selection.support.controlEnabled shouldBe true
        selection.support.enforcement shouldBe EnforcementStatus.UNVERIFIED
    }

    @Test
    fun `only a maintainer qualification can reach the confirmed tier`() {
        // Local observation cannot confirm a cap at all (see EnforcementVerdictEngine), so an
        // unqualified codename never leaves the candidate/unverified tiers however much it is observed.
        select(lineage(codename = "raven")).support.enforcement shouldBe EnforcementStatus.CANDIDATE
        select(lineage(codename = "raven"), verificationStarted = true).support.enforcement shouldBe
            EnforcementStatus.UNVERIFIED
        select(lineage(codename = "oriole")).support.enforcement shouldBe EnforcementStatus.CONFIRMED
    }

    @Test
    fun `a passed run enables control and licenses only what it exercised`() {
        val selection = select(lineage(codename = "raven"), qualification = qualificationPass())

        selection.support.controlEnabled shouldBe true
        selection.support.enforcement shouldBe EnforcementStatus.SELF_QUALIFIED
        selection.support.licensedPolicies shouldBe listOf(
            ChargePolicy.FixedLimit(70),
            ChargePolicy.FixedLimit(85),
        )
    }

    /**
     * A licence naming nothing must not read as "no restriction". An app update that changes a stable
     * id's format without a protocol-version bump leaves a pass whose exercised policies no longer
     * resolve — and granting the tier there would hand back every policy the adapter can write, which
     * is the opposite of what the pass claims.
     */
    @Test
    fun `a pass whose exercised policies no longer parse licenses nothing and grants no tier`() {
        val selection = select(
            lineage(codename = "raven"),
            qualification = qualificationPass(exercisedPolicies = listOf("limit_seventy", "")),
        )

        selection.support.enforcement shouldBe EnforcementStatus.CANDIDATE
        selection.support.controlEnabled shouldBe false
        selection.support.licensedPolicies shouldBe null
    }

    @Test
    fun `a pass on another adapter licenses nothing here`() {
        val selection = select(
            lineage(codename = "raven"),
            qualification = qualificationPass(adapterId = "samsung-oneui8-v1"),
        )

        selection.support.enforcement shouldBe EnforcementStatus.CANDIDATE
        selection.support.licensedPolicies shouldBe null
    }

    @Test
    fun `a refutation disables control and beats everything else`() {
        // Even with the verification opt-in still set, and even on a maintainer-qualified codename:
        // this build was observed charging past the cap it accepted.
        listOf("raven", "oriole").forEach { codename ->
            val selection = select(
                lineage(codename = codename),
                evidence = lineageEvidence(EnforcementVerdict.REFUTED),
                verificationStarted = true,
            )
            selection.adapter?.id shouldBe "lineageos-chargingcontrol-v1"
            selection.support.controlEnabled shouldBe false
            selection.support.enforcement shouldBe EnforcementStatus.REFUTED
            selection.support.contributionWanted shouldBe true
            selection.support.detail shouldBe R.string.adapter_detail_enforcement_refuted
        }
    }

    @Test
    fun `a refutation recorded under the old algorithm version still disables control`() {
        // The regression an algorithm-version bump invites: the user accepted the unconfirmed build
        // (the opt-in is retained across updates), the device was then observed charging past its cap
        // under version 1, and the app updated. Reading that stored refutation as "no evidence" would
        // hand control straight back — through UNVERIFIED, silently — to the exact hardware this gate
        // exists to keep it away from.
        val raw = """
            {"adapterId":"lineageos-chargingcontrol-v1","buildIdentity":"build-a","algorithmVersion":1,
            "verdict":"REFUTED","capPercent":80,"observedPercent":84,"observedAtWallMillis":1000}
        """.trimIndent()
        val storeScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val stored = try {
            val appDataStore = AppDataStore(
                PreferenceDataStoreFactory.create(scope = storeScope) {
                    File(
                        ApplicationProvider.getApplicationContext<Context>().cacheDir,
                        "enforcement-migration-${System.nanoTime()}.preferences_pb",
                    )
                },
            )
            val store = EnforcementEvidenceStore(
                appDataStore,
                object : BuildIdentitySource {
                    override fun current() = "build-a"
                },
                SerializationModule.json(),
            )
            runBlocking {
                appDataStore.store.edit { it[stringPreferencesKey("enforcement.evidence.v1")] = raw }
                store.currentState()
            }
        } finally {
            storeScope.cancel()
        }

        val selection = select(
            lineage(codename = "raven"),
            evidence = stored,
            verificationStarted = true,
        )
        selection.support.controlEnabled shouldBe false
        selection.support.enforcement shouldBe EnforcementStatus.REFUTED
    }

    @Test
    fun `evidence recorded for a different adapter does not apply`() {
        // A refutation of the GrapheneOS adapter says nothing about the Lineage one, so the device
        // stays an ordinary candidate instead of being locked out by someone else's verdict.
        val selection = select(
            lineage(codename = "raven"),
            evidence = lineageEvidence(EnforcementVerdict.REFUTED, adapterId = "grapheneos-chargelimit-v1"),
        )
        selection.support.controlEnabled shouldBe false
        selection.support.enforcement shouldBe EnforcementStatus.CANDIDATE
    }

    @Test
    fun `loading and corrupt evidence both fail closed`() {
        // Loading is "not read yet" and must never be mistaken for "nothing stored"…
        select(lineage(codename = "raven"), evidence = EnforcementEvidenceState.Loading).support.let {
            it.controlEnabled shouldBe false
            it.enforcement shouldBe EnforcementStatus.CANDIDATE
        }
        // …and an undecodable record may be a refutation, so it is treated as one.
        select(
            lineage(codename = "raven"),
            evidence = EnforcementEvidenceState.Corrupt,
            verificationStarted = true,
        ).support.let {
            it.controlEnabled shouldBe false
            it.enforcement shouldBe EnforcementStatus.REFUTED
        }
    }

    @Test
    fun `adapters that need no evidence are untouched by the gate`() {
        listOf(
            EnforcementEvidenceState.Loading,
            EnforcementEvidenceState.Corrupt,
            lineageEvidence(EnforcementVerdict.REFUTED, adapterId = "samsung-oneui8-v1"),
        ).forEach { evidence ->
            val selection = select(samsung(80000), evidence = evidence)
            selection.adapter?.id shouldBe "samsung-oneui8-v1"
            selection.support.controlEnabled shouldBe true
            selection.support.enforcement shouldBe null
        }
    }

    @Test
    fun `a lineageos build on OEM hardware is handled by lineage, never the OEM lab adapter`() {
        // The Lineage adapters precede all OEM adapters, so a custom-ROM build on Samsung/Xiaomi/OnePlus
        // hardware is never swallowed by a manufacturer-based lab adapter.
        select(lineage(codename = "gts9", manufacturer = "samsung")).adapter?.id shouldBe
            "lineageos-chargingcontrol-v1"
        select(lineage(codename = "munch", manufacturer = "Xiaomi")).adapter?.id shouldBe
            "lineageos-chargingcontrol-v1"
        select(lineage(codename = "salami", manufacturer = "OnePlus")).adapter?.id shouldBe
            "lineageos-chargingcontrol-v1"
        // A provider-less derivative still lands on the Lineage lab adapter, not the OEM one.
        select(lineage(codename = "gts9", manufacturer = "samsung", provider = false)).adapter?.id shouldBe
            "lineageos-lab"
    }

    @Test
    fun `a stock device is unaffected by the lineage adapters`() {
        // Neither signal set → both Lineage adapters skip, OEM matching proceeds as before.
        select(samsung(80000)).adapter?.id shouldBe "samsung-oneui8-v1"
    }

    @Test
    fun `a qualified lineageos device is selected when only the system feature is readable`() {
        val selection = select(lineageWithDeniedProperty(codename = "oriole"))
        selection.adapter?.id shouldBe "lineageos-chargingcontrol-v1"
        selection.support.controlEnabled shouldBe true
    }

    @Test
    fun `an unqualified lineageos device is still routed to lineage without the version property`() {
        // The Pixel 6 / LineageOS 23.2 case: previously selected google-pixel-lab-v1, which hid the
        // contribution wizard (contributionWanted defaults false on the Pixel adapter) and pointed
        // "open battery settings" at Battery Saver instead of Battery.
        val selection = select(lineageWithDeniedProperty(codename = "raven"))
        selection.adapter?.id shouldBe "lineageos-chargingcontrol-v1"
        selection.support.enforcement shouldBe EnforcementStatus.CANDIDATE
    }

    @Test
    fun `the guided capture wizard is withheld on lineageos but still offered to OEM lab devices`() {
        // On LineageOS the keys are already mapped and live outside the wizard's capture set, so a guided run
        // always diffs to empty and cannot be delivered — the contribution goes through the direct report.
        val lineage = select(
            lineageWithDeniedProperty(codename = "raven"),
            evidence = lineageEvidence(EnforcementVerdict.REFUTED),
        ).support
        lineage.contributionWanted shouldBe true
        lineage.guidedCaptureUseful shouldBe false

        // An unmapped OEM is the case the wizard exists for; it must be unaffected.
        val samsung = select(samsung(oneUi = null)).support
        samsung.contributionWanted shouldBe true
        samsung.guidedCaptureUseful shouldBe true
    }

    @Test
    fun `lineageos on OEM hardware is never swallowed by an OEM adapter without the version property`() {
        select(lineageWithDeniedProperty("gts9", "samsung")).adapter?.id shouldBe "lineageos-chargingcontrol-v1"
        select(lineageWithDeniedProperty("munch", "Xiaomi")).adapter?.id shouldBe "lineageos-chargingcontrol-v1"
        select(lineageWithDeniedProperty("salami", "OnePlus")).adapter?.id shouldBe "lineageos-chargingcontrol-v1"
        select(lineageWithDeniedProperty("bluejay", "Google")).adapter?.id shouldBe "lineageos-chargingcontrol-v1"
    }
}
