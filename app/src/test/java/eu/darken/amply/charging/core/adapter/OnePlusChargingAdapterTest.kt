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

class OnePlusChargingAdapterTest {
    private val adapter = OnePlusChargingAdapter()

    private fun oplus(
        manufacturer: String = "OnePlus",
        oplusRom: Int? = 15,
        systemUser: Boolean = true,
    ) = DeviceInfo(
        manufacturer = manufacturer,
        model = "CPH2621",
        sdk = 35,
        fingerprint = "test",
        oplusRomVersion = oplusRom,
        isSystemUser = systemUser,
    )

    @Test
    fun `any ColorOS 15 device matches across the oplus family`() {
        // The gate is the oplus ROM version (oplusrom is Oplus-exclusive), not the manufacturer.
        adapter.probe(oplus(manufacturer = "OnePlus")).controlEnabled shouldBe true
        adapter.probe(oplus(manufacturer = "OPPO")).controlEnabled shouldBe true
        adapter.probe(oplus(manufacturer = "realme")).controlEnabled shouldBe true
        adapter.probe(oplus()).detail shouldBe R.string.adapter_detail_oplus_ready
    }

    @Test
    fun `other rom versions and non-oplus devices do not match`() {
        adapter.probe(oplus(oplusRom = 14)).matched shouldBe false
        adapter.probe(oplus(oplusRom = 16)).matched shouldBe false
        adapter.probe(oplus(oplusRom = null)).matched shouldBe false // non-Oplus device
    }

    @Test
    fun `secondary user disables control`() {
        val support = adapter.probe(oplus(systemUser = false))
        support.matched shouldBe true
        support.controlEnabled shouldBe false
        support.detail shouldBe R.string.adapter_detail_secondary_user
    }

    @Test
    fun `read maps the three-way selection`() = runTest {
        suspend fun observed(regular: String?, smart: String?): ChargeObservation {
            val values = mutableMapOf<String, String>()
            regular?.let { values[OnePlusChargingAdapter.KEY_REGULAR] = it }
            smart?.let { values[OnePlusChargingAdapter.KEY_SMART] = it }
            return adapter.read(FakeBackend(values = values))
        }

        observed("1", "0") shouldBe ChargeObservation.Verified(ChargePolicy.FixedLimit(80), BackendKind.SHIZUKU)
        observed("0", "1") shouldBe ChargeObservation.Verified(ChargePolicy.Adaptive, BackendKind.SHIZUKU)
        observed("0", "0") shouldBe ChargeObservation.Verified(ChargePolicy.Unrestricted, BackendKind.SHIZUKU)
        // Absent keys decode as off (Unrestricted).
        observed(null, null) shouldBe ChargeObservation.Verified(ChargePolicy.Unrestricted, BackendKind.SHIZUKU)
    }

    @Test
    fun `both-on and malformed values are unrecognized`() = runTest {
        suspend fun observed(regular: String?, smart: String?): ChargeObservation {
            val values = mutableMapOf<String, String>()
            regular?.let { values[OnePlusChargingAdapter.KEY_REGULAR] = it }
            smart?.let { values[OnePlusChargingAdapter.KEY_SMART] = it }
            return adapter.read(FakeBackend(values = values))
        }

        // Mutual exclusion is OEM-enforced; both on = inconsistent external state, never overwrite.
        observed("1", "1").let {
            it.shouldBeInstanceOf<ChargeObservation.Unknown>()
            it.unrecognizedValue shouldBe true
        }
        observed("2", "0").let {
            it.shouldBeInstanceOf<ChargeObservation.Unknown>()
            it.unrecognizedValue shouldBe true
        }
    }

    @Test
    fun `unreadable state is unknown but not flagged unrecognized`() = runTest {
        val observed = adapter.read(FakeBackend(readable = false))
        observed.shouldBeInstanceOf<ChargeObservation.Unknown>()
        observed.unrecognizedValue shouldBe false
    }

    @Test
    fun `apply writes both system keys, off before on`() = runTest {
        val limitBackend = FakeBackend()
        adapter.apply(ChargePolicy.FixedLimit(80), limitBackend) shouldBe true
        limitBackend.writes shouldContainExactly listOf(
            SettingMutation(SettingNamespace.SYSTEM, OnePlusChargingAdapter.KEY_SMART, "0"),
            SettingMutation(SettingNamespace.SYSTEM, OnePlusChargingAdapter.KEY_REGULAR, "1"),
        )

        val adaptiveBackend = FakeBackend()
        adapter.apply(ChargePolicy.Adaptive, adaptiveBackend) shouldBe true
        adaptiveBackend.writes shouldContainExactly listOf(
            SettingMutation(SettingNamespace.SYSTEM, OnePlusChargingAdapter.KEY_REGULAR, "0"),
            SettingMutation(SettingNamespace.SYSTEM, OnePlusChargingAdapter.KEY_SMART, "1"),
        )

        val fullBackend = FakeBackend()
        adapter.apply(ChargePolicy.Unrestricted, fullBackend) shouldBe true
        fullBackend.writes shouldContainExactly listOf(
            SettingMutation(SettingNamespace.SYSTEM, OnePlusChargingAdapter.KEY_REGULAR, "0"),
            SettingMutation(SettingNamespace.SYSTEM, OnePlusChargingAdapter.KEY_SMART, "0"),
        )
    }

    @Test
    fun `apply rejects unsupported policies and dropped writes`() = runTest {
        adapter.apply(ChargePolicy.FixedLimit(85), FakeBackend()) shouldBe false
        adapter.apply(ChargePolicy.PauseAtFull, FakeBackend()) shouldBe false
        // Writes report success but never land → read-back mismatch fails the apply.
        adapter.apply(ChargePolicy.FixedLimit(80), FakeBackend(dropWrites = true)) shouldBe false
    }

    @Test
    fun `session capabilities`() {
        adapter.sessionOverridePolicy shouldBe ChargePolicy.Unrestricted
        adapter.defaultProtectivePolicy shouldBe ChargePolicy.FixedLimit(80)
        adapter.verification shouldBe VerificationStrategy.SYNC_READBACK
        adapter.preferShizukuForWrites shouldBe true
        adapter.reconnectGestureSupported shouldBe false
    }

    private class FakeBackend(
        private val readable: Boolean = true,
        private val values: MutableMap<String, String> = mutableMapOf(),
        private val dropWrites: Boolean = false,
    ) : AccessBackend {
        override val kind = BackendKind.SHIZUKU
        val writes = mutableListOf<SettingMutation>()

        override suspend fun status() = BackendStatus(true, true, "test".toCaString())
        override suspend fun read(namespace: SettingNamespace, key: String) =
            SettingRead(readable, values[key], if (readable) null else "blocked".toCaString())

        override suspend fun write(mutation: SettingMutation): Boolean {
            writes += mutation
            if (!dropWrites) values[mutation.key] = mutation.value
            return true
        }

        override suspend fun snapshot(namespace: SettingNamespace) = emptyMap<String, String>()
    }
}
