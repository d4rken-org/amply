package eu.darken.amply.charging.core.adapter

import android.content.Context
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
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Pins the user-facing reasons [NothingChargingAdapter.read] gives for states it refuses to decode. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class NothingChargingAdapterReasonTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val adapter = NothingChargingAdapter()

    @Test
    fun `an absent key explains that battery health was never set`() = runTest {
        val observed = adapter.read(FakeBackend(value = null))

        observed.shouldBeInstanceOf<ChargeObservation.Unknown>()
        observed.unrecognizedValue shouldBe true
        observed.reason.get(context) shouldBe context.getString(R.string.charging_reason_nothing_never_set)
    }

    @Test
    fun `an unqualified value names the raw setting`() = runTest {
        val observed = adapter.read(FakeBackend(value = "1"))

        observed.shouldBeInstanceOf<ChargeObservation.Unknown>()
        observed.unrecognizedValue shouldBe true
        observed.reason.get(context) shouldContain "nt_battery_health=1"
    }

    private class FakeBackend(private val value: String?) : AccessBackend {
        override val kind = BackendKind.SHIZUKU

        override suspend fun status() = BackendStatus(true, true, "test".toCaString())
        override suspend fun read(namespace: SettingNamespace, key: String) = SettingRead(true, value, null)
        override suspend fun write(mutation: SettingMutation): Boolean = false
    }
}
