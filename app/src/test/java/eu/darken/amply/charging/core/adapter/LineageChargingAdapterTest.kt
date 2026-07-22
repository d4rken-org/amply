package eu.darken.amply.charging.core.adapter

import eu.darken.amply.R
import eu.darken.amply.charging.core.BackendKind
import eu.darken.amply.charging.core.ChargeObservation
import eu.darken.amply.charging.core.ChargePolicy
import eu.darken.amply.charging.core.DeviceInfo
import eu.darken.amply.charging.core.access.AccessBackend
import eu.darken.amply.charging.core.access.BackendStatus
import eu.darken.amply.charging.core.access.LineageChargeReadout
import eu.darken.amply.charging.core.access.LineageChargeReader
import eu.darken.amply.charging.core.access.SettingMutation
import eu.darken.amply.charging.core.access.SettingNamespace
import eu.darken.amply.charging.core.access.SettingRead
import eu.darken.amply.common.ca.toCaString
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

class LineageChargingAdapterTest {

    private fun device(
        codename: String = "oriole",
        provider: Boolean = true,
        systemUser: Boolean = true,
        version: String? = "23.2",
    ) = DeviceInfo(
        manufacturer = "Google",
        model = "Pixel 6",
        sdk = 36,
        fingerprint = "test",
        codename = codename,
        lineageOsVersion = version,
        hasLineageSettingsProvider = provider,
        isSystemUser = systemUser,
    )

    // Gate logic is exercised through the test seam; production ships an empty allowlist (asserted below).
    private fun gated(vararg codenames: String) = LineageChargingAdapter(FakeLineage(), codenames.toSet())

    @Test
    fun `production adapter ships lab-only with an empty qualified allowlist`() {
        LineageChargingAdapter.QUALIFIED_CODENAMES shouldBe emptySet()
        // Even a fully-provisioned oriole is not live until a codename is qualified.
        LineageChargingAdapter(FakeLineage()).probe(device()).matched shouldBe false
    }

    @Test
    fun `probe gates on qualified codename, provider, and system user`() {
        gated("oriole").probe(device()).let {
            it.controlEnabled shouldBe true
            it.detail shouldBe R.string.adapter_detail_lineageos_ready
        }
        gated("oriole").probe(device(version = null)).matched shouldBe false // not LineageOS
        gated("oriole").probe(device(codename = "raven")).let {
            it.matched shouldBe false // codename not in the qualified allowlist
            it.detail shouldBe R.string.adapter_detail_requires_lineageos
        }
        gated("oriole").probe(device(provider = false)).let {
            it.matched shouldBe true
            it.controlEnabled shouldBe false
            it.detail shouldBe R.string.adapter_detail_lineageos_no_provider
        }
        gated("oriole").probe(device(systemUser = false)).let {
            it.controlEnabled shouldBe false
            it.detail shouldBe R.string.adapter_detail_secondary_user
        }
    }

    private suspend fun observed(enabled: String?, mode: String? = null, limit: String? = null): ChargeObservation {
        val values = mutableMapOf<String, String>()
        enabled?.let { values[LineageChargingAdapter.KEY_ENABLED] = it }
        mode?.let { values[LineageChargingAdapter.KEY_MODE] = it }
        limit?.let { values[LineageChargingAdapter.KEY_LIMIT] = it }
        val fake = FakeLineage(values)
        return LineageChargingAdapter(fake).read(fake)
    }

    @Test
    fun `read maps restorable states to Verified`() = runTest {
        observed(enabled = "0") shouldBe ChargeObservation.Verified(ChargePolicy.Unrestricted, BackendKind.SHIZUKU)
        observed(enabled = "1", mode = "3", limit = "80") shouldBe
            ChargeObservation.Verified(ChargePolicy.FixedLimit(80), BackendKind.SHIZUKU)
        observed(enabled = "1", mode = "3", limit = "95") shouldBe
            ChargeObservation.Verified(ChargePolicy.FixedLimit(95), BackendKind.SHIZUKU)
    }

    @Test
    fun `read refuses schedules, off-tick and non-canonical limits, absent and malformed enabled`() = runTest {
        listOf(
            observed(enabled = "1", mode = "1", limit = "80"), // AUTO schedule
            observed(enabled = "1", mode = "2", limit = "80"), // CUSTOM schedule
            observed(enabled = "1", mode = "3", limit = "72"), // off-tick limit
            observed(enabled = "1", mode = "3", limit = "080"), // non-canonical (leading zero)
            observed(enabled = "1", mode = "3", limit = "+80"), // non-canonical (sign)
            observed(enabled = "1", mode = "3", limit = " 80 "), // non-canonical (whitespace)
            observed(enabled = "1", mode = "3", limit = null), // limit absent
            observed(enabled = null), // enabled absent (unconfigured) — refuse rather than guess "off"
            observed(enabled = "x"), // malformed
        ).forEach {
            it.shouldBeInstanceOf<ChargeObservation.Unknown>()
            it.unrecognizedValue shouldBe true
        }
    }

