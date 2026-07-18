package eu.darken.amply.charging.core.adapter

import com.google.common.truth.Truth.assertThat
import eu.darken.amply.charging.core.access.AccessBackend
import eu.darken.amply.charging.core.access.BackendStatus
import eu.darken.amply.charging.core.access.SettingMutation
import eu.darken.amply.charging.core.access.SettingNamespace
import eu.darken.amply.charging.core.access.SettingRead
import eu.darken.amply.charging.core.BackendKind
import eu.darken.amply.charging.core.ChargeObservation
import eu.darken.amply.charging.core.ChargePolicy
import eu.darken.amply.charging.core.DeviceInfo
import kotlinx.coroutines.test.runTest
import org.junit.Test

class PixelChargingAdapterTest {
    private val adapter = PixelChargingAdapter()

    @Test
    fun `pixel 8 on api 37 is enabled`() {
        val support = adapter.probe(DeviceInfo("Google", "Pixel 8", 37, "test"))

        assertThat(support.matched).isTrue()
        assertThat(support.controlEnabled).isTrue()
        assertThat(support.detail).contains("Supported Pixel capability")
    }

    @Test
    fun `pixel 9 pro on api 36 is enabled`() {
        val support = adapter.probe(DeviceInfo("Google", "Pixel 9 Pro", 36, "test"))

        assertThat(support.matched).isTrue()
        assertThat(support.controlEnabled).isTrue()
    }

    @Test
    fun `pixel 6a is the oldest enabled model`() {
        assertThat(adapter.probe(DeviceInfo("Google", "Pixel 6a", 35, "test")).controlEnabled)
            .isTrue()
        assertThat(adapter.probe(DeviceInfo("Google", "Pixel 6 Pro", 35, "test")).controlEnabled)
            .isFalse()
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

        assertThat(adapter.probe(missingController).controlEnabled).isFalse()
        assertThat(adapter.probe(tablet).controlEnabled).isFalse()
    }

    @Test
    fun `android 14 remains unsupported`() {
        assertThat(adapter.probe(DeviceInfo("Google", "Pixel 8", 34, "test")).controlEnabled)
            .isFalse()
    }

    @Test
    fun `fixed limit disables adaptive before selecting mode one`() = runTest {
        val backend = FakeBackend()

        assertThat(adapter.apply(ChargePolicy.FixedLimit(80), backend)).isTrue()

        assertThat(backend.writes).containsExactly(
            SettingMutation(SettingNamespace.SECURE, PixelChargingAdapter.KEY_ADAPTIVE, "0"),
            SettingMutation(SettingNamespace.SECURE, PixelChargingAdapter.KEY_MODE, "1"),
        ).inOrder()
    }

    @Test
    fun `unrestricted clears mode before adaptive`() = runTest {
        val backend = FakeBackend()

        assertThat(adapter.apply(ChargePolicy.Unrestricted, backend)).isTrue()

        assertThat(backend.writes).containsExactly(
            SettingMutation(SettingNamespace.SECURE, PixelChargingAdapter.KEY_MODE, "0"),
            SettingMutation(SettingNamespace.SECURE, PixelChargingAdapter.KEY_ADAPTIVE, "0"),
        ).inOrder()
    }

    @Test
    fun `adaptive values decode as verified adaptive policy`() = runTest {
        val backend = FakeBackend(
            values = mutableMapOf(
                PixelChargingAdapter.KEY_MODE to "0",
                PixelChargingAdapter.KEY_ADAPTIVE to "1",
            ),
        )

        assertThat(adapter.read(backend)).isEqualTo(
            ChargeObservation.Verified(ChargePolicy.Adaptive, BackendKind.SHIZUKU),
        )
    }

    @Test
    fun `long life hardware state verifies fixed 80`() {
        assertThat(PixelChargingAdapter.decodeHardwareState(4)).isEqualTo(
            ChargeObservation.Verified(ChargePolicy.FixedLimit(80), BackendKind.BATTERY_HARDWARE),
        )
    }

    @Test
    fun `normal hardware state does not claim unrestricted`() {
        assertThat(PixelChargingAdapter.decodeHardwareState(1))
            .isInstanceOf(ChargeObservation.Unknown::class.java)
    }

    @Test
    fun `unreadable hidden values never become verified`() = runTest {
        val backend = FakeBackend(readable = false)

        assertThat(adapter.read(backend)).isInstanceOf(ChargeObservation.Unknown::class.java)
    }

    private class FakeBackend(
        private val readable: Boolean = true,
        private val values: MutableMap<String, String> = mutableMapOf(),
    ) : AccessBackend {
        override val kind = BackendKind.SHIZUKU
        val writes = mutableListOf<SettingMutation>()

        override suspend fun status() = BackendStatus(true, true, "test")
        override suspend fun read(namespace: SettingNamespace, key: String) =
            SettingRead(readable, values[key], if (readable) null else "blocked")

        override suspend fun write(mutation: SettingMutation): Boolean {
            writes += mutation
            values[mutation.key] = mutation.value
            return true
        }

        override suspend fun snapshot(namespace: SettingNamespace) = emptyMap<String, String>()
    }
}
