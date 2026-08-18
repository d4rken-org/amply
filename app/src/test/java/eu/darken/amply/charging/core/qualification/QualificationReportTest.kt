package eu.darken.amply.charging.core.qualification

import eu.darken.amply.charging.core.ChargePolicy
import eu.darken.amply.charging.core.DeviceInfo
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test

class QualificationReportTest {

    private val device = DeviceInfo(
        manufacturer = "Google",
        model = "Pixel 6",
        sdk = 36,
        fingerprint = "google/oriole/oriole:16/BP4A.251205.006/1234:user/release-keys",
        codename = "oriole",
        lineageOsVersion = null,
        hasLineageFeature = true,
        oneUiVersion = null,
        hyperOsVersion = null,
        oplusRomVersion = null,
    )

    private fun record(
        phaseLog: List<PhaseRecord> = listOf(
            PhaseRecord(
                phase = RunPhase.CUT_1,
                commanded = "fixed:70",
                enteredAtWallMillis = 1_000L,
                entryPercent = 80,
                entryCounter = 3_200_000,
                exitAtWallMillis = 721_000L,
                exitPercent = 80,
                exitCounter = 3_201_000,
            ),
        ),
    ) = QualificationRunRecord(
        baseline = ChargePolicy.FixedLimit(80),
        runId = "run-1",
        runToken = "token-1",
        adapterId = "lineageos-chargingcontrol-v1",
        buildIdentity = "abc123",
        protocolVersion = QualificationProtocol.PROTOCOL_VERSION,
        shape = RunShape.VARIABLE_CAP,
        signal = FlowSignal.COUNTER,
        lowCap = 70,
        releasePolicy = ChargePolicy.FixedLimit(85),
        observedHoldPercent = 80,
        runStartedAtWallMillis = 1_000L,
        phaseLog = phaseLog,
    )

    private fun report(terminal: RunTerminal = RunTerminal.Passed, rec: QualificationRunRecord = record()) =
        buildQualificationReport(
            record = rec,
            terminal = terminal,
            device = device,
            buildIdentity = "abc123",
            appVersion = "0.4.0-beta0",
            androidRelease = "16",
            brand = "google",
            createdAtEpochMs = 2_000_000L,
        )

    @Test
    fun `the report carries the codename an allowlist entry needs`() {
        val text = formatQualificationReport(report())

        text shouldContain "device=oriole"
        text shouldContain "adapter=lineageos-chargingcontrol-v1"
        text shouldContain "build_identity=abc123"
    }

    @Test
    fun `the outcome is spelled out for every terminal`() {
        RunTerminal.Passed.reportId() shouldBe "passed"
        RunTerminal.Refuted.reportId() shouldBe "refuted"
        RunTerminal.Inconclusive(InconclusiveReason.NO_RESUME).reportId() shouldBe "inconclusive:no_resume"
        RunTerminal.Aborted(AbortReason.UNPLUGGED).reportId() shouldBe "aborted:unplugged"
    }

    @Test
    fun `a refuted run still produces a report`() {
        val text = formatQualificationReport(report(terminal = RunTerminal.Refuted))

        text shouldContain "outcome=refuted"
    }

    @Test
    fun `the measurement context is present so a reader can judge the result`() {
        val text = formatQualificationReport(report())

        text shouldContain "run_shape=variable_cap"
        text shouldContain "accumulation_signal=counter"
        text shouldContain "cap_percent=70"
        text shouldContain "release_policy=fixed:85"
        text shouldContain "observed_hold_percent=80"
        text shouldContain "protocol_version=${QualificationProtocol.PROTOCOL_VERSION}"
    }

    @Test
    fun `the phase table lists each phase with its entry and exit readings`() {
        val text = formatQualificationReport(report())

        text shouldContain "CUT_1 | fixed:70 | 80%/3200000 | 80%/3201000 | 720000"
    }

    @Test
    fun `a run that ended before any phase completed says so instead of showing an empty table`() {
        val text = formatQualificationReport(
            report(terminal = RunTerminal.Aborted(AbortReason.UNPLUGGED), rec = record(phaseLog = emptyList())),
        )

        text shouldContain "(the run ended before completing a phase)"
    }

    @Test
    fun `no raw settings values are emitted, only the policies Amply commanded`() {
        val text = formatQualificationReport(report())

        // The Lineage provider keys are what a settings-diff report would carry; this one must not.
        text shouldNotContain "charging_control_enabled"
        text shouldNotContain "charging_control_mode"
        text shouldNotContain "charging_control_charging_limit"
    }

    @Test
    fun `the issue title identifies the device and the outcome`() {
        qualificationIssueTitle(report()) shouldBe "[Qualification] Google Pixel 6 (oriole): passed"
    }

    @Test
    fun `the issue body wraps the report in a fenced block`() {
        val body = qualificationIssueBody(report())

        body shouldContain "```"
        body shouldContain "qualification_schema=${QualificationReport.QUALIFICATION_SCHEMA}"
    }

    @Test
    fun `the issue url is encoded`() {
        val url = qualificationIssueUrl(report())

        url shouldContain "?title="
        url shouldContain "&body="
        url shouldNotContain " "
    }

    @Test
    fun `report values stay on one line`() {
        val messy = record().copy(adapterId = "line\nbreak\tadapter")

        val text = formatQualificationReport(report(rec = messy))

        text.lineSequence().count { it.startsWith("adapter=") } shouldBe 1
    }
}
