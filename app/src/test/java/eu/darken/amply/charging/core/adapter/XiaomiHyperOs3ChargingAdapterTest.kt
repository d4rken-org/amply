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
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

class XiaomiHyperOs3ChargingAdapterTest {
    private val adapter = XiaomiHyperOs3ChargingAdapter()

    private fun xiaomi3(
        model: String = "24117RN76G",
        codename: String = "tanzanite",
        hyperOs: Int? = 3,
        systemUser: Boolean = true,
        manufacturer: String = "Xiaomi",
    ) = DeviceInfo(
        manufacturer = manufacturer,
        model = model,
        codename = codename,
        sdk = 36,
        fingerprint = "test",
        hyperOsVersion = hyperOs,
        isSystemUser = systemUser,
    )

    @Test
    fun `a qualified codename on HyperOS 3 matches with control`() {
        val support = adapter.probe(xiaomi3())

        support.matched shouldBe true
        support.controlEnabled shouldBe true
        // Its own string, not the HyperOS 2 one: Battery protection has demonstrated hardware
        // enforcement, so this generation keeps the stronger "applies to the hardware" claim.
        support.detail shouldBe R.string.adapter_detail_xiaomi_hyperos3_ready
    }

    @Test
    fun `unqualified codenames fall through`() {
        // marblein (Poco F5, HyperOS 3.0.2) is the real counterexample: same key, only modes 0/1,
        // no Battery protection — the reason this gate cannot be version-only.
        adapter.probe(xiaomi3(codename = "marblein", model = "23049PCD8I")).matched shouldBe false
        adapter.probe(xiaomi3(codename = "")).matched shouldBe false
    }

    @Test
    fun `other HyperOS generations and non-Xiaomi devices do not match`() {
        // HyperOS 2 stays with the hyperos2 adapter even for a qualified codename.
        adapter.probe(xiaomi3(hyperOs = 2)).matched shouldBe false
        // A future major version needs its own qualification, even on a qualified codename.
        adapter.probe(xiaomi3(hyperOs = 4)).matched shouldBe false
        adapter.probe(xiaomi3(hyperOs = null)).matched shouldBe false
        adapter.probe(xiaomi3(manufacturer = "samsung")).matched shouldBe false
    }

    @Test
    fun `secondary user disables control`() {
        val support = adapter.probe(xiaomi3(systemUser = false))

        support.matched shouldBe true
        support.controlEnabled shouldBe false
        support.detail shouldBe R.string.adapter_detail_secondary_user
    }

    @Test
    fun `read maps all three modes and treats the absent key as intelligent`() = runTest {
        adapter.read(FakeBackend(values = mutableMapOf(XiaomiChargingAdapter.KEY_MODE to "2"))) shouldBe
            ChargeObservation.Verified(ChargePolicy.FixedLimit(80), BackendKind.DIRECT_WSS)
        adapter.read(FakeBackend(values = mutableMapOf(XiaomiChargingAdapter.KEY_MODE to "1"))) shouldBe
            ChargeObservation.Verified(ChargePolicy.Adaptive, BackendKind.DIRECT_WSS)
        adapter.read(FakeBackend(values = mutableMapOf(XiaomiChargingAdapter.KEY_MODE to "0"))) shouldBe
            ChargeObservation.Verified(ChargePolicy.Unrestricted, BackendKind.DIRECT_WSS)
        // Absent = intelligent mirrors HyperOS 2 but is UNVERIFIED on HyperOS 3 — pending the
        // issue-#48 qualification run's factory/absent-key check.
        adapter.read(FakeBackend()) shouldBe
            ChargeObservation.Verified(ChargePolicy.Adaptive, BackendKind.DIRECT_WSS)
    }

    @Test
    fun `unrecognized values are flagged as such`() = runTest {
        val observed = adapter.read(FakeBackend(values = mutableMapOf(XiaomiChargingAdapter.KEY_MODE to "3")))

        observed.shouldBeInstanceOf<ChargeObservation.Unknown>()
        observed.unrecognizedValue shouldBe true
    }

    @Test
    fun `unreadable state is unknown but not flagged unrecognized`() = runTest {
        val observed = adapter.read(FakeBackend(readable = false))

        observed.shouldBeInstanceOf<ChargeObservation.Unknown>()
        observed.unrecognizedValue shouldBe false
    }

    @Test
    fun `apply writes the single mode key for all three policies`() = runTest {
        val backend = FakeBackend()

        adapter.apply(ChargePolicy.FixedLimit(80), backend) shouldBe true
        adapter.apply(ChargePolicy.Adaptive, backend) shouldBe true
        adapter.apply(ChargePolicy.Unrestricted, backend) shouldBe true

        backend.writes shouldContainExactly listOf(
            SettingMutation(SettingNamespace.SECURE, XiaomiChargingAdapter.KEY_MODE, "2"),
            SettingMutation(SettingNamespace.SECURE, XiaomiChargingAdapter.KEY_MODE, "1"),
            SettingMutation(SettingNamespace.SECURE, XiaomiChargingAdapter.KEY_MODE, "0"),
        )
    }

    @Test
    fun `apply rejects unsupported policies without writing`() = runTest {
        val backend = FakeBackend()

        // The OEM cap is hard-wired to 80 — any other percent must be refused, not approximated.
        adapter.apply(ChargePolicy.FixedLimit(85), backend) shouldBe false
        adapter.apply(ChargePolicy.PauseAtFull, backend) shouldBe false

        backend.writes shouldBe emptyList()
    }

    @Test
    fun `apply fails on rejected or dropped writes`() = runTest {
        adapter.apply(ChargePolicy.FixedLimit(80), FakeBackend(failWrites = true)) shouldBe false
        // Write reports success but the value never lands: read-back equality must fail.
        // (Absent decodes as Adaptive, so use a non-Adaptive policy for the dropped-write check.)
        adapter.apply(ChargePolicy.FixedLimit(80), FakeBackend(dropWrites = true)) shouldBe false
    }

    @Test
    fun `session capabilities`() {
        adapter.sessionOverridePolicy shouldBe ChargePolicy.Unrestricted
        adapter.defaultProtectivePolicy shouldBe ChargePolicy.FixedLimit(80)
        adapter.verification shouldBe VerificationStrategy.SYNC_READBACK
        adapter.reconnectGestureSupported shouldBe false
        adapter.preferShizukuForWrites shouldBe false
        adapter.policyLatchesAtPlug shouldBe false
        // Unlike HyperOS 2, this ROM has an unconditional protective mode (Battery protection), so
        // the default is one Amply can honestly claim is holding.
        adapter.defaultProtectivePolicy.enforcementIsConditional shouldBe false
    }

    private class FakeBackend(
        private val readable: Boolean = true,
        private val values: MutableMap<String, String> = mutableMapOf(),
        private val failWrites: Boolean = false,
        private val dropWrites: Boolean = false,
    ) : AccessBackend {
        override val kind = BackendKind.DIRECT_WSS
        val writes = mutableListOf<SettingMutation>()

        override suspend fun status() = BackendStatus(true, true, "test".toCaString())
        override suspend fun read(namespace: SettingNamespace, key: String) =
            SettingRead(readable, values[key], if (readable) null else "blocked".toCaString())

        override suspend fun write(mutation: SettingMutation): Boolean {
            if (failWrites) return false
            writes += mutation
            if (!dropWrites) values[mutation.key] = mutation.value
            return true
        }
    }
}
