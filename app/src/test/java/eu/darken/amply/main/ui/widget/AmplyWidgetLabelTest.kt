package eu.darken.amply.main.ui.widget

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import eu.darken.amply.charging.core.BackendKind
import eu.darken.amply.charging.core.ChargeObservation
import eu.darken.amply.charging.core.ChargePolicy
import eu.darken.amply.charging.core.ChargingState
import eu.darken.amply.charging.core.enforcement.EnforcementStatus
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

    private fun state(enforcement: EnforcementStatus?) = ChargingState(
        controlEnabled = true,
        enforcement = enforcement,
        observation = ChargeObservation.Verified(ChargePolicy.FixedLimit(80), BackendKind.SHIZUKU),
    )

    @Test
    fun `the widget does not claim a limit while enforcement is unverified`() {
        // The setting reads back verified, which on these builds proves only that the ROM stored it.
        statusLine(
            context = context,
            sessionActive = false,
            settling = false,
            awaitingReplug = false,
            state = state(EnforcementStatus.UNDER_TEST),
            requestedTarget = null,
        ) shouldBe "Limited to 80% · not verified yet"

        // Confirmed, or an adapter the question doesn't apply to: the plain claim, as before.
        listOf(EnforcementStatus.CONFIRMED, null).forEach { enforcement ->
            statusLine(
                context = context,
                sessionActive = false,
                settling = false,
                awaitingReplug = false,
                state = state(enforcement),
                requestedTarget = null,
            ) shouldBe "Limited to 80%"
        }
    }
}