    @Test
    fun `unreadable state is unknown but not flagged unrecognized`() = runTest {
        val fake = FakeLineage(readable = false)
        val result = LineageChargingAdapter(fake).read(fake)
        result.shouldBeInstanceOf<ChargeObservation.Unknown>()
        result.unrecognizedValue shouldBe false
    }

    @Test
    fun `apply writes limit, then mode, then enabled and read-back verifies`() = runTest {
        val fake = FakeLineage()
        LineageChargingAdapter(fake).apply(ChargePolicy.FixedLimit(85), fake) shouldBe true
        fake.writes shouldContainExactly listOf(
            SettingMutation(SettingNamespace.LINEAGE_SYSTEM, LineageChargingAdapter.KEY_LIMIT, "85"),
            SettingMutation(SettingNamespace.LINEAGE_SYSTEM, LineageChargingAdapter.KEY_MODE, "3"),
            SettingMutation(SettingNamespace.LINEAGE_SYSTEM, LineageChargingAdapter.KEY_ENABLED, "1"),
        )

        val off = FakeLineage()
        LineageChargingAdapter(off).apply(ChargePolicy.Unrestricted, off) shouldBe true
        off.writes shouldContainExactly listOf(
            SettingMutation(SettingNamespace.LINEAGE_SYSTEM, LineageChargingAdapter.KEY_ENABLED, "0"),
        )
    }

    @Test
    fun `apply rejects unsupported policies and dropped writes`() = runTest {
        LineageChargingAdapter(FakeLineage()).apply(ChargePolicy.Adaptive, FakeLineage()) shouldBe false
        LineageChargingAdapter(FakeLineage()).apply(ChargePolicy.PauseAtFull, FakeLineage()) shouldBe false
        LineageChargingAdapter(FakeLineage()).apply(ChargePolicy.FixedLimit(72), FakeLineage()) shouldBe false // off-tick
        // Writes report success but never land → read-back mismatch fails the apply.
        val dropping = FakeLineage(dropWrites = true)
        LineageChargingAdapter(dropping).apply(ChargePolicy.FixedLimit(80), dropping) shouldBe false
    }

    @Test
    fun `supported policies cover every discrete tick plus unrestricted`() {
        LineageChargingAdapter(FakeLineage()).supportedPolicies shouldBe
            LineageChargingAdapter.SUPPORTED_LIMITS.map { ChargePolicy.FixedLimit(it) } + ChargePolicy.Unrestricted
    }

    @Test
    fun `session capabilities`() {
        val adapter = LineageChargingAdapter(FakeLineage())
        adapter.sessionOverridePolicy shouldBe ChargePolicy.Unrestricted
        adapter.defaultProtectivePolicy shouldBe ChargePolicy.FixedLimit(80)
        adapter.verification shouldBe VerificationStrategy.SYNC_READBACK
        adapter.preferShizukuForWrites shouldBe true
        adapter.reconnectGestureSupported shouldBe false
    }

    /** One fake serving BOTH the write backend and the read snapshot from a shared map — mirrors the real
     * provider, where a Shizuku `content insert` and a ContentResolver query hit the same store. */
    private class FakeLineage(
        private val values: MutableMap<String, String> = mutableMapOf(),
        private val readable: Boolean = true,
        private val dropWrites: Boolean = false,
    ) : AccessBackend, LineageChargeReader {
        override val kind = BackendKind.SHIZUKU
        val writes = mutableListOf<SettingMutation>()

        override suspend fun status() = BackendStatus(true, true, "test".toCaString())
        override suspend fun read(namespace: SettingNamespace, key: String) =
            SettingRead(false, error = "lineage reads use LineageChargeReader".toCaString())

        override suspend fun write(mutation: SettingMutation): Boolean {
            writes += mutation
            if (!dropWrites) values[mutation.key] = mutation.value
            return true
        }

        override suspend fun readChargeControl(): LineageChargeReadout = if (!readable) {
            LineageChargeReadout.Unreadable("blocked".toCaString())
        } else {
            LineageChargeReadout.Values(
                enabled = values[LineageChargingAdapter.KEY_ENABLED],
                mode = values[LineageChargingAdapter.KEY_MODE],
                limit = values[LineageChargingAdapter.KEY_LIMIT],
            )
        }
    }
}
