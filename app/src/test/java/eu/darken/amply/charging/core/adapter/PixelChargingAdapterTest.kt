package eu.darken.amply.charging.core.adapter

import eu.darken.amply.charging.core.access.AccessBackend
import eu.darken.amply.charging.core.access.BackendStatus
import eu.darken.amply.charging.core.access.SettingMutation
import eu.darken.amply.charging.core.access.SettingNamespace
import eu.darken.amply.charging.core.access.SettingRead
import eu.darken.amply.R
import eu.darken.amply.charging.core.BackendKind
import eu.darken.amply.charging.core.ChargeObservation
import eu.darken.amply.charging.core.ChargePolicy
import eu.darken.amply.charging.core.DeviceInfo
import eu.darken.amply.common.ca.toCaString
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

class PixelChargingAdapterTest {
    private val adapter = PixelChargingAdapter()

    @Test
    fun `pixel 8 on api 37 is enabled`() {
        val support = adapter.probe(DeviceInfo("Google", "Pixel 8", 37, "test"))

        support.matched shouldBe true
        support.controlEnabled shouldBe true
        support.detail shouldBe R.string.adapter_detail_pixel_ready
    }

    @Test
    fun `pixel 9 pro on api 36 is enabled`() {
        val support = adapter.probe(DeviceInfo("Google", "Pixel 9 Pro", 36, "test"))

        support.matched shouldBe true
        support.controlEnabled shouldBe true
    }

    @Test
    fun `pixel 6a is the oldest enabled model`() {
        adapter.probe(DeviceInfo("Google", "Pixel 6a", 35, "test")).controlEnabled shouldBe true
        adapter.probe(DeviceInfo("Google", "Pixel 6 Pro", 35, "test")).controlEnabled shouldBe false
    }

    @Test
    fun `missing controller or phone capability disables control`() {
        val missingController = DeviceInfo(
            "Google",
            "Pixel 9 Pro",
            36,
            "test",
            hasChargingOptimization = false,
        )
        val tablet = DeviceInfo(
            "Google",
            "Pixel 9 Pro",
            36,
            "test",
            isPhone = false,
        )

        adapter.probe(missingController).controlEnabled shouldBe false
        adapter.probe(tablet).controlEnabled shouldBe false
    }

    @Test
    fun `android 14 remains unsupported`() {
        adapter.probe(DeviceInfo("Google", "Pixel 8", 34, "test")).controlEnabled shouldBe false
    }

    @Test
    fun `fixed limit disables adaptive before selecting mode one`() = runTest {
        val backend = FakeBackend()

        adapter.apply(ChargePolicy.FixedLimit(80), backend) shouldBe true

        backend.writes shouldContainExactly listOf(
            SettingMutation(SettingNamespace.SECURE, PixelChargingAdapter.KEY_ADAPTIVE, "0"),
            SettingMutation(SettingNamespace.SECURE, PixelChargingAdapter.KEY_MODE, "1"),
        )
    }

    @Test
    fun `unrestricted clears mode before adaptive`() = runTest {
        val backend = FakeBackend()

        adapter.apply(ChargePolicy.Unrestricted, backend) shouldBe true

        backend.writes shouldContainExactly listOf(
            SettingMutation(SettingNamespace.SECURE, PixelChargingAdapter.KEY_MODE, "0"),
            SettingMutation(SettingNamespace.SECURE, PixelChargingAdapter.KEY_ADAPTIVE, "0"),
        )
    }

    @Test
    fun `reapply forces a mode change before restoring the fixed limit`() = runTest {
        val backend = FakeBackend()

        adapter.reapply(ChargePolicy.FixedLimit(80), backend) shouldBe true

        backend.writes shouldContainExactly listOf(
            SettingMutation(SettingNamespace.SECURE, PixelChargingAdapter.KEY_MODE, "0"),
            SettingMutation(SettingNamespace.SECURE, PixelChargingAdapter.KEY_ADAPTIVE, "0"),
            SettingMutation(SettingNamespace.SECURE, PixelChargingAdapter.KEY_MODE, "1"),
        )
    }

    @Test
    fun `reapply forces a mode change before restoring adaptive`() = runTest {
        val backend = FakeBackend()

        adapter.reapply(ChargePolicy.Adaptive, backend) shouldBe true

        backend.writes shouldContainExactly listOf(
            SettingMutation(SettingNamespace.SECURE, PixelChargingAdapter.KEY_MODE, "1"),
            SettingMutation(SettingNamespace.SECURE, PixelChargingAdapter.KEY_MODE, "0"),
            SettingMutation(SettingNamespace.SECURE, PixelChargingAdapter.KEY_ADAPTIVE, "1"),
        )
    }

    @Test
    fun `adaptive values decode as verified adaptive policy`() = runTest {
        val backend = FakeBackend(
            values = mutableMapOf(
                PixelChargingAdapter.KEY_MODE to "0",
                PixelChargingAdapter.KEY_ADAPTIVE to "1",
            ),
        )

        adapter.read(backend) shouldBe ChargeObservation.Verified(
            ChargePolicy.Adaptive,
            BackendKind.SHIZUKU,
        )
    }

    @Test
    fun `long life hardware state verifies fixed 80`() {
        PixelChargingAdapter.decodeHardwareState(4, plugged = true) shouldBe ChargeObservation.Verified(
            ChargePolicy.FixedLimit(80),
            BackendKind.BATTERY_HARDWARE,
        )
    }

    @Test
    fun `adaptive hardware state verifies adaptive`() {
        PixelChargingAdapter.decodeHardwareState(5, plugged = true) shouldBe ChargeObservation.Verified(
            ChargePolicy.Adaptive,
            BackendKind.BATTERY_HARDWARE,
        )
    }

    @Test
    fun `normal hardware state does not claim unrestricted`() {
        PixelChargingAdapter.decodeHardwareState(1, plugged = true)
            .shouldBeInstanceOf<ChargeObservation.Unknown>()
    }

    @Test
    fun `unplugged long life state is not verified`() {
        PixelChargingAdapter.decodeHardwareState(4, plugged = false) shouldBe null
    }

    @Test
    fun `unplugged adaptive state is not verified`() {
        PixelChargingAdapter.decodeHardwareState(5, plugged = false) shouldBe null
    }

    @Test
    fun `unplugged temperature state is not reported`() {
        PixelChargingAdapter.decodeHardwareState(3, plugged = false) shouldBe null
    }

    @Test
    fun `unreadable hidden values never become verified`() = runTest {
        val backend = FakeBackend(readable = false)

        adapter.read(backend).shouldBeInstanceOf<ChargeObservation.Unknown>()
    }

    private class FakeBackend(
        private val readable: Boolean = true,
        private val values: MutableMap<String, String> = mutableMapOf(),
    ) : AccessBackend {
        override val kind = BackendKind.SHIZUKU
        val writes = mutableListOf<SettingMutation>()

        override suspend fun status() = BackendStatus(true, true, "test".toCaString())
        override suspend fun read(namespace: SettingNamespace, key: String) =
            SettingRead(readable, values[key], if (readable) null else "blocked".toCaString())

        override suspend fun write(mutation: SettingMutation): Boolean {
            writes += mutation
            values[mutation.key] = mutation.value
            return true
        }
    }
}
