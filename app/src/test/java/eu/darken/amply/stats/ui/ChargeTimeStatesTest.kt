package eu.darken.amply.stats.ui

import android.os.BatteryManager
import eu.darken.amply.battery.core.BatteryReadout
import eu.darken.amply.stats.core.BandObservation
import eu.darken.amply.stats.core.ChargeTimeBasis
import eu.darken.amply.stats.core.ChargeTimeEstimator
import eu.darken.amply.stats.core.ChargeTimeModelState
import eu.darken.amply.stats.core.ChargingType
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

/**
 * The estimate is folded once and projected many times, so these cover the seam: the model provider
 * must not be touched while recording is off, and the projection must follow the live level.
 */
class ChargeTimeStatesTest {

    private fun steps(sessionId: Long, from: Int, to: Int, millis: Long) =
        (from until to).map { percent ->
            BandObservation(
                sessionId = sessionId,
                chargingType = ChargingType.AC,
                percentFrom = percent,
                millis = millis,
            )
        }

    private val model = ChargeTimeEstimator.buildModel(
        steps(1L, 0, 100, 60_000) + steps(2L, 0, 100, 60_000),
        sessionPowerMilliwatts = mapOf(1L to 10_000, 2L to 12_000),
    )

    private fun readout(percent: Int, status: Int, plugged: Int) = BatteryReadout(
        levelPercent = percent,
        status = status,
        plugged = plugged,
    )

    private val charging = readout(
        percent = 40,
        status = BatteryManager.BATTERY_STATUS_CHARGING,
        plugged = BatteryManager.BATTERY_PLUGGED_AC,
    )

    @Test
    fun `with recording off the model provider is never invoked`() = runTest {
        var invocations = 0
        val provider: () -> Flow<ChargeTimeModelState> = {
            invocations++
            flowOf(ChargeTimeModelState.Ready(model))
        }

        val state = chargeTimeStates(
            captureEnabled = flowOf(false),
            readouts = flowOf(charging),
            model = provider,
        ).first()

        state shouldBe ChargeTimeState.Loading
        // Not a style point: the provider's flow opens stats.db, and a user who never enabled
        // recording must never get one created for them.
        invocations shouldBe 0
    }

    @Test
    fun `the estimate follows the live level without the model changing`() = runTest {
        val readouts = MutableStateFlow(charging)
        val states = chargeTimeStates(
            captureEnabled = flowOf(true),
            readouts = readouts,
            model = { flowOf(ChargeTimeModelState.Ready(model)) },
        )

        val atForty = states.first().shouldBeInstanceOf<ChargeTimeState.Ready>()
        atForty.estimate.toFullMillis shouldBe 60 * 60_000L
        atForty.charging shouldBe true
        atForty.basis shouldBe ChargeTimeBasis.SAME_TYPE

        readouts.value = charging.copy(levelPercent = 90)
        val atNinety = states.first().shouldBeInstanceOf<ChargeTimeState.Ready>()
        atNinety.estimate.toFullMillis shouldBe 10 * 60_000L
    }

    @Test
    fun `a device held at a limit is not counting down`() = runTest {
        val state = chargeTimeStates(
            captureEnabled = flowOf(true),
            readouts = flowOf(charging.copy(status = BatteryManager.BATTERY_STATUS_NOT_CHARGING)),
            model = { flowOf(ChargeTimeModelState.Ready(model)) },
        ).first().shouldBeInstanceOf<ChargeTimeState.Ready>()

        state.charging shouldBe false
    }

    @Test
    fun `an unplugged device is not counting down either`() = runTest {
        val state = chargeTimeStates(
            captureEnabled = flowOf(true),
            readouts = flowOf(
                readout(percent = 64, status = BatteryManager.BATTERY_STATUS_DISCHARGING, plugged = 0),
            ),
            model = { flowOf(ChargeTimeModelState.Ready(model)) },
        ).first().shouldBeInstanceOf<ChargeTimeState.Ready>()

        state.charging shouldBe false
    }

    @Test
    fun `an empty history is not enough data, an outage is unavailable`() = runTest {
        val empty = chargeTimeStates(
            captureEnabled = flowOf(true),
            readouts = flowOf(charging),
            model = { flowOf(ChargeTimeModelState.Ready(ChargeTimeEstimator.buildModel(emptyList()))) },
        ).first()
        empty shouldBe ChargeTimeState.NotEnoughData(sessions = 0)

        val broken = chargeTimeStates(
            captureEnabled = flowOf(true),
            readouts = flowOf(charging),
            model = { flowOf(ChargeTimeModelState.Unavailable) },
        ).first()
        broken shouldBe ChargeTimeState.Unavailable
    }

    @Test
    fun `an unreadable battery cannot be projected from`() = runTest {
        val state = chargeTimeStates(
            captureEnabled = flowOf(true),
            readouts = flowOf(null),
            model = { flowOf(ChargeTimeModelState.Ready(model)) },
        ).first()

        state.shouldBeInstanceOf<ChargeTimeState.NotEnoughData>()
    }
}
