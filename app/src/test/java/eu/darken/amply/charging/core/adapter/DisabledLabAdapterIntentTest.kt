package eu.darken.amply.charging.core.adapter

import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ResolveInfo
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import io.kotest.matchers.shouldBe
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class DisabledLabAdapterIntentTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val adapter = XiaomiLabAdapter()

    @Test
    fun `prefers the power-usage screen when it resolves`() {
        registerActivityFor(Intent.ACTION_POWER_USAGE_SUMMARY)

        adapter.nativeSettingsIntent(context).action shouldBe Intent.ACTION_POWER_USAGE_SUMMARY
    }

    @Test
    fun `falls back to battery saver settings when power usage does not resolve`() {
        // Nothing registered for POWER_USAGE_SUMMARY → the generic fallback is used.
        adapter.nativeSettingsIntent(context).action shouldBe Settings.ACTION_BATTERY_SAVER_SETTINGS
    }

    private fun registerActivityFor(action: String) {
        val resolveInfo = ResolveInfo().apply {
            activityInfo = ActivityInfo().apply {
                packageName = "com.example.settings"
                name = "BatteryActivity"
            }
        }
        shadowOf(context.packageManager).addResolveInfoForIntent(Intent(action), resolveInfo)
    }
}
