package eu.darken.amply.charging.core.access

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test

class LineageHealthProbeTest {

    /** Verbatim `dumpsys lineagehealth` from oriole on LineageOS 23.2 / Android 16. */
    private val orioleDump = """

        LineageHealth Service State:

        ChargingControlController Configuration:
          Enabled: false
          Mode: 1
          Limit: 80
          StartTime: 79200
          TargetTime: 21600

        ChargingControlController State:
          mIsEnabled: false
          mBatteryPct: 100.0
          mIsPowerConnected: true
          mIsNotificationPosted: false
          mIsDoneNotification: true
          mIsControlCancelledOnce: false

        Provider: org.lineageos.platform.internal.health.ccprovider.Deadline
          mSavedTargetTime: 0
    """.trimIndent()

    private fun dump(provider: String, mode: Int) =
        "ChargingControlController Configuration:\n  Mode: $mode\nProvider: $provider"

    private val upstream = "org.lineageos.platform.internal.health.ccprovider"

    @Test
    fun `the real oriole dump parses to deadline in auto mode`() {
        val summary = parseLineageHealthDump(orioleDump)!!
        summary.provider shouldBe LineageChargingProvider.DEADLINE
        summary.mode shouldBe 1
    }

    @Test
    fun `deadline in auto mode proves nothing about limit support`() {
        // Upstream getProviderForMode tries Deadline FIRST for MODE_AUTO/MODE_MANUAL and returns immediately,
        // so Limit is never consulted. Reading this as "no limit support" was the original mistake: oriole's
        // NO-GO came from a mode=3 write reading back as 1, not from its Deadline dump.
        parseLineageHealthDump(dump("$upstream.Deadline", 1))!!
            .limitMechanism shouldBe LineageLimitMechanism.NOT_OBSERVED
        parseLineageHealthDump(dump("$upstream.Deadline", 2))!!
            .limitMechanism shouldBe LineageLimitMechanism.NOT_OBSERVED
    }

    @Test
    fun `limit provider indicates a native HAL cap`() {
        // Limit is only ever bound when mLimit.isSupported(), on either mode branch.
        parseLineageHealthDump(dump("$upstream.Limit", 3))!!
            .limitMechanism shouldBe LineageLimitMechanism.NATIVE_LIMIT
    }

    @Test
    fun `toggle is a capable mechanism, never a rejection`() {
        // Toggle also accepts MODE_LIMIT and enforces targetPct itself by cutting charging, so binding Toggle
        // must NOT be read as "no percentage cap possible" — that inference was wrong and is what this pins.
        parseLineageHealthDump(dump("$upstream.Toggle", 3))!!
            .limitMechanism shouldBe LineageLimitMechanism.FRAMEWORK_TOGGLE
        parseLineageHealthDump(dump("$upstream.Toggle", 1))!!
            .limitMechanism shouldBe LineageLimitMechanism.FRAMEWORK_TOGGLE
    }

    @Test
    fun `no dump can ever yield an unsupported verdict`() {
        // There is deliberately no negative case: isHALModeSupported swallows RemoteException into false, so
        // even a non-selection can be transient. Enumerated so a future value cannot reintroduce a false NO-GO.
        LineageLimitMechanism.entries.map { it.name } shouldBe
            listOf("NATIVE_LIMIT", "FRAMEWORK_TOGGLE", "NOT_OBSERVED", "UNKNOWN")
    }

    @Test
    fun `a fork's same-named provider is not given upstream semantics`() {
        // Derivatives are deliberately accepted by DeviceInfo.isLineageOs, so a same-simple-name class from an
        // unknown package is exactly the case that must fail closed rather than inherit upstream's meaning.
        val forked = parseLineageHealthDump(dump("com.somefork.health.ccprovider.Limit", 3))!!
        forked.provider shouldBe LineageChargingProvider.UNKNOWN
        forked.limitMechanism shouldBe LineageLimitMechanism.UNKNOWN
    }

    @Test
    fun `a dump without a provider line is unknown rather than a negative result`() {
        parseLineageHealthDump("LineageHealth Service State:\n  Enabled: false") shouldBe null
        parseLineageHealthDump("") shouldBe null
    }

    @Test
    fun `the encoded form carries no schedule or battery detail`() {
        // StartTime/TargetTime are the user's charging schedule (22:00-06:00 here) and reveal their sleep
        // pattern; mBatteryPct is live state. This encoded string is all that crosses Binder.
        val encoded = parseLineageHealthDump(orioleDump)!!.encode()
        encoded shouldNotContain "79200"
        encoded shouldNotContain "21600"
        encoded shouldNotContain "100.0"
        encoded shouldBe "DEADLINE|1"
    }

    @Test
    fun `the encoded form round-trips`() {
        val summary = parseLineageHealthDump(orioleDump)!!
        parseLineageHealthSummary(summary.encode()) shouldBe summary
        parseLineageHealthSummary(LineageHealthSummary(LineageChargingProvider.LIMIT, null).encode())
            ?.provider shouldBe LineageChargingProvider.LIMIT
    }

    @Test
    fun `a malformed or empty encoded value decodes to null`() {
        parseLineageHealthSummary(null) shouldBe null
        parseLineageHealthSummary("") shouldBe null
        parseLineageHealthSummary("NOPE|1") shouldBe null
        parseLineageHealthSummary("LIMIT") shouldBe null
    }
}
