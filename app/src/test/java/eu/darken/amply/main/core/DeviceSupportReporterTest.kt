package eu.darken.amply.main.core

import com.google.common.truth.Truth.assertThat
import org.junit.Test
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
        adapterId = adapterId,
        adapterMatched = adapterId != null,
        adapterControlEnabled = false,
        contributionWanted = true,
        adapterDetail = "Detected for diagnostics only; no unverified writes are exposed",
        batteryChargingStatus = 1,
        batteryPlugged = true,
        appVersionName = "0.1.0-spike1",
        appVersionCode = 10,
        flavor = "foss",
        buildType = "debug",
    )

    @Test
    fun `format is deterministic and schema-tagged`() {
        val text = formatReport(report())
        assertThat(text).startsWith("Amply device-support request")
        assertThat(text).contains("report_schema=1")
        assertThat(text).contains("manufacturer=Samsung")
        assertThat(text).contains("model=SM-S911B")
        assertThat(text).contains("adapter=samsung-lab")
        assertThat(text).contains("contribution_wanted=true")
        // Same input twice must produce byte-identical output.
        assertThat(formatReport(report())).isEqualTo(text)
    }

    @Test
    fun `missing adapter renders as none`() {
        assertThat(formatReport(report(adapterId = null))).contains("adapter=none")
    }

    @Test
    fun `sanitize collapses control characters to a single space`() {
        assertThat(sanitizeReportValue("line1\r\nline2\tend")).isEqualTo("line1 line2 end")
        assertThat(sanitizeReportValue("  padded  ")).isEqualTo("padded")
        assertThat(sanitizeReportValue(null)).isEqualTo("")
    }

    @Test
    fun `sanitize caps length with an ellipsis`() {
        assertThat(sanitizeReportValue("x".repeat(50), max = 10)).isEqualTo("xxxxxxxxxx…")
    }

    @Test
    fun `issue url targets the repo, omits labels, and round-trips title and body`() {
        val r = report(manufacturer = "Föö", model = "A&B #1 100%+x")
        val url = issueUrl(r)

        assertThat(url).startsWith("https://github.com/d4rken-org/amply/issues/new?title=")
        assertThat(url).contains("&body=")
        assertThat(url).doesNotContain("labels=")

        val titlePart = url.substringAfter("?title=").substringBefore("&body=")
        val bodyPart = url.substringAfter("&body=")
        assertThat(URLDecoder.decode(titlePart, Charsets.UTF_8.name())).isEqualTo(issueTitle(r))
        assertThat(URLDecoder.decode(bodyPart, Charsets.UTF_8.name())).isEqualTo(issueBody(r))
    }

    @Test
    fun `body fences the report so special characters cannot break markdown`() {
        val r = report(model = "A&B #1")
        val decodedBody = URLDecoder.decode(
            issueUrl(r).substringAfter("&body="),
            Charsets.UTF_8.name(),
        )
        assertThat(decodedBody).contains("```")
        assertThat(decodedBody).contains(formatReport(r))
    }

    @Test
    fun `empty model still yields a clean title`() {
        assertThat(issueTitle(report(manufacturer = "Nothing", model = "")))
            .isEqualTo("[Device support] Nothing")
    }

    @Test
    fun `newlines in values survive encoding without breaking the query`() {
        // A CR/LF that slipped past sanitization must still encode to a single query value.
        val url = issueUrl(report(model = "bad\nmodel"))
        assertThat(url).doesNotContain("\n")
        assertThat(url.count { it == '?' }).isEqualTo(1)
    }
}
