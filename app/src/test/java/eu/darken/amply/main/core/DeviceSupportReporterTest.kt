package eu.darken.amply.main.core

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
        miuiVersionCode = null,
        hasProtectBattery = true,
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
        text shouldContain "report_schema=4"
        text shouldContain "manufacturer=Samsung"
        text shouldContain "model=SM-S911B"
        text shouldContain "one_ui_version=61000"
        text shouldContain "miui_version_code=none"
        text shouldContain "has_protect_battery=true"
        text shouldContain "adapter=samsung-lab"
        text shouldContain "contribution_wanted=true"
        // Same input twice must produce byte-identical output.
        formatReport(report()) shouldBe text
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
