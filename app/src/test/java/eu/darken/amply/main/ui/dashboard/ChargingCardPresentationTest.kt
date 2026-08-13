package eu.darken.amply.main.ui.dashboard

import eu.darken.amply.battery.core.BatteryReadout
import eu.darken.amply.stats.core.ChargeSessionSummary
import eu.darken.amply.stats.core.ChargingType
import eu.darken.amply.stats.core.StatsLiveSession
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class ChargingCardPresentationTest {

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
        ChargingCardPresentation.from(stats(enabled = false, live = liveSession), plugged) shouldBe
            ChargingCardPresentation.Promo
        ChargingCardPresentation.from(stats(enabled = false, last = lastSession), unplugged) shouldBe
            ChargingCardPresentation.Promo
    }

    @Test
    fun `unavailable wins over everything but promo`() {
        ChargingCardPresentation.from(stats(unavailable = true, loading = true, live = liveSession), plugged) shouldBe
            ChargingCardPresentation.Unavailable
    }

    @Test
    fun `loading wins over connection states`() {
        ChargingCardPresentation.from(stats(loading = true), plugged) shouldBe ChargingCardPresentation.Loading
        ChargingCardPresentation.from(stats(loading = true), unplugged) shouldBe ChargingCardPresentation.Loading
    }

    @Test
    fun `plugged with an open row is live`() {
        ChargingCardPresentation.from(stats(live = liveSession), plugged) shouldBe
            ChargingCardPresentation.Live(session = liveSession)
    }

    @Test
    fun `plugged without a row is connected-without-session`() {
        ChargingCardPresentation.from(stats(), plugged) shouldBe
            ChargingCardPresentation.ConnectedWithoutSession(startFailed = false)
        ChargingCardPresentation.from(stats(startFailed = true), plugged) shouldBe
            ChargingCardPresentation.ConnectedWithoutSession(startFailed = true)
    }

    @Test
    fun `a failed start never claims live, even with an open row`() {
        // A row the recorder resumed after a process restart stays open indefinitely by design. If the
        // service then can't start, no ticks arrive and that row is frozen — showing it as live would
        // present stale numbers as current AND hide the retry that could fix it.
        ChargingCardPresentation.from(stats(live = liveSession, startFailed = true), plugged) shouldBe
            ChargingCardPresentation.ConnectedWithoutSession(startFailed = true)
    }

    @Test
    fun `stale open row never claims live while unplugged`() {
        ChargingCardPresentation.from(stats(live = liveSession, last = lastSession, count = 3), unplugged) shouldBe
            ChargingCardPresentation.Idle(lastSession = lastSession, sessionCount = 3)
    }

    // "No charger reported" and "reported no charger" are different facts. Collapsing the first into
    // the second is right for deciding whether to act, and wrong for describing what is happening —
    // it would flip an in-progress charge to an idle "last charge" teaser.
    @Test
    fun `an unreported plug state is indeterminate, not idle`() {
        val unreported = BatteryReadout(levelPercent = 78, plugged = null)
        ChargingCardPresentation.from(stats(live = liveSession), unreported) shouldBe
            ChargingCardPresentation.Indeterminate
        ChargingCardPresentation.from(stats(last = lastSession, count = 3), unreported) shouldBe
            ChargingCardPresentation.Indeterminate
    }

    @Test
    fun `an absent readout is indeterminate too`() {
        ChargingCardPresentation.from(stats(), null) shouldBe ChargingCardPresentation.Indeterminate
    }

    @Test
    fun `capture state still wins over an unreadable battery`() {
        val unreported = BatteryReadout(levelPercent = 78, plugged = null)
        ChargingCardPresentation.from(stats(enabled = false), unreported) shouldBe ChargingCardPresentation.Promo
        ChargingCardPresentation.from(stats(unavailable = true), unreported) shouldBe
            ChargingCardPresentation.Unavailable
        ChargingCardPresentation.from(stats(loading = true), unreported) shouldBe ChargingCardPresentation.Loading
    }

    @Test
    fun `idle carries the teaser payload`() {
        ChargingCardPresentation.from(stats(last = lastSession, count = 5), unplugged) shouldBe
            ChargingCardPresentation.Idle(lastSession = lastSession, sessionCount = 5)
        ChargingCardPresentation.from(stats(), unplugged) shouldBe
            ChargingCardPresentation.Idle(lastSession = null, sessionCount = 0)
    }

    // Speed buckets. An unknown draw is not a normal one — the headline falls back to plain "Charging".

    @Test
    fun `no measurable draw has no speed`() {
        chargingSpeed(null) shouldBe null
        chargingSpeed(0) shouldBe null
        chargingSpeed(-1) shouldBe null
    }

    @Test
    fun `anything under the AOSP slow bar is slow`() {
        chargingSpeed(1) shouldBe ChargingSpeed.SLOW
        chargingSpeed(4_999) shouldBe ChargingSpeed.SLOW
    }

    @Test
    fun `between the slow and fast bars is normal`() {
        chargingSpeed(5_000) shouldBe ChargingSpeed.NORMAL
        chargingSpeed(7_500) shouldBe ChargingSpeed.NORMAL
    }

    @Test
    fun `above the AOSP fast bar is fast`() {
        chargingSpeed(7_501) shouldBe ChargingSpeed.FAST
        chargingSpeed(15_000) shouldBe ChargingSpeed.FAST
    }

    @Test
    fun `above twice the fast bar is very fast`() {
        chargingSpeed(15_001) shouldBe ChargingSpeed.VERY_FAST
    }
}
