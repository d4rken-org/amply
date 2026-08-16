package eu.darken.amply.charging.core.enforcement

import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldMatch
import org.junit.jupiter.api.Test

class BuildIdentityTest {

    private val stockFingerprint = "google/oriole/oriole:16/BP3A.250905.014/13640300:user/release-keys"

    private fun identity(
        fingerprint: String = stockFingerprint,
        incremental: String = "13640300",
        buildTime: Long = 1_762_000_000_000L,
        providerVersion: Long = 16_000L,
    ) = composeBuildIdentity(fingerprint, incremental, buildTime, providerVersion)

    @Test
    fun `the same build composes the same identity`() {
        identity() shouldBe identity()
    }

    @Test
    fun `identical fingerprints still differ per build`() {
        // LineageOS spoofs Build.FINGERPRINT to stock, so two nightlies share it while the charging
        // service underneath differs. Fingerprint-only scoping would keep trusting the old verdict.
        identity() shouldNotBe identity(incremental = "13640301")
        identity() shouldNotBe identity(buildTime = 1_763_000_000_000L)
        identity() shouldNotBe identity(providerVersion = 16_001L)
    }

    @Test
    fun `the identity is an opaque fixed-length token`() {
        identity() shouldMatch Regex("[0-9a-f]{16}")
    }
}
