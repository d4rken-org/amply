package eu.darken.amply.charging.core.adapter

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import eu.darken.amply.charging.core.DeviceInfo
import eu.darken.amply.charging.core.SettingProbe
import eu.darken.amply.charging.core.access.LineageChargeReadout
import eu.darken.amply.charging.core.access.LineageChargeReader
import eu.darken.amply.charging.core.enforcement.EnforcementEvidence
import eu.darken.amply.charging.core.enforcement.EnforcementEvidenceState
import eu.darken.amply.charging.core.enforcement.EnforcementVerdict
import eu.darken.amply.charging.core.enforcement.EnforcementVerdictEngine
import eu.darken.amply.common.ca.toCaString
import io.kotest.matchers.shouldBe
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ContributionEligibilityTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val stubReader = object : LineageChargeReader {
        override suspend fun readChargeControl() = LineageChargeReadout.Unreadable("unused".toCaString())
    }
    private val registry = AdapterRegistry(
        context = context,
        lineage = LineageChargingAdapter(stubReader),
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

    /** Nothing here is about the enforcement gate, so every case runs with the fresh-install default. */
    private fun select(
        device: DeviceInfo,
        evidence: EnforcementEvidenceState = EnforcementEvidenceState.Absent,
        verificationStarted: Boolean = false,
    ) = registry.select(device, evidence, verificationStarted)

    private fun device(manufacturer: String, model: String = "X", sdk: Int = 35) = DeviceInfo(
        manufacturer = manufacturer,
        model = model,
        sdk = sdk,
        fingerprint = "fp",
        isPhone = true,
        hasChargingOptimization = true,
    )

    @Test
    fun `unknown OEM is a wanted contribution`() {
        val support = select(device("Sony")).support
        support.matched shouldBe false
        support.contributionWanted shouldBe true
    }

    @Test
    fun `unqualified xiaomi is matched by the lab adapter and wants contributions`() {
        val support = select(device("Xiaomi")).support
        support.matched shouldBe true
        support.contributionWanted shouldBe true
    }

    @Test
    fun `samsung and oplus family diagnostics-only adapters want contributions`() {
        // No version signal → the live adapters don't match; the lab adapters handle these.
        select(device("Samsung")).support.contributionWanted shouldBe true
        select(device("OnePlus")).support.contributionWanted shouldBe true
        select(device("Oppo")).support.contributionWanted shouldBe true
        select(device("realme")).support.contributionWanted shouldBe true
    }

    @Test
    fun `supported pixel does not solicit a contribution`() {
        val support = select(device("Google", model = "Pixel 8")).support
        support.matched shouldBe true
        support.contributionWanted shouldBe false
    }

    @Test
    fun `grapheneos never solicits a contribution regardless of the key probe`() {
        // The unprivileged key probe is @Protected-denied on real GrapheneOS, so its result carries
        // no information; the keys are fully mapped and nothing is left to discover.
        val ready = device("Google", model = "Pixel 9 Pro XL", sdk = 37)
            .copy(
                hasChargingOptimization = false,
                hasGrapheneOsPackages = true,
                batteryChargeLimitProbe = SettingProbe.PRESENT,
            )
        select(ready).support.contributionWanted shouldBe false
        select(ready.copy(batteryChargeLimitProbe = SettingProbe.ABSENT))
            .support.contributionWanted shouldBe false
    }

    @Test
    fun `an unverified lineageos device is not a contribution target, a refuted one is`() {
        val device = device("Google", model = "Pixel 6").copy(
            codename = "raven",
            hasLineageFeature = true,
            hasLineageSettingsProvider = true,
        )
        // Nothing to report yet: the device just hasn't been verified, and the dashboard asks the user
        // to run the verification instead.
        select(device).support.contributionWanted shouldBe false
        // A build that accepted the limit and charged past it anyway is exactly what a maintainer wants.
        val refuted = EnforcementEvidenceState.Present(
            EnforcementEvidence(
                adapterId = "lineageos-chargingcontrol-v1",
                buildIdentity = "build-a",
                algorithmVersion = EnforcementVerdictEngine.ALGORITHM_VERSION,
                verdict = EnforcementVerdict.REFUTED,
                capPercent = 80,
                observedPercent = 86,
                observedAtWallMillis = 1_000L,
            ),
        )
        select(device, evidence = refuted).support.contributionWanted shouldBe true
    }

    @Test
    fun `gated pixel is still not a contribution target`() {
        // Old Pixel model: matched by the Pixel adapter but control-gated — a known limitation,
        // not a new device to add support for.
        val support = select(device("Google", model = "Pixel 4", sdk = 33)).support
        support.contributionWanted shouldBe false
    }
}
