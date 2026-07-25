package eu.darken.amply.main.ui.battery

import eu.darken.amply.battery.core.BatteryReadout
import eu.darken.amply.main.ui.dashboard.StatsDashboardState
import eu.darken.amply.stats.core.ChargeSessionSummary
import eu.darken.amply.stats.core.ChargingType
import eu.darken.amply.stats.core.StatsLiveSession
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class ChargeTeaserStateTest {

    private val liveSession = StatsLiveSession(
        id = 1,
        startedAtWallMillis = 1_000L,
        startedElapsedRealtimeMillis = 500L,
        startPercent = 42,
        partial = false,
        curve = emptyList(),
    )

    private val lastSession = ChargeSessionSummary(
        id = 7,
        startedAtWallMillis = 0L,
        endedAtWallMillis = 3_600_000L,
        durationMillis = 3_600_000L,
        startPercent = 40,
        endPercent = 80,
        chargingType = ChargingType.AC,
        avgPowerMilliwatts = 9_000,
        peakPowerMilliwatts = 18_000,
        minTemperatureTenthsC = 300,
        avgTemperatureTenthsC = 310,
        maxTemperatureTenthsC = 330,
        limitHit = false,
        partial = false,
        fullReachedAtWallMillis = null,
        sealReason = null,
    )

    private val plugged = BatteryReadout(levelPercent = 78, plugged = 1)
    private val unplugged = BatteryReadout(levelPercent = 78, plugged = 0)

    private fun stats(
        enabled: Boolean = true,
        loading: Boolean = false,
        unavailable: Boolean = false,
        startFailed: Boolean = false,
        last: ChargeSessionSummary? = null,
        live: StatsLiveSession? = null,
    ) = StatsDashboardState(
        enabled = enabled,
        loading = loading,
        unavailable = unavailable,
        startFailed = startFailed,
        lastSession = last,
        live = live,
    )

    @Test
    fun `capture off leaves the toggle standing alone`() {
        ChargeTeaserState.from(stats(enabled = false, live = liveSession), plugged) shouldBe
            ChargeTeaserState.CaptureOff
    }

    @Test
    fun `loading is distinct from empty so the empty copy never shows first`() {
        ChargeTeaserState.from(stats(loading = true), plugged) shouldBe ChargeTeaserState.Loading
    }

    @Test
    fun `an outage is not reported as an empty history`() {
        ChargeTeaserState.from(stats(unavailable = true, last = lastSession), unplugged) shouldBe
            ChargeTeaserState.Unavailable
    }

    @Test
    fun `plugged with an open row is live`() {
        ChargeTeaserState.from(stats(live = liveSession), plugged) shouldBe ChargeTeaserState.Live(liveSession)
    }

    @Test
    fun `a stale open row is not shown as this charge once unplugged`() {
        // The recorder seals the row asynchronously, so it briefly survives the unplug. A naive
        // `live ?: lastSession` would keep claiming a charge is in progress on a desk.
        ChargeTeaserState.from(stats(live = liveSession, last = lastSession), unplugged) shouldBe
            ChargeTeaserState.Last(lastSession)
    }

    @Test
    fun `a frozen row after a failed start falls back to the last finished charge`() {
        ChargeTeaserState.from(stats(live = liveSession, last = lastSession, startFailed = true), plugged) shouldBe
            ChargeTeaserState.Last(lastSession)
    }

    @Test
    fun `plugged without any session yet is none, not a frozen live`() {
        ChargeTeaserState.from(stats(live = liveSession, startFailed = true), plugged) shouldBe
            ChargeTeaserState.None
    }

    @Test
    fun `an unreported plug state claims neither this charge nor the last one`() {
        val unreported = BatteryReadout(levelPercent = 78, plugged = null)
        ChargeTeaserState.from(stats(live = liveSession, last = lastSession), unreported) shouldBe
            ChargeTeaserState.Indeterminate
    }

    @Test
    fun `an absent readout is indeterminate`() {
        ChargeTeaserState.from(stats(live = liveSession, last = lastSession), null) shouldBe
            ChargeTeaserState.Indeterminate
    }

    @Test
    fun `capture on with nothing recorded is none`() {
        ChargeTeaserState.from(stats(), unplugged) shouldBe ChargeTeaserState.None
    }
}
