package eu.darken.amply.common.ca

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import eu.darken.amply.R
import eu.darken.amply.charging.core.ChargePolicy
import io.kotest.matchers.shouldBe
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Guards the string resources whose runtime formatting a compile pass cannot catch: literal-percent
 * escaping (`%%`) in argument-bearing strings, and the completed-recordings plural (including the
 * English zero case, which resolves to the `other` category, not a dedicated `zero` form).
 */
@RunWith(RobolectricTestRunner::class)
class StringResourceFormatTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `charge policy fixed label escapes the literal percent`() {
        ChargePolicy.FixedLimit(80).label.get(context) shouldBe "Limit to 80%"
    }

    @Test
    fun `dashboard and widget fixed labels escape the literal percent`() {
        context.getString(R.string.dashboard_policy_fixed, 80) shouldBe "80% limit"
        context.getString(R.string.widget_label_limited, 80) shouldBe "Limited to 80%"
    }

    @Test
    fun `multi-argument device line formats model and api level`() {
        context.getString(R.string.dashboard_device_line, "Pixel 8", 36) shouldBe "Pixel 8 · Android API 36"
    }

    @Test
    fun `completed-recordings plural covers one, many, and the English zero case`() {
        val res = context.resources
        res.getQuantityString(R.plurals.settings_support_recordings, 1, 1) shouldBe "1 completed recording"
        res.getQuantityString(R.plurals.settings_support_recordings, 2, 2) shouldBe "2 completed recordings"
        res.getQuantityString(R.plurals.settings_support_recordings, 0, 0) shouldBe "0 completed recordings"
    }
}
