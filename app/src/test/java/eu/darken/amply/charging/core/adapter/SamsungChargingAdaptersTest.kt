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

class SamsungChargingAdaptersTest {
    private val modern = SamsungModernChargingAdapter()
    private val legacy = SamsungLegacyChargingAdapter()

    private fun samsung(
        oneUi: Int?,
        hasKey: Boolean = true,
        systemUser: Boolean = true,
        manufacturer: String = "samsung",
    ) = DeviceInfo(
        manufacturer = manufacturer,
        model = "SM-X210",
        sdk = 36,
        fingerprint = "test",
        oneUiVersion = oneUi,
        hasProtectBattery = hasKey,
        isSystemUser = systemUser,
    )

    @Test
    fun `modern matches only one ui 8`() {
        modern.probe(samsung(80000)).matched shouldBe true
        modern.probe(samsung(89999)).matched shouldBe true
        modern.probe(samsung(79999)).matched shouldBe false
        modern.probe(samsung(90000)).matched shouldBe false
        modern.probe(samsung(null)).matched shouldBe false
    }

    @Test
    fun `legacy matches only one ui 4 and 5`() {
        legacy.probe(samsung(40000)).matched shouldBe true
        legacy.probe(samsung(40100)).matched shouldBe true
        legacy.probe(samsung(59999)).matched shouldBe true
        legacy.probe(samsung(39999)).matched shouldBe false
        legacy.probe(samsung(60000)).matched shouldBe false
        legacy.probe(samsung(70000)).matched shouldBe false
        legacy.probe(samsung(null)).matched shouldBe false
    }

    @Test
    fun `non samsung manufacturers never match`() {
        modern.probe(samsung(80000, manufacturer = "Google")).matched shouldBe false
        legacy.probe(samsung(40100, manufacturer = "OnePlus")).matched shouldBe false
    }

    @Test
    fun `missing protect battery key disables control and asks for contribution`() {
        val support = modern.probe(samsung(80000, hasKey = false))

        support.matched shouldBe true
        support.controlEnabled shouldBe false
        support.detail shouldBe R.string.adapter_detail_samsung_no_key
        support.contributionWanted shouldBe true
    }

    @Test
    fun `secondary user disables control without asking for contribution`() {
        val support = modern.probe(samsung(80000, systemUser = false))

        support.matched shouldBe true
        support.controlEnabled shouldBe false
        support.detail shouldBe R.string.adapter_detail_secondary_user
        support.contributionWanted shouldBe false
    }

    @Test
    fun `qualified device is control enabled`() {
        val support = modern.probe(samsung(80000))

        support.controlEnabled shouldBe true
        support.detail shouldBe R.string.adapter_detail_samsung_ready
        support.contributionWanted shouldBe false
    }

    @Test
    fun `modern read maps all verified states`() = runTest {
        suspend fun observed(protect: String?, threshold: String? = null): ChargeObservation {
            val values = mutableMapOf<String, String>()
            protect?.let { values[SamsungChargingAdapter.KEY_PROTECT_BATTERY] = it }
            threshold?.let { values[SamsungChargingAdapter.KEY_THRESHOLD] = it }
            return modern.read(FakeBackend(values = values))
        }

        observed("0") shouldBe ChargeObservation.Verified(ChargePolicy.Unrestricted, BackendKind.DIRECT_WSS)
        observed("3") shouldBe ChargeObservation.Verified(ChargePolicy.PauseAtFull, BackendKind.DIRECT_WSS)
        observed("1") shouldBe ChargeObservation.Verified(ChargePolicy.FixedLimit(80), BackendKind.DIRECT_WSS)
        observed("1", "85") shouldBe ChargeObservation.Verified(ChargePolicy.FixedLimit(85), BackendKind.DIRECT_WSS)
        observed("1", "95") shouldBe ChargeObservation.Verified(ChargePolicy.FixedLimit(95), BackendKind.DIRECT_WSS)
    }

    @Test
    fun `modern read rejects out of domain and malformed values`() = runTest {
        suspend fun observed(protect: String?, threshold: String? = null): ChargeObservation {
            val values = mutableMapOf<String, String>()
            protect?.let { values[SamsungChargingAdapter.KEY_PROTECT_BATTERY] = it }
            threshold?.let { values[SamsungChargingAdapter.KEY_THRESHOLD] = it }
            return modern.read(FakeBackend(values = values))
        }

        // Unknown mode values (e.g. an unverified One UI generation's "2") never verify and
        // must carry the machine-readable unrecognized flag so session start can refuse.
        observed("2").shouldBeInstanceOf<ChargeObservation.Unknown>().unrecognizedValue shouldBe true
        observed("garbage").shouldBeInstanceOf<ChargeObservation.Unknown>().unrecognizedValue shouldBe true
        observed(null).shouldBeInstanceOf<ChargeObservation.Unknown>().unrecognizedValue shouldBe true
        // Threshold must be a valid tick when the key is present; only absence defaults to 80.
        observed("1", "75").shouldBeInstanceOf<ChargeObservation.Unknown>().unrecognizedValue shouldBe true
        observed("1", "100").shouldBeInstanceOf<ChargeObservation.Unknown>().unrecognizedValue shouldBe true
        observed("1", "abc").shouldBeInstanceOf<ChargeObservation.Unknown>().unrecognizedValue shouldBe true
    }

    @Test
    fun `threshold is irrelevant for off and pause modes`() = runTest {
        val backend = FakeBackend(
            values = mutableMapOf(
                SamsungChargingAdapter.KEY_PROTECT_BATTERY to "3",
                SamsungChargingAdapter.KEY_THRESHOLD to "garbage",
            ),
        )

        modern.read(backend) shouldBe ChargeObservation.Verified(ChargePolicy.PauseAtFull, BackendKind.DIRECT_WSS)
    }

