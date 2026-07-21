package eu.darken.amply.main.ui.widget

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import eu.darken.amply.charging.core.ChargePolicy
import io.kotest.matchers.shouldBe
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AmplyWidgetLabelTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `protect button reflects the adapter's protective default`() {
        protectButtonLabel(context, ChargePolicy.FixedLimit(80)) shouldBe "∞80%"
        protectButtonLabel(context, ChargePolicy.FixedLimit(85)) shouldBe "∞85%"
        // Xiaomi: the protective default is heuristic adaptive, not a fixed cap — the label
        // must not claim a permanent 80% limit.
        protectButtonLabel(context, ChargePolicy.Adaptive) shouldBe "∞Auto"
    }
}
