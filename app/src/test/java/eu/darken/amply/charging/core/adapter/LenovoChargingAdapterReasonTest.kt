package eu.darken.amply.charging.core.adapter

import android.content.Context
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import eu.darken.amply.R
import eu.darken.amply.charging.core.BackendKind
import eu.darken.amply.charging.core.ChargeObservation
import eu.darken.amply.charging.core.access.AccessBackend
import eu.darken.amply.charging.core.access.BackendStatus
import eu.darken.amply.charging.core.access.SettingMutation
import eu.darken.amply.charging.core.access.SettingNamespace
import eu.darken.amply.charging.core.access.SettingRead
import eu.darken.amply.common.ca.toCaString
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Pins the user-facing reasons [LenovoChargingAdapter.read] gives for states it refuses to decode. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class LenovoChargingAdapterReasonTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val adapter = LenovoChargingAdapter()

    @Test
    fun `an absent key explains that battery protection was never set`() = runTest {
        val observed = adapter.read(FakeBackend(value = null))

        observed.shouldBeInstanceOf<ChargeObservation.Unknown>()
        observed.unrecognizedValue shouldBe true
        observed.reason.get(context) shouldBe context.getString(R.string.charging_reason_lenovo_never_set)
    }

    @Test
    fun `an unknown value names the raw setting`() = runTest {
        val observed = adapter.read(FakeBackend(value = "2"))

        observed.shouldBeInstanceOf<ChargeObservation.Unknown>()
        observed.unrecognizedValue shouldBe true
        observed.reason.get(context) shouldContain "battery_protected_charge=2"
    }

    @Test
    fun `native changes are observed on the system key`() {
        adapter.observedSettingUris shouldContainExactly listOf(Settings.System.getUriFor("battery_protected_charge"))
    }

    private class FakeBackend(private val value: String?) : AccessBackend {
        override val kind = BackendKind.SHIZUKU

        override suspend fun status() = BackendStatus(true, true, "test".toCaString())
        override suspend fun read(namespace: SettingNamespace, key: String) = SettingRead(true, value, null)
        override suspend fun write(mutation: SettingMutation): Boolean = false
    }
}
