package eu.darken.amply.common.compose.chart

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import eu.darken.amply.common.compose.AmplyPreview
import eu.darken.amply.common.compose.PreviewWrapper

const val SPARKLINE_TEST_TAG = "chart.sparkline"

/**
 * A single self-normalized series with no axes, labels or legend — the shape of one metric, small
 * enough to sit inside a stat tile.
 *
 * Deliberately **not** [LineChart] with everything switched off: that component always renders its
 * legend `FlowRow` and reserves an end-label gutter, neither of which can be disabled, so at tile
 * size the result would be mostly chrome.
 *
 * The API takes real [ChartPoint]s rather than a bare list of values because battery samples are not
 * evenly spaced in time (the recorder samples on level change as well as on its timer) — spacing
 * them equally would draw a shape the session never had. X is elapsed-from-start.
 *
 * Nothing is drawn unless the series has an **adjacent** non-null pair with **different** values —
 * the same rule `StatsCurveChart` gates its live curve on. A self-normalized series with no range
 * would otherwise render as a flat line at the canvas midpoint, which reads as a plotted trend but
 * is really the "no range" fallback; and a path broken at nulls draws nothing at all. Callers can
 * assert presence/absence via [SPARKLINE_TEST_TAG], which only the drawn case carries.
 */
@Composable
fun Sparkline(
    points: List<ChartPoint>,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary,
    height: Dp = 28.dp,
) {
    if (!points.hasDrawableVariation()) return

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .testTag(SPARKLINE_TEST_TAG),
    ) {
        val drawn = points.filter { it.y != null }
        val xs = drawn.map { it.x }
        val ys = drawn.map { it.y!! }
        val xMin = xs.min()
        val xSpan = (xs.max() - xMin).takeIf { it > 0f } ?: 1f
        val yMin = ys.min()
        val ySpan = (ys.max() - yMin).takeIf { it > 0f } ?: 1f
        // Inset by the stroke so the extremes aren't clipped at the canvas edges.
        val inset = 2f
        val usableHeight = (size.height - inset * 2).coerceAtLeast(1f)

        fun px(x: Float) = (x - xMin) / xSpan * size.width
        fun py(y: Float) = inset + usableHeight * (1f - (y - yMin) / ySpan)

        // Break the path across null gaps rather than interpolating over missing data.
        var path: Path? = null
        points.forEach { point ->
            val y = point.y
            if (y == null) {
                path?.let { drawPath(it, color, style = Stroke(width = 3f, cap = StrokeCap.Round)) }
                path = null
                return@forEach
            }
            val offset = Offset(px(point.x), py(y))
            val current = path
            if (current == null) {
                path = Path().apply { moveTo(offset.x, offset.y) }
            } else {
                current.lineTo(offset.x, offset.y)
            }
        }
        path?.let { drawPath(it, color, style = Stroke(width = 3f, cap = StrokeCap.Round)) }
    }
}

/**
 * True when at least one **adjacent** pair of non-null samples differs — the only case a
 * self-normalized single-series line can actually draw as a curve.
 */
fun List<ChartPoint>.hasDrawableVariation(): Boolean = zipWithNext().any { (first, second) ->
    val a = first.y
    val b = second.y
    a != null && b != null && a != b
}

@AmplyPreview
@Composable
private fun SparklinePreview() = PreviewWrapper {
    Column(
        modifier = Modifier.padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Rising level", style = MaterialTheme.typography.labelSmall)
        Sparkline(points = (0..20).map { ChartPoint(it * 60_000f, 42f + it * 2.4f) })

        Text("Uneven sample spacing, with a gap", style = MaterialTheme.typography.labelSmall)
        Sparkline(
            points = listOf(
                ChartPoint(0f, 18_000f),
                ChartPoint(30_000f, 17_200f),
                ChartPoint(45_000f, 12_400f),
                ChartPoint(600_000f, null),
                ChartPoint(900_000f, 6_100f),
                ChartPoint(1_500_000f, 2_400f),
            ),
            color = MaterialTheme.colorScheme.tertiary,
        )

        // A constant series draws nothing at all rather than a fake midline — this row stays empty.
        Text("Constant (renders nothing)", style = MaterialTheme.typography.labelSmall)
        Sparkline(points = (0..10).map { ChartPoint(it * 60_000f, 4_185f) })
    }
}
