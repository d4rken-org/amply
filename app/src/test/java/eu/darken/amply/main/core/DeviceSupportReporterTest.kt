package eu.darken.amply.main.core

import eu.darken.amply.charging.core.access.LineageChargingProvider
import eu.darken.amply.charging.core.access.LineageHealthSummary
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.string.shouldStartWith
import org.junit.jupiter.api.Test
import java.net.URLDecoder

class DeviceSupportReporterTest {

    private fun report(
        manufacturer: String = "Samsung",
        model: String = "SM-S911B",
        adapterId: String? = "samsung-lab",
        isLineageOs: Boolean = false,
        isGrapheneOs: Boolean = false,
        hasBatteryChargeLimit: Boolean = false,
        lineageHealth: LineageHealthSummary? = null,
    ) = DeviceSupportReport(
        manufacturer = manufacturer,
        brand = "samsung",
        model = model,
        device = "dm3q",
        product = "dm3qxxx",
        fingerprint = "samsung/dm3qxxx/dm3q:14/UP1A/x:user/release-keys",
        sdkInt = 34,
        release = "14",
        isPhone = true,
        hasChargingOptimization = false,
        oneUiVersion = 61000,
        hyperOsVersion = null,
        oplusRomVersion = null,
        lineageOsVersion = null,
        isLineageOs = isLineageOs,
        isGrapheneOs = isGrapheneOs,
        lineageHealth = lineageHealth,
        hasProtectBattery = true,
        hasBatteryChargeLimit = hasBatteryChargeLimit,
        hasLineageSettingsProvider = false,
        adapterId = adapterId,
        adapterMatched = adapterId != null,
        adapterControlEnabled = false,
        contributionWanted = true,
        batteryChargingStatus = 1,
        batteryPlugged = true,
        appVersionName = "0.1.0-beta1",
        appVersionCode = 100010,
        flavor = "foss",
        buildType = "debug",
    )

    @Test
    fun `format is deterministic and schema-tagged`() {
        val text = formatReport(report())
        text shouldStartWith "Amply device-support request"
        text shouldContain "report_schema=9"
        text shouldContain "manufacturer=Samsung"
        text shouldContain "model=SM-S911B"
        text shouldContain "one_ui_version=61000"
        text shouldContain "hyperos_version=none"
        text shouldContain "oplus_rom_version=none"
        text shouldContain "is_lineageos=false"
        text shouldContain "lineageos_version=none"
        text shouldContain "is_grapheneos=false"
        text shouldContain "has_battery_charge_limit=false"
        text shouldContain "has_protect_battery=true"
        text shouldContain "has_lineage_settings_provider=false"
        text shouldContain "adapter=samsung-lab"
        text shouldContain "contribution_wanted=true"
        // Same input twice must produce byte-identical output.
        formatReport(report()) shouldBe text
    }

    @Test
    fun `a lineageos report is identifiable even though the version property is unreadable`() {
        // The real-device shape: ro.lineage.* is SELinux-denied, so the version is absent. Triage must still
        // be able to tell it is a LineageOS build, otherwise the report looks like stock.
        val text = formatReport(report(isLineageOs = true))
        text shouldContain "is_lineageos=true"
        text shouldContain "lineageos_version=none"
    }

    @Test
    fun `a native-limit lineageos device reports that mechanism`() {
        val text = formatReport(
            report(
                isLineageOs = true,
                lineageHealth = LineageHealthSummary(LineageChargingProvider.LIMIT, mode = 3),
            ),
        )
        text shouldContain "lineage_cc_provider=LIMIT"
        text shouldContain "lineage_cc_mode=3"
        text shouldContain "lineage_cc_limit_mechanism=NATIVE_LIMIT"
    }

    @Test
    fun `deadline in auto mode is reported as not observed, not as a rejection`() {
        // The pairing matters: upstream returns Deadline for MODE_AUTO before ever checking Limit, so this
        // must not read as "no limit support" — the maintainer needs to know to ask for a re-run in limit mode.
        val text = formatReport(
            report(
                isLineageOs = true,
                lineageHealth = LineageHealthSummary(LineageChargingProvider.DEADLINE, mode = 1),
            ),
        )
        text shouldContain "lineage_cc_provider=DEADLINE"
        text shouldContain "lineage_cc_mode=1"
        text shouldContain "lineage_cc_limit_mechanism=NOT_OBSERVED"
    }

    @Test
    fun `an unprobed device reports unknown rather than a negative`() {
        // No Shizuku, or not LineageOS: absence of the probe must never read as "no limit support".
        val text = formatReport(report())
        text shouldContain "lineage_cc_provider=unknown"
        text shouldContain "lineage_cc_limit_mechanism=UNKNOWN"
    }

    @Test
    fun `a grapheneos report carries identity and key presence`() {
        // The triage-relevant pairing: identity without the key means the wizard should discover it;
        // identity with the key means the live gate should have engaged.
        val text = formatReport(report(isGrapheneOs = true, hasBatteryChargeLimit = true))
        text shouldContain "is_grapheneos=true"
        text shouldContain "has_battery_charge_limit=true"
    }

    @Test
    fun `missing adapter renders as none`() {
        formatReport(report(adapterId = null)) shouldContain "adapter=none"
    }

    @Test
    fun `sanitize collapses control characters to a single space`() {
        sanitizeReportValue("line1\r\nline2\tend") shouldBe "line1 line2 end"
        sanitizeReportValue("  padded  ") shouldBe "padded"
        sanitizeReportValue(null) shouldBe ""
    }

    @Test
    fun `sanitize caps length with an ellipsis`() {
        sanitizeReportValue("x".repeat(50), max = 10) shouldBe "xxxxxxxxxx…"
    }

    @Test
    fun `issue url targets the repo, omits labels, and round-trips title and body`() {
        val r = report(manufacturer = "Föö", model = "A&B #1 100%+x")
        val url = issueUrl(r)

        url shouldStartWith "https://github.com/d4rken-org/amply/issues/new?title="
        url shouldContain "&body="
        url shouldNotContain "labels="

        val titlePart = url.substringAfter("?title=").substringBefore("&body=")
        val bodyPart = url.substringAfter("&body=")
        URLDecoder.decode(titlePart, Charsets.UTF_8.name()) shouldBe issueTitle(r)
        URLDecoder.decode(bodyPart, Charsets.UTF_8.name()) shouldBe issueBody(r)
    }

    @Test
    fun `body fences the report so special characters cannot break markdown`() {
        val r = report(model = "A&B #1")
        val decodedBody = URLDecoder.decode(
            issueUrl(r).substringAfter("&body="),
            Charsets.UTF_8.name(),
        )
        decodedBody shouldContain "```"
        decodedBody shouldContain formatReport(r)
    }

    @Test
    fun `empty model still yields a clean title`() {
        issueTitle(report(manufacturer = "Nothing", model = "")) shouldBe "[Device support] Nothing"
    }

    @Test
    fun `newlines in values survive encoding without breaking the query`() {
        // A CR/LF that slipped past sanitization must still encode to a single query value.
        val url = issueUrl(report(model = "bad\nmodel"))
        url shouldNotContain "\n"
        url.count { it == '?' } shouldBe 1
    }
}
