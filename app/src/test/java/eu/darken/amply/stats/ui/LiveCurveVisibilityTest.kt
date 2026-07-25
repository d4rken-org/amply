package eu.darken.amply.stats.ui

import eu.darken.amply.stats.core.ChargeCurvePoint
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class LiveCurveVisibilityTest {

    private fun curve(vararg percents: Int?) = percents.mapIndexed { index, percent ->
        ChargeCurvePoint(
            elapsedFromStartMillis = index * 30_000L,
            percent = percent,
            powerMilliwatts = null,
            temperatureTenthsC = null,
        )
    }

    private fun show(curve: List<ChargeCurvePoint>, elapsedMillis: Long = 600_000L) =
        shouldShowLiveCurve(curve, elapsedMillis)

    @Test
    fun `a curve with an adjacent change is drawn`() {
        show(curve(40, 41)) shouldBe true
    }

    @Test
    fun `elapsed time is a hard floor`() {
        show(curve(40, 41), elapsedMillis = CHART_MIN_ELAPSED_MILLIS - 1) shouldBe false
        show(curve(40, 41), elapsedMillis = CHART_MIN_ELAPSED_MILLIS) shouldBe true
    }

    @Test
    fun `a flat curve has nothing to draw`() {
        // The state a device held at its OEM limit reports: plenty of samples, no movement.
        show(curve(80, 80, 80, 80, 80)) shouldBe false
    }

    @Test
    fun `empty and single-point curves are not curves`() {
        show(emptyList()) shouldBe false
        show(curve(80)) shouldBe false
    }

    @Test
    fun `all-null samples are not variation`() {
        show(curve(null, null, null)) shouldBe false
    }

    @Test
    fun `a null transition is not variation`() {
        // Neither direction is a change in value — one side simply wasn't reported.
        show(curve(40, null)) shouldBe false
        show(curve(null, 40)) shouldBe false
        show(curve(null, 40, null)) shouldBe false
    }

    @Test
    fun `distinct values split by a gap draw no line`() {
        // LineChart breaks its path at nulls, so these are two single-point segments — nothing renders.
        show(curve(40, null, 41)) shouldBe false
    }

    @Test
    fun `variation after a gap still counts`() {
        // 41→42 is adjacent, so there is a real segment to draw even though the curve starts broken.
        show(curve(40, null, 41, 42)) shouldBe true
    }

    @Test
    fun `power alone can carry the curve`() {
        val points = listOf(
            ChargeCurvePoint(0L, percent = 80, powerMilliwatts = 12_000, temperatureTenthsC = 300),
            ChargeCurvePoint(30_000L, percent = 80, powerMilliwatts = 9_000, temperatureTenthsC = 300),
        )
        show(points) shouldBe true
    }

    @Test
    fun `temperature alone can carry the curve`() {
        val points = listOf(
            ChargeCurvePoint(0L, percent = 80, powerMilliwatts = 12_000, temperatureTenthsC = 300),
            ChargeCurvePoint(30_000L, percent = 80, powerMilliwatts = 12_000, temperatureTenthsC = 301),
        )
        show(points) shouldBe true
    }

    @Test
    fun `every series constant is not a curve`() {
        val points = listOf(
            ChargeCurvePoint(0L, percent = 80, powerMilliwatts = 12_000, temperatureTenthsC = 300),
            ChargeCurvePoint(30_000L, percent = 80, powerMilliwatts = 12_000, temperatureTenthsC = 300),
        )
        show(points) shouldBe false
    }
}
