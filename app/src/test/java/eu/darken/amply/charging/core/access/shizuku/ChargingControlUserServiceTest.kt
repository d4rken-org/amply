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

    @Test
    fun `writeLineageSetting validates key and value before executing any command`() {
        // Non-allowlisted Lineage key is rejected up-front (no `content` process is ever spawned).
        runCatching {
            service.writeLineageSetting("charging_control_start_time", "0")
        }.exceptionOrNull().let {
            it.shouldBeInstanceOf<IllegalArgumentException>()
            it!!.message shouldContain "allowlisted"
        }
        // Out-of-domain value (schedule mode) is rejected likewise.
        runCatching {
            service.writeLineageSetting("charging_control_mode", "1")
        }.exceptionOrNull().let {
            it.shouldBeInstanceOf<IllegalArgumentException>()
            it!!.message shouldContain "domain"
        }
    }

    @Test
    fun `write policy accepts only in-domain values for every allowlisted key`() {
        // Accepted (validate() returns without throwing; the command itself is not run here).
        SettingWritePolicy.validate("secure", "charge_optimization_mode", "1")
        SettingWritePolicy.validate("secure", "adaptive_charging_enabled", "0")
        SettingWritePolicy.validate("secure", "security_pc_secure_protect_mode_key", "0")
        SettingWritePolicy.validate("secure", "security_pc_secure_protect_mode_key", "1")
        SettingWritePolicy.validate("global", "protect_battery", "3")
        SettingWritePolicy.validate("global", "battery_protection_threshold", "95")
        SettingWritePolicy.validate("system", "regular_charge_protection_switch_state", "1")
    }

    @Test
    fun `write policy rejects out-of-domain values`() {
        listOf(
            Triple("secure", "security_pc_secure_protect_mode_key", "2"),
            Triple("secure", "security_pc_secure_protect_mode_key", "999"),
            Triple("secure", "charge_optimization_mode", "2"),
            Triple("global", "protect_battery", "2"),
            Triple("global", "battery_protection_threshold", "75"),
            Triple("global", "battery_protection_threshold", "100"),
        ).forEach { (namespace, key, value) ->
            val failure = runCatching {
                SettingWritePolicy.validate(namespace, key, value)
            }.exceptionOrNull()
            failure.shouldBeInstanceOf<IllegalArgumentException>()
            failure!!.message shouldContain "domain"
        }
    }

    @Test
    fun `write policy rejects keys outside their own namespace`() {
        // protect_battery is allowlisted in global only — a secure-namespace write must fail.
        val failure = runCatching {
            SettingWritePolicy.validate("secure", "protect_battery", "1")
        }.exceptionOrNull()

        failure.shouldBeInstanceOf<IllegalArgumentException>()
        failure!!.message shouldContain "allowlisted"
    }
}
