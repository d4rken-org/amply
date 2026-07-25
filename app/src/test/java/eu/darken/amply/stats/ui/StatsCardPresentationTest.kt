package eu.darken.amply.stats.ui

import eu.darken.amply.battery.core.BatteryReadout
import eu.darken.amply.stats.core.ChargeSessionSummary
import eu.darken.amply.stats.core.ChargingType
import eu.darken.amply.stats.core.StatsLiveSession
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class StatsCardPresentationTest {

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
        count: Int = 0,
        live: StatsLiveSession? = null,
    ) = StatsDashboardState(
        enabled = enabled,
        loading = loading,
        unavailable = unavailable,
        startFailed = startFailed,
        lastSession = last,
        sessionCount = count,
        live = live,
    )

    @Test
    fun `disabled always yields the promo card - even plugged with an open row`() {
        StatsCardPresentation.from(stats(enabled = false, live = liveSession), plugged) shouldBe
            StatsCardPresentation.Promo
        StatsCardPresentation.from(stats(enabled = false, last = lastSession), unplugged) shouldBe
            StatsCardPresentation.Promo
    }

    @Test
    fun `unavailable wins over everything but promo`() {
        StatsCardPresentation.from(stats(unavailable = true, loading = true, live = liveSession), plugged) shouldBe
            StatsCardPresentation.Unavailable
    }

    @Test
    fun `loading wins over connection states`() {
        StatsCardPresentation.from(stats(loading = true), plugged) shouldBe StatsCardPresentation.Loading
        StatsCardPresentation.from(stats(loading = true), unplugged) shouldBe StatsCardPresentation.Loading
    }

    @Test
    fun `plugged with an open row is live`() {
        StatsCardPresentation.from(stats(live = liveSession), plugged) shouldBe
            StatsCardPresentation.Live(session = liveSession, battery = plugged)
    }

    @Test
    fun `plugged without a row is connected-without-session`() {
        StatsCardPresentation.from(stats(), plugged) shouldBe
            StatsCardPresentation.ConnectedWithoutSession(battery = plugged, startFailed = false)
        StatsCardPresentation.from(stats(startFailed = true), plugged) shouldBe
            StatsCardPresentation.ConnectedWithoutSession(battery = plugged, startFailed = true)
    }

    @Test
    fun `a failed start never claims live, even with an open row`() {
        // A row the recorder resumed after a process restart stays open indefinitely by design. If the
        // service then can't start, no ticks arrive and that row is frozen — showing it as live would
        // present stale numbers as current AND hide the retry that could fix it.
        StatsCardPresentation.from(stats(live = liveSession, startFailed = true), plugged) shouldBe
            StatsCardPresentation.ConnectedWithoutSession(battery = plugged, startFailed = true)
    }

    @Test
    fun `stale open row never claims live while unplugged`() {
        StatsCardPresentation.from(stats(live = liveSession, last = lastSession, count = 3), unplugged) shouldBe
            StatsCardPresentation.Idle(lastSession = lastSession, sessionCount = 3)
    }

    @Test
    fun `null plugged collapses conservatively to not connected`() {
        val unreported = BatteryReadout(levelPercent = 78, plugged = null)
        StatsCardPresentation.from(stats(live = liveSession), unreported) shouldBe
            StatsCardPresentation.Idle(lastSession = null, sessionCount = 0)
        StatsCardPresentation.from(stats(), null) shouldBe
            StatsCardPresentation.Idle(lastSession = null, sessionCount = 0)
    }

    @Test
    fun `idle carries the teaser payload`() {
        StatsCardPresentation.from(stats(last = lastSession, count = 5), unplugged) shouldBe
            StatsCardPresentation.Idle(lastSession = lastSession, sessionCount = 5)
        StatsCardPresentation.from(stats(), unplugged) shouldBe
            StatsCardPresentation.Idle(lastSession = null, sessionCount = 0)
    }
}
