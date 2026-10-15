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

class LenovoChargingAdapterTest {
    private val adapter = LenovoChargingAdapter()

    private fun lenovo(
        manufacturer: String = "LENOVO",
        model: String = "Lenovo PB-6505M",
        codename: String = "PB-6505M",
        sdk: Int = 28,
        systemUser: Boolean = true,
    ) = DeviceInfo(
        manufacturer = manufacturer,
        model = model,
        sdk = sdk,
        fingerprint = "test",
        codename = codename,
        isPhone = false,
        hasChargingOptimization = false,
        isSystemUser = systemUser,
    )

    @Test
    fun `the qualified identity matches with control`() {
        val support = adapter.probe(lenovo())
        support.matched shouldBe true
        support.controlEnabled shouldBe true
        support.detail shouldBe R.string.adapter_detail_lenovo_ready
        support.contributionWanted shouldBe false
    }

    @Test
    fun `secondary user matches but disables control`() {
        val support = adapter.probe(lenovo(systemUser = false))
        support.matched shouldBe true
        support.controlEnabled shouldBe false
        support.detail shouldBe R.string.adapter_detail_secondary_user
    }

    @Test
    fun `each single deviation from the qualified identity does not match`() {
        listOf(
            lenovo(manufacturer = "Motorola"),
            lenovo(model = "Lenovo TB-X606F"),
            lenovo(codename = "TB-X606F"),
            lenovo(sdk = 27),
            lenovo(sdk = 29),
        ).forEach { device ->
            val support = adapter.probe(device)
            support.matched shouldBe false
            support.controlEnabled shouldBe false
            support.detail shouldBe R.string.adapter_detail_requires_lenovo
        }
    }

    @Test
    fun `codename and model must come from the same qualified pair`() {
        adapter.probe(lenovo(codename = "PB-6505M", model = "Lenovo PB-6505F")).matched shouldBe false
        adapter.probe(lenovo(codename = "PB-6505F", model = "Lenovo PB-6505M")).matched shouldBe false
    }

    @Test
    fun `identity matching ignores case`() {
        adapter.probe(lenovo(manufacturer = "Lenovo", codename = "pb-6505m", model = "LENOVO PB-6505M"))
            .matched shouldBe true
    }

    @Test
    fun `the gate does not require telephony`() {
        adapter.probe(lenovo().copy(isPhone = false)).controlEnabled shouldBe true
    }

    @Test
    fun `read decodes the qualified values`() = runTest {
        suspend fun observed(value: String) = adapter.read(FakeBackend(values = mutableMapOf(KEY to value)))

        observed("0") shouldBe ChargeObservation.Verified(ChargePolicy.Unrestricted, BackendKind.SHIZUKU)
        observed("1") shouldBe ChargeObservation.Verified(ChargePolicy.FixedLimit(60), BackendKind.SHIZUKU)
    }

    @Test
    fun `absent, other and malformed values are unrecognized`() = runTest {
        listOf(null, "2", "-1", "60", "", " 1", "garbage").forEach { value ->
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
            ChargePolicy.FixedLimit(60) to "1",
            ChargePolicy.Unrestricted to "0",
        ).forEach { (policy, value) ->
            val backend = FakeBackend()
            adapter.apply(policy, backend) shouldBe true
            backend.writes shouldContainExactly listOf(SettingMutation(SettingNamespace.SYSTEM, KEY, value))
        }
    }

    @Test
    fun `apply fails on a read-back mismatch`() = runTest {
        adapter.apply(ChargePolicy.FixedLimit(60), FakeBackend(dropWrites = true)) shouldBe false
    }

    @Test
    fun `apply fails when the write fails`() = runTest {
        adapter.apply(ChargePolicy.FixedLimit(60), FakeBackend(writeSucceeds = false)) shouldBe false
    }

    @Test
    fun `apply rejects unsupported policies without writing`() = runTest {
        listOf(
            ChargePolicy.FixedLimit(80),
            ChargePolicy.FixedLimit(70),
            ChargePolicy.Adaptive,
            ChargePolicy.PauseAtFull,
        ).forEach { policy ->
            val backend = FakeBackend()
            adapter.apply(policy, backend) shouldBe false
            backend.writes.shouldBeEmpty()
        }
    }

    @Test
    fun `session capabilities`() {
        adapter.supportedPolicies shouldContainExactly listOf(
            ChargePolicy.FixedLimit(60),
            ChargePolicy.Unrestricted,
        )
        adapter.sessionOverridePolicy shouldBe ChargePolicy.Unrestricted
        adapter.defaultProtectivePolicy shouldBe ChargePolicy.FixedLimit(60)
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
        const val KEY = LenovoChargingAdapter.KEY
    }
}
