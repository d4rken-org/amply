package eu.darken.amply.common.compose.chart

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.background

/** One point of a [ChartSeries]. A null [y] marks a gap so the line is broken rather than interpolated. */
data class ChartPoint(val x: Float, val y: Float?)

/** A single labelled line, auto-scaled to its own value range (series use different units). */
data class ChartSeries(
    val label: String,
    val color: Color,
    val points: List<ChartPoint>,
)

/**
 * Minimal, dependency-free line chart drawn on a Compose [Canvas]. Each series is normalized to its
 * own min/max (percent, power, and temperature share no unit), so the chart shows curve *shape*, not
 * absolute comparison — the numeric summary alongside it carries the real values. Handles the awkward
 * cases explicitly: empty input renders a placeholder, a single point renders a dot, a zero-range
 * series renders a centered flat line, and null points break the stroke.
 */
@Composable
fun LineChart(
    series: List<ChartSeries>,
    modifier: Modifier = Modifier,
    emptyLabel: String,
) {
    val drawable = series.filter { s -> s.points.any { it.y != null } }
    if (drawable.isEmpty()) {
        Box(
            modifier = modifier
                .fillMaxWidth()
                .height(180.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                emptyLabel,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }

    val globalXRange = xRange(drawable)
    val gridColor = MaterialTheme.colorScheme.outlineVariant

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(180.dp)
            .padding(vertical = 8.dp),
    ) {
        drawBaseline(gridColor)
        drawable.forEach { s -> drawSeries(s, globalXRange) }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        drawable.forEach { s ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(s.color),
                )
                Text(
                    s.label,
                    modifier = Modifier.padding(start = 6.dp),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private fun xRange(series: List<ChartSeries>): ClosedFloatingPointRange<Float> {
    val xs = series.flatMap { s -> s.points.filter { it.y != null }.map { it.x } }
    val min = xs.min()
    val max = xs.max()
    return if (min == max) min..(min + 1f) else min..max
}

private fun DrawScope.drawBaseline(color: Color) {
    drawLine(
        color = color,
        start = Offset(0f, size.height),
        end = Offset(size.width, size.height),
        strokeWidth = 1f,
    )
}

private fun DrawScope.drawSeries(series: ChartSeries, xRange: ClosedFloatingPointRange<Float>) {
    val ys = series.points.mapNotNull { it.y }
    if (ys.isEmpty()) return
    val yMin = ys.min()
    val yMax = ys.max()
    val ySpan = (yMax - yMin).takeIf { it > 0f }
    val xSpan = (xRange.endInclusive - xRange.start).takeIf { it > 0f } ?: 1f

    fun px(x: Float) = ((x - xRange.start) / xSpan) * size.width
    // Higher value → higher on screen; a zero-range series sits centered.
    fun py(y: Float) = if (ySpan == null) size.height / 2f else size.height * (1f - (y - yMin) / ySpan)

    val drawn = series.points.filter { it.y != null }
    if (drawn.size == 1) {
        val p = drawn.first()
        drawCircle(series.color, radius = 4f, center = Offset(px(p.x), py(p.y!!)))
        return
    }

    // Break the path across null gaps rather than interpolating over missing data.
    var path: Path? = null
    for (point in series.points) {
        val y = point.y
        if (y == null) {
            path?.let { drawPath(it, series.color, style = Stroke(width = 4f)) }
            path = null
            continue
        }
        val offset = Offset(px(point.x), py(y))
        if (path == null) {
            path = Path().apply { moveTo(offset.x, offset.y) }
        } else {
            path.lineTo(offset.x, offset.y)
        }
    }
    path?.let { drawPath(it, series.color, style = Stroke(width = 4f)) }
}
