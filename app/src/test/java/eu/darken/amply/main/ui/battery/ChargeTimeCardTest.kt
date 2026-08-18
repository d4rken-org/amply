package eu.darken.amply.main.ui.battery

import android.app.Application
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import eu.darken.amply.R
import eu.darken.amply.stats.core.ChargeBandSplit
import eu.darken.amply.stats.core.ChargeTimeBasis
import eu.darken.amply.stats.core.ChargeTimeEstimate
import eu.darken.amply.stats.ui.ChargeTimeState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The card's honesty rules: a countdown is only ever shown while the battery is actually taking
 * charge, an absent target is stated rather than filled in, and our own outage stays distinguishable
 * from an empty history.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "+h2400dp")
class ChargeTimeCardTest {
    @get:Rule
    val compose = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<Application>()

    // Formatting overload only where there are arguments: several of these strings carry a literal
    // "%" and are declared formatted="false", so formatting them would throw.
    private fun string(res: Int, vararg args: Any): String =
        if (args.isEmpty()) context.getString(res) else context.getString(res, *args)

    private val estimate = ChargeTimeEstimate(
        toEightyMillis = 2_040_000,
        toFullMillis = 4_740_000,
        avgSpeedMilliwatts = 11_500,
        split = ChargeBandSplit(
            toFiftyMillis = 2_700_000,
            fiftyToEightyMillis = 1_800_000,
            eightyToHundredMillis = 3_600_000,
        ),
        basedOnSessions = 6,
    )

    private fun ready(
        estimate: ChargeTimeEstimate = this.estimate,
        charging: Boolean = true,
        percent: Int? = 42,
        basis: ChargeTimeBasis = ChargeTimeBasis.SAME_TYPE,
    ) = ChargeTimeState.Ready(estimate = estimate, basis = basis, charging = charging, currentPercent = percent)

    private fun render(state: ChargeTimeState) {
        compose.setContent { ChargeTimeCard(state = state) }
    }

    @Test
    fun `while charging the headline counts down to full`() {
        render(ready())
        compose.onNodeWithText(string(R.string.charge_time_headline_full_countdown, "1h 19m")).assertExists()
        compose.onNodeWithText("34m").assertExists()
        compose.onNodeWithText("11.5 W").assertExists()
    }

    @Test
    fun `unplugged the same figures are a reference, not a countdown`() {
        render(ready(charging = false))
        compose.onNodeWithText(string(R.string.charge_time_headline_full_reference, "1h 19m")).assertExists()
        compose.onAllNodesWithText(string(R.string.charge_time_headline_full_countdown, "1h 19m"))
            .assertCountEquals(0)
    }

    @Test
    fun `held at a limit it reads as a reference too`() {
        // The session is live and the device is on a charger, but the platform reports NOT_CHARGING,
        // so nothing is moving toward the target and a countdown would be a false claim.
        render(ready(charging = false, percent = 80))
        compose.onNodeWithText(string(R.string.charge_time_headline_full_reference, "1h 19m")).assertExists()
    }

    @Test
    fun `an absent full target is stated, never rendered as a number`() {
        render(ready(estimate = estimate.copy(toFullMillis = null)))
        // The headline falls back to the target that does exist...
        compose.onNodeWithText(string(R.string.charge_time_headline_eighty_countdown, "34m")).assertExists()
        // ...and the missing cell says so rather than showing 0m.
        compose.onNodeWithText(string(R.string.charge_time_value_missing)).assertExists()
    }

    @Test
    fun `above eighty percent the To 80 cell is dropped rather than reading zero`() {
        render(ready(percent = 86, estimate = estimate.copy(toEightyMillis = null)))
        compose.onAllNodesWithText(string(R.string.charge_time_to_eighty).uppercase()).assertCountEquals(0)
        compose.onNodeWithText(string(R.string.charge_time_to_full).uppercase()).assertExists()
    }

    @Test
    fun `the trickle note appears when the last stretch dominates`() {
        render(ready())
        compose.onNodeWithText(string(R.string.charge_time_trickle_note)).assertExists()
    }

    @Test
    fun `the trickle note stays away from an unremarkable taper`() {
        // The note explains a slowdown, so it must not appear where there isn't one.
        render(
            ready(
                estimate = estimate.copy(
                    split = ChargeBandSplit(
                        toFiftyMillis = 2_700_000,
                        fiftyToEightyMillis = 1_800_000,
                        eightyToHundredMillis = 1_800_000,
                    ),
                ),
            ),
        )
        compose.onAllNodesWithText(string(R.string.charge_time_trickle_note)).assertCountEquals(0)
    }

    @Test
    fun `only the segments with data are drawn`() {
        render(
            ready(
                estimate = estimate.copy(
                    split = ChargeBandSplit(fiftyToEightyMillis = 1_800_000, eightyToHundredMillis = 3_600_000),
                ),
            ),
        )
        compose.onNodeWithText(string(R.string.charge_time_split_fifty_eighty)).assertExists()
        compose.onAllNodesWithText(string(R.string.charge_time_split_to_fifty)).assertCountEquals(0)
    }

    @Test
    fun `the provenance line names the session count for this charger type`() {
        render(ready())
        compose.onNodeWithText(
            context.resources.getQuantityString(R.plurals.charge_time_provenance_same_type, 6, 6),
        ).assertExists()
    }

    @Test
    fun `a pooled projection says it is pooled`() {
        render(ready(basis = ChargeTimeBasis.POOLED))
        compose.onNodeWithText(
            context.resources.getQuantityString(R.plurals.charge_time_provenance_pooled, 6, 6),
        ).assertExists()
    }

    @Test
    fun `not enough data is a statement about the user's history`() {
        render(ChargeTimeState.NotEnoughData(sessions = 1))
        compose.onNodeWithText(string(R.string.charge_time_not_enough)).assertExists()
    }

    @Test
    fun `an outage is never presented as an empty history`() {
        render(ChargeTimeState.Unavailable)
        compose.onNodeWithText(string(R.string.charge_time_unavailable)).assertExists()
        compose.onAllNodesWithText(string(R.string.charge_time_not_enough)).assertCountEquals(0)
    }

    @Test
    fun `loading shows neither an estimate nor the empty copy`() {
        render(ChargeTimeState.Loading)
        compose.onNodeWithText(string(R.string.charge_time_loading)).assertExists()
        compose.onAllNodesWithText(string(R.string.charge_time_not_enough)).assertCountEquals(0)
    }
}
