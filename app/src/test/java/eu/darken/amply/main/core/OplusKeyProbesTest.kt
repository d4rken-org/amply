package eu.darken.amply.main.core

import android.content.Context
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import eu.darken.amply.charging.core.SettingProbe
import io.kotest.matchers.shouldBe
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Wiring test for the Oplus key probe. The formatting tests hand it enum values, so nothing else would catch the
 * probe reading the wrong key name or the wrong namespace — and the namespace is the easy mistake here, since every
 * other key Amply probes lives in `global` or `secure` while these two are `system`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OplusKeyProbesTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test
    fun `a device with neither key probes absent`() {
        OplusKeyProbes.read(context.contentResolver) shouldBe OplusKeyProbes.UNPROBED
    }

    @Test
    fun `the fixed-cap key is read from the system namespace`() {
        Settings.System.putString(context.contentResolver, "regular_charge_protection_switch_state", "1")

        val probes = OplusKeyProbes.read(context.contentResolver)
        probes.regular shouldBe SettingProbe.PRESENT
        probes.smart shouldBe SettingProbe.ABSENT
    }

    @Test
    fun `the adaptive key is read from the system namespace`() {
        Settings.System.putString(context.contentResolver, "smart_charge_protection_switch_state", "0")

        val probes = OplusKeyProbes.read(context.contentResolver)
        probes.smart shouldBe SettingProbe.PRESENT
        probes.regular shouldBe SettingProbe.ABSENT
    }

    @Test
    fun `presence is reported for an off value too`() {
        // "0" means the feature exists and is switched off — that is still a mapped key, and the report carries
        // presence only, never the value.
        Settings.System.putString(context.contentResolver, "regular_charge_protection_switch_state", "0")

        OplusKeyProbes.read(context.contentResolver).regular shouldBe SettingProbe.PRESENT
    }

    @Test
    fun `the same key in another namespace is not mistaken for the system one`() {
        Settings.Secure.putString(context.contentResolver, "regular_charge_protection_switch_state", "1")
        Settings.Global.putString(context.contentResolver, "smart_charge_protection_switch_state", "1")

        OplusKeyProbes.read(context.contentResolver) shouldBe OplusKeyProbes.UNPROBED
    }
}