    @Test
    fun `unreadable settings never verify`() = runTest {
        modern.read(FakeBackend(readable = false)).shouldBeInstanceOf<ChargeObservation.Unknown>()
        legacy.read(FakeBackend(readable = false)).shouldBeInstanceOf<ChargeObservation.Unknown>()
    }

    @Test
    fun `modern fixed limit writes threshold before mode`() = runTest {
        val backend = FakeBackend()

        modern.apply(ChargePolicy.FixedLimit(90), backend) shouldBe true

        backend.writes shouldContainExactly listOf(
            SettingMutation(SettingNamespace.GLOBAL, SamsungChargingAdapter.KEY_THRESHOLD, "90"),
            SettingMutation(SettingNamespace.GLOBAL, SamsungChargingAdapter.KEY_PROTECT_BATTERY, "1"),
        )
    }

    @Test
    fun `modern pause and unrestricted write only the mode key`() = runTest {
        val pauseBackend = FakeBackend()
        modern.apply(ChargePolicy.PauseAtFull, pauseBackend) shouldBe true
        pauseBackend.writes shouldContainExactly listOf(
            SettingMutation(SettingNamespace.GLOBAL, SamsungChargingAdapter.KEY_PROTECT_BATTERY, "3"),
        )

        val offBackend = FakeBackend()
        modern.apply(ChargePolicy.Unrestricted, offBackend) shouldBe true
        offBackend.writes shouldContainExactly listOf(
            SettingMutation(SettingNamespace.GLOBAL, SamsungChargingAdapter.KEY_PROTECT_BATTERY, "0"),
        )
    }

    @Test
    fun `modern rejects unsupported policies without writing`() = runTest {
        val backend = FakeBackend()

        modern.apply(ChargePolicy.Adaptive, backend) shouldBe false
        modern.apply(ChargePolicy.FixedLimit(70), backend) shouldBe false

        backend.writes shouldBe emptyList()
    }

    @Test
    fun `modern apply fails when a write is rejected`() = runTest {
        val backend = FakeBackend(failWrites = setOf(SamsungChargingAdapter.KEY_PROTECT_BATTERY))

        modern.apply(ChargePolicy.FixedLimit(85), backend) shouldBe false
    }

    @Test
    fun `modern apply fails when read back does not match`() = runTest {
        // Writes report success but the value never lands (e.g. vendor coercion).
        val backend = FakeBackend(dropWrites = true)

        modern.apply(ChargePolicy.PauseAtFull, backend) shouldBe false
    }

    @Test
    fun `legacy maps the plain toggle`() = runTest {
        legacy.read(FakeBackend(values = mutableMapOf(SamsungChargingAdapter.KEY_PROTECT_BATTERY to "1"))) shouldBe
            ChargeObservation.Verified(ChargePolicy.FixedLimit(85), BackendKind.DIRECT_WSS)
        legacy.read(FakeBackend(values = mutableMapOf(SamsungChargingAdapter.KEY_PROTECT_BATTERY to "0"))) shouldBe
            ChargeObservation.Verified(ChargePolicy.Unrestricted, BackendKind.DIRECT_WSS)
        // Modern-only values must not decode on a legacy device.
        legacy.read(FakeBackend(values = mutableMapOf(SamsungChargingAdapter.KEY_PROTECT_BATTERY to "3")))
            .shouldBeInstanceOf<ChargeObservation.Unknown>()
    }

    @Test
    fun `legacy never writes the threshold key`() = runTest {
        val backend = FakeBackend()

        legacy.apply(ChargePolicy.FixedLimit(85), backend) shouldBe true
        legacy.apply(ChargePolicy.Unrestricted, backend) shouldBe true
        legacy.apply(ChargePolicy.FixedLimit(80), backend) shouldBe false
        legacy.apply(ChargePolicy.PauseAtFull, backend) shouldBe false

        backend.writes.map { it.key }.toSet() shouldBe setOf(SamsungChargingAdapter.KEY_PROTECT_BATTERY)
    }

    @Test
    fun `session capabilities differ per generation`() {
        modern.sessionOverridePolicy shouldBe ChargePolicy.PauseAtFull
        modern.defaultProtectivePolicy shouldBe ChargePolicy.FixedLimit(80)
        legacy.sessionOverridePolicy shouldBe ChargePolicy.Unrestricted
        legacy.defaultProtectivePolicy shouldBe ChargePolicy.FixedLimit(85)
        modern.verification shouldBe VerificationStrategy.SYNC_READBACK
        legacy.verification shouldBe VerificationStrategy.SYNC_READBACK
        modern.reconnectGestureSupported shouldBe false
        legacy.reconnectGestureSupported shouldBe false
    }

    private class FakeBackend(
        private val readable: Boolean = true,
        private val values: MutableMap<String, String> = mutableMapOf(),
        private val failWrites: Set<String> = emptySet(),
        private val dropWrites: Boolean = false,
    ) : AccessBackend {
        override val kind = BackendKind.DIRECT_WSS
        val writes = mutableListOf<SettingMutation>()

        override suspend fun status() = BackendStatus(true, true, "test".toCaString())
        override suspend fun read(namespace: SettingNamespace, key: String) =
            SettingRead(readable, values[key], if (readable) null else "blocked".toCaString())

        override suspend fun write(mutation: SettingMutation): Boolean {
            if (mutation.key in failWrites) return false
            writes += mutation
            if (!dropWrites) values[mutation.key] = mutation.value
            return true
        }
    }
}
