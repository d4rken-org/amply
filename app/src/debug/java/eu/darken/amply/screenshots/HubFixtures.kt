// Battery-hub screenshot content. These composables render the hub's tile grid, the per-metric
// detail screen and the charge-time card from crafted fixtures, so the screenshotTest source set can
// capture them to PNGs on the JVM (no device). They live in the debug source set so they never ship
// in a release build, and each has an IDE @Preview for quick iteration. Engineering regression shots
// — they are NOT part of the Play Store screenshot flow (see ScreenshotContent.kt).
package eu.darken.amply.screenshots

import android.os.BatteryManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import eu.darken.amply.battery.core.BatteryReadout
import eu.darken.amply.common.compose.PreviewWrapper
import eu.darken.amply.main.ui.battery.BatteryHubScreen
import eu.darken.amply.main.ui.battery.BatteryMetric
import eu.darken.amply.main.ui.battery.BatteryMetricDetailScreen
import eu.darken.amply.main.ui.battery.BatteryMetricDetailState
import eu.darken.amply.main.ui.battery.ChargeTeaserState
import eu.darken.amply.main.ui.battery.ChargeTimeCard
import eu.darken.amply.stats.core.ChargeBandSplit
import eu.darken.amply.stats.core.ChargeCurvePoint
import eu.darken.amply.stats.core.ChargeSessionSummary
import eu.darken.amply.stats.core.ChargeTimeBasis
import eu.darken.amply.stats.core.ChargeTimeEstimate
import eu.darken.amply.stats.core.ChargingType
import eu.darken.amply.stats.core.CurveMetricAvailability
import eu.darken.amply.stats.core.MetricStats
import eu.darken.amply.stats.core.StatsSealReason
import eu.darken.amply.stats.ui.ChargeTimeState

// -- Content composables (one per screenshot) --------------------------------------------------

// Recording on with a finished charge behind it: every metric that varies carries a sparkline, and
// voltage — recorded but flat — deliberately carries none while still being tappable.
@Composable
internal fun HubTileGridContent() = PreviewWrapper {
    HubShot(
        curve = hubCurve(),
        chargeTime = ChargeTimeState.Ready(
            estimate = chargeTimeEstimate,
            basis = ChargeTimeBasis.SAME_TYPE,
            charging = true,
            currentPercent = 82,
        ),
    )
}

// Recording on but nothing recorded yet: the same six tiles, live values only, none navigable.
@Composable
internal fun HubTileGridEmptyContent() = PreviewWrapper {
    HubShot(
        curve = emptyList(),
        teaser = ChargeTeaserState.None,
        chargeTime = ChargeTimeState.NotEnoughData(sessions = 0),
    )
}

// One metric of one session: its latest value, its curve on a real axis, and the statistics taken
// from the raw samples (deliberately wider than the plotted curve).
@Composable
internal fun MetricDetailContent() = PreviewWrapper {
    BatteryMetricDetailScreen(
        state = BatteryMetricDetailState(
            metric = BatteryMetric.POWER,
            sessionMissing = false,
            curve = hubCurve(),
            stats = MetricStats(min = 1_850, avg = 9_400, max = 19_200, sampleCount = 214),
        ),
        onBack = {},
    )
}

// The charge-time card with a full projection: a countdown while charging, the three figures, the
// where-the-time-goes bar, and the taper note.
@Composable
internal fun ChargeTimeReadyContent() = PreviewWrapper {
    Column(
        modifier = Modifier.padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ChargeTimeCard(
            state = ChargeTimeState.Ready(
                estimate = chargeTimeEstimate,
                basis = ChargeTimeBasis.SAME_TYPE,
                charging = true,
                currentPercent = 42,
            ),
        )
        // The same figures off the charger: a reference, never a countdown.
        ChargeTimeCard(
            state = ChargeTimeState.Ready(
                estimate = chargeTimeEstimate,
                basis = ChargeTimeBasis.POOLED,
                charging = false,
                currentPercent = 42,
            ),
        )
    }
}

