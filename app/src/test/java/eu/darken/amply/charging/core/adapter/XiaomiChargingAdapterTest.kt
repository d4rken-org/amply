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

class XiaomiChargingAdapterTest {
    private val adapter = XiaomiChargingAdapter()

    private fun xiaomi(
        model: String = "2306EPN60G",
        hyperOs: Int? = 2,
        systemUser: Boolean = true,
        manufacturer: String = "Xiaomi",
    ) = DeviceInfo(
        manufacturer = manufacturer,
        model = model,
        sdk = 35,
        fingerprint = "test",
        hyperOsVersion = hyperOs,
        isSystemUser = systemUser,
    )

    @Test
    fun `any HyperOS 2 Xiaomi device matches regardless of model`() {
        adapter.probe(xiaomi()).matched shouldBe true
        adapter.probe(xiaomi()).controlEnabled shouldBe true
        adapter.probe(xiaomi()).detail shouldBe R.string.adapter_detail_xiaomi_ready

        // The gate is the ROM version, not the model: a different HyperOS 2 model still matches.
        adapter.probe(xiaomi(model = "23078PND5G")).matched shouldBe true
        // Redmi/POCO report Xiaomi as manufacturer, so they qualify on HyperOS 2 too.
        adapter.probe(xiaomi(model = "23021RAAEG")).matched shouldBe true
    }

    @Test
    fun `other HyperOS generations and non-Xiaomi devices do not match`() {
        adapter.probe(xiaomi(hyperOs = 1)).matched shouldBe false // HyperOS 1 unverified
        adapter.probe(xiaomi(hyperOs = 3)).matched shouldBe false // future HyperOS 3 unverified
        adapter.probe(xiaomi(hyperOs = null)).matched shouldBe false // pre-HyperOS MIUI
        adapter.probe(xiaomi(manufacturer = "samsung")).matched shouldBe false
    }

    @Test
    fun `secondary user disables control`() {
        val support = adapter.probe(xiaomi(systemUser = false))

        support.matched shouldBe true
        support.controlEnabled shouldBe false
        support.detail shouldBe R.string.adapter_detail_secondary_user
    }

    @Test
    fun `read maps both modes and treats the absent key as intelligent`() = runTest {
        adapter.read(FakeBackend(values = mutableMapOf(XiaomiChargingAdapter.KEY_MODE to "1"))) shouldBe
            ChargeObservation.Verified(ChargePolicy.Adaptive, BackendKind.DIRECT_WSS)
        adapter.read(FakeBackend(values = mutableMapOf(XiaomiChargingAdapter.KEY_MODE to "0"))) shouldBe
            ChargeObservation.Verified(ChargePolicy.Unrestricted, BackendKind.DIRECT_WSS)
        // Factory state: key absent until first change; OEM UI treats it as intelligent.
        adapter.read(FakeBackend()) shouldBe
            ChargeObservation.Verified(ChargePolicy.Adaptive, BackendKind.DIRECT_WSS)
    }

    @Test
    fun `unrecognized values are flagged as such`() = runTest {
        val observed = adapter.read(FakeBackend(values = mutableMapOf(XiaomiChargingAdapter.KEY_MODE to "2")))

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
    fun `apply writes the single mode key`() = runTest {
        val backend = FakeBackend()

        adapter.apply(ChargePolicy.Unrestricted, backend) shouldBe true
        adapter.apply(ChargePolicy.Adaptive, backend) shouldBe true

        backend.writes shouldContainExactly listOf(
            SettingMutation(SettingNamespace.SECURE, XiaomiChargingAdapter.KEY_MODE, "0"),
            SettingMutation(SettingNamespace.SECURE, XiaomiChargingAdapter.KEY_MODE, "1"),
        )
    }

    @Test
    fun `apply rejects unsupported policies without writing`() = runTest {
        val backend = FakeBackend()

        adapter.apply(ChargePolicy.FixedLimit(80), backend) shouldBe false
        adapter.apply(ChargePolicy.PauseAtFull, backend) shouldBe false

        backend.writes shouldBe emptyList()
    }

    @Test
    fun `apply fails on rejected or dropped writes`() = runTest {
        adapter.apply(ChargePolicy.Adaptive, FakeBackend(failWrites = true)) shouldBe false
        // Write reports success but the value never lands: read-back equality must fail.
        // (Absent decodes as Adaptive, so use Unrestricted for the dropped-write check.)
        adapter.apply(ChargePolicy.Unrestricted, FakeBackend(dropWrites = true)) shouldBe false
    }

    @Test
    fun `session capabilities`() {
        adapter.sessionOverridePolicy shouldBe ChargePolicy.Unrestricted
        adapter.defaultProtectivePolicy shouldBe ChargePolicy.Adaptive
        adapter.verification shouldBe VerificationStrategy.SYNC_READBACK
        adapter.reconnectGestureSupported shouldBe false
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
