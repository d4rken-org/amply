package eu.darken.amply.charging.core.adapter

import eu.darken.amply.R
import eu.darken.amply.charging.core.BackendKind
import eu.darken.amply.charging.core.ChargeObservation
import eu.darken.amply.charging.core.ChargePolicy
import eu.darken.amply.charging.core.DeviceInfo
import eu.darken.amply.charging.core.access.AccessBackend
import eu.darken.amply.charging.core.access.BackendStatus
import eu.darken.amply.charging.core.access.SettingMutation
import eu.darken.amply.charging.core.access.SettingNamespace
import eu.darken.amply.charging.core.access.SettingRead
import eu.darken.amply.common.ca.toCaString
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

class NothingChargingAdapterTest {
    private val adapter = NothingChargingAdapter()

    private fun nothing(
        manufacturer: String = "Nothing",
        model: String = "A059P",
        codename: String = "Asteroids",
        sdk: Int = 36,
        feature: Boolean = true,
        systemUser: Boolean = true,
    ) = DeviceInfo(
        manufacturer = manufacturer,
        model = model,
        sdk = sdk,
        fingerprint = "test",
        codename = codename,
        hasNothingFeature = feature,
        isSystemUser = systemUser,
    )

    @Test
    fun `the qualified identity matches with control`() {
        val support = adapter.probe(nothing())
        support.matched shouldBe true
        support.controlEnabled shouldBe true
        support.detail shouldBe R.string.adapter_detail_nothing_ready
        support.contributionWanted shouldBe false
    }

    @Test
    fun `secondary user matches but disables control`() {
        val support = adapter.probe(nothing(systemUser = false))
        support.matched shouldBe true
        support.controlEnabled shouldBe false
        support.detail shouldBe R.string.adapter_detail_secondary_user
    }

    @Test
    fun `each single deviation from the qualified identity does not match`() {
        listOf(
            nothing(model = "A059"),
            nothing(sdk = 35),
            nothing(sdk = 37),
            nothing(codename = "Galaga"),
            nothing(feature = false),
            nothing(manufacturer = "Google"),
        ).forEach { device ->
            val support = adapter.probe(device)
            support.matched shouldBe false
            support.controlEnabled shouldBe false
            support.detail shouldBe R.string.adapter_detail_requires_nothing
        }
    }

    @Test
    fun `identity matching ignores case`() {
        adapter.probe(nothing(manufacturer = "NOTHING", codename = "asteroids", model = "a059p")).matched shouldBe true
    }

    @Test
    fun `read decodes the qualified values`() = runTest {
        suspend fun observed(value: String) = adapter.read(FakeBackend(values = mutableMapOf(KEY to value)))

        observed("0") shouldBe ChargeObservation.Verified(ChargePolicy.Unrestricted, BackendKind.SHIZUKU)
        observed("2") shouldBe ChargeObservation.Verified(ChargePolicy.FixedLimit(70), BackendKind.SHIZUKU)
        observed("3") shouldBe ChargeObservation.Verified(ChargePolicy.FixedLimit(80), BackendKind.SHIZUKU)
    }

    @Test
    fun `absent, unqualified and malformed values are unrecognized`() = runTest {
        listOf(null, "1", "4", "garbage").forEach { value ->
            val values = mutableMapOf<String, String>()
            value?.let { values[KEY] = it }
            val observed = adapter.read(FakeBackend(values = values))
            observed.shouldBeInstanceOf<ChargeObservation.Unknown>()
            observed.unrecognizedValue shouldBe true
        }
    }

    @Test
    fun `unreadable state is unknown but not flagged unrecognized`() = runTest {
        val observed = adapter.read(FakeBackend(readable = false))
        observed.shouldBeInstanceOf<ChargeObservation.Unknown>()
        observed.unrecognizedValue shouldBe false
    }

    @Test
    fun `apply writes the single system key per policy`() = runTest {
        mapOf(
            ChargePolicy.FixedLimit(80) to "3",
            ChargePolicy.FixedLimit(70) to "2",
            ChargePolicy.Unrestricted to "0",
        ).forEach { (policy, value) ->
            val backend = FakeBackend()
            adapter.apply(policy, backend) shouldBe true
            backend.writes shouldContainExactly listOf(SettingMutation(SettingNamespace.SYSTEM, KEY, value))
        }
    }

    @Test
    fun `apply fails on a read-back mismatch`() = runTest {
        adapter.apply(ChargePolicy.FixedLimit(80), FakeBackend(dropWrites = true)) shouldBe false
    }

    @Test
    fun `apply fails when the write fails`() = runTest {
        adapter.apply(ChargePolicy.FixedLimit(80), FakeBackend(writeSucceeds = false)) shouldBe false
    }

    @Test
    fun `apply rejects unsupported policies without writing`() = runTest {
        listOf(ChargePolicy.FixedLimit(90), ChargePolicy.Adaptive).forEach { policy ->
            val backend = FakeBackend()
            adapter.apply(policy, backend) shouldBe false
            backend.writes.shouldBeEmpty()
        }
    }

    @Test
    fun `session capabilities`() {
        adapter.supportedPolicies shouldContainExactly listOf(
            ChargePolicy.FixedLimit(80),
            ChargePolicy.FixedLimit(70),
            ChargePolicy.Unrestricted,
        )
        adapter.sessionOverridePolicy shouldBe ChargePolicy.Unrestricted
        adapter.defaultProtectivePolicy shouldBe ChargePolicy.FixedLimit(80)
        adapter.verification shouldBe VerificationStrategy.SYNC_READBACK
        adapter.preferShizukuForWrites shouldBe true
        adapter.reconnectGestureSupport shouldBe ReconnectSupport.ANY_LEVEL_ONLY
        adapter.policyLatchesAtPlug shouldBe false
        adapter.enforcementEvidenceRequired shouldBe false
    }

    private class FakeBackend(
        private val readable: Boolean = true,
        private val values: MutableMap<String, String> = mutableMapOf(),
        private val dropWrites: Boolean = false,
        private val writeSucceeds: Boolean = true,
    ) : AccessBackend {
        override val kind = BackendKind.SHIZUKU
        val writes = mutableListOf<SettingMutation>()

        override suspend fun status() = BackendStatus(true, true, "test".toCaString())
        override suspend fun read(namespace: SettingNamespace, key: String) =
            SettingRead(readable, values[key], if (readable) null else "blocked".toCaString())

        override suspend fun write(mutation: SettingMutation): Boolean {
            writes += mutation
            if (!writeSucceeds) return false
            if (!dropWrites) values[mutation.key] = mutation.value
            return true
        }
    }

    private companion object {
        const val KEY = NothingChargingAdapter.KEY
    }
}