// Recording is on but too little has been observed to project anything yet.
@Composable
internal fun ChargeTimeNotEnoughContent() = PreviewWrapper {
    Column(
        modifier = Modifier.padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ChargeTimeCard(state = ChargeTimeState.NotEnoughData(sessions = 1))
        ChargeTimeCard(state = ChargeTimeState.Loading)
        ChargeTimeCard(state = ChargeTimeState.Unavailable)
    }
}

// -- Shared renderer + fixtures ----------------------------------------------------------------

internal val chargeTimeEstimate = ChargeTimeEstimate(
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

@Composable
private fun HubShot(
    curve: List<ChargeCurvePoint>,
    teaser: ChargeTeaserState = ChargeTeaserState.Last(hubLastSession),
    chargeTime: ChargeTimeState = ChargeTimeState.Loading,
) = BatteryHubScreen(
    readout = hubReadout,
    captureEnabled = true,
    teaser = teaser,
    showProBadge = false,
    onBack = {},
    onOpenHistory = {},
    onEnableCapture = {},
    onOpenSession = {},
    onOpenMetric = {},
    curve = curve,
    availability = CurveMetricAvailability.of(curve),
    chargeTime = chargeTime,
)

internal val hubReadout = BatteryReadout(
    levelPercent = 82,
    status = BatteryManager.BATTERY_STATUS_CHARGING,
    plugged = BatteryManager.BATTERY_PLUGGED_AC,
    health = BatteryManager.BATTERY_HEALTH_GOOD,
    technology = "Li-ion",
    temperatureTenthsC = 314,
    voltageMillivolts = 4_185,
    currentNowMicroamps = 1_250_000,
    chargeCounterMicroampHours = 3_800_000,
    cycleCount = 142,
    maxChargingCurrentMicroamps = 2_000_000,
    maxChargingVoltageMicrovolts = 9_000_000,
)

/** A rising level with a tapering current, a warming battery, and a deliberately flat voltage. */
internal fun hubCurve(): List<ChargeCurvePoint> = (0..24).map { i ->
    ChargeCurvePoint(
        elapsedFromStartMillis = i * 300_000L,
        percent = (42 + i * 2).coerceAtMost(100),
        powerMilliwatts = (18_000 - i * 600).coerceAtLeast(2_000),
        temperatureTenthsC = 300 + i,
        voltageMillivolts = 4_185,
        currentNowMicroamps = (2_400_000 - i * 80_000).coerceAtLeast(300_000),
    )
}

internal val hubLastSession = ChargeSessionSummary(
    id = 7,
    startedAtWallMillis = 0,
    endedAtWallMillis = 0,
    durationMillis = 7_200_000,
    startPercent = 42,
    endPercent = 90,
    chargingType = ChargingType.AC,
    avgPowerMilliwatts = 12_000,
    peakPowerMilliwatts = 27_000,
    minTemperatureTenthsC = 300,
    avgTemperatureTenthsC = 312,
    maxTemperatureTenthsC = 324,
    limitHit = false,
    partial = false,
    fullReachedAtWallMillis = null,
    sealReason = StatsSealReason.UNPLUGGED,
)

// -- IDE previews (design-time only; the screenshotTest wrappers drive the actual capture) ------

@Preview(name = "Hub tile grid", showBackground = true, device = DS)
@Composable
private fun PreviewHubTileGrid() = HubTileGridContent()

@Preview(name = "Hub tile grid · nothing recorded", showBackground = true, device = DS)
@Composable
private fun PreviewHubTileGridEmpty() = HubTileGridEmptyContent()

@Preview(name = "Metric detail", showBackground = true, device = DS)
@Composable
private fun PreviewMetricDetail() = MetricDetailContent()

@Preview(name = "Charge time · ready", showBackground = true, device = DS)
@Composable
private fun PreviewChargeTimeReady() = ChargeTimeReadyContent()

@Preview(name = "Charge time · not enough data", showBackground = true, device = DS)
@Composable
private fun PreviewChargeTimeNotEnough() = ChargeTimeNotEnoughContent()
