package eu.darken.amply.charging.core.access.shizuku

import androidx.test.core.app.ApplicationProvider
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ChargingControlUserServiceTest {
    private val service = ChargingControlUserService(ApplicationProvider.getApplicationContext())

    @Test
    fun `write rejects arbitrary key before executing a command`() {
        val failure = runCatching {
            service.writeSetting("secure", "totally_unrelated_setting", "1")
        }.exceptionOrNull()

        failure.shouldBeInstanceOf<IllegalArgumentException>()
        failure!!.message shouldContain "allowlisted"
    }

    @Test
    fun `write rejects shell syntax in values`() {
        val failure = runCatching {
            service.writeSetting("secure", "charge_optimization_mode", "1;id")
        }.exceptionOrNull()

        failure.shouldBeInstanceOf<IllegalArgumentException>()
        failure!!.message shouldContain "Invalid setting value"
    }

    @Test
    fun `snapshot rejects unknown namespace`() {
        val failure = runCatching { service.snapshotSettings("vendor") }.exceptionOrNull()

        failure.shouldBeInstanceOf<IllegalArgumentException>()
        failure!!.message shouldContain "namespace"
    }
}
