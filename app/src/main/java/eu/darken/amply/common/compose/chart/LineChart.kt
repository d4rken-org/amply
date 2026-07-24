package eu.darken.amply.common.compose.chart

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** One point of a [ChartSeries]. A null [y] marks a gap so the line is broken rather than interpolated. */
data class ChartPoint(val x: Float, val y: Float?)

/**
 * A single labelled line, auto-scaled to its own value range (series use different units). [rangeLabel]
 * is an optional caller-formatted "min→max unit" string shown in the legend so the reader can recover
 * the absolute span the normalized curve hides.
 */
data class ChartSeries(
    val label: String,
    val color: Color,
    val points: List<ChartPoint>,
    val rangeLabel: String? = null,
)

/**
 * Minimal, dependency-free line chart drawn on a Compose [Canvas]. Each series is normalized to its
 * own min/max (percent, power, and temperature share no unit), so the chart shows curve *shape*, not
 * absolute comparison — the per-series [ChartSeries.rangeLabel] in the legend carries the real span.
 * Handles the awkward cases explicitly: empty input renders a placeholder, a single point renders a
 * dot, a zero-range series renders a centered flat line, and null points break the stroke.
 *
 * When [xAxisFormatter] is supplied, tick labels for the start/mid/end of the *plotted* x-range are
 * rendered under the chart (deduped, so a sub-tick span collapses to one label). The formatter is fed
 * values from the chart's own drawable range, so a label can never disagree with the drawn endpoints.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LineChart(
    series: List<ChartSeries>,
    modifier: Modifier = Modifier,
    emptyLabel: String,
    chartHeight: Dp = 180.dp,
    xAxisFormatter: ((Float) -> String)? = null,
    xAxisContentDescription: String? = null,
) {
    val drawable = series.filter { s -> s.points.any { it.y != null } }
    if (drawable.isEmpty()) {
        Box(
            modifier = modifier
                .fillMaxWidth()
                .height(chartHeight),
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
            .height(chartHeight)
            .padding(vertical = 8.dp),
    ) {
        drawBaseline(gridColor)
        drawable.forEach { s -> drawSeries(s, globalXRange) }
    }

    if (xAxisFormatter != null) {
        XAxisLabels(globalXRange, xAxisFormatter, xAxisContentDescription)
    }

    FlowRow(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
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
                    text = if (s.rangeLabel != null) "${s.label}  ${s.rangeLabel}" else s.label,
                    modifier = Modifier.padding(start = 6.dp),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun XAxisLabels(
    xRange: ClosedFloatingPointRange<Float>,
    formatter: (Float) -> String,
    contentDescription: String?,
) {
    val start = formatter(xRange.start)
    val mid = formatter((xRange.start + xRange.endInclusive) / 2f)
    val end = formatter(xRange.endInclusive)
    val rowModifier = Modifier
        .fillMaxWidth()
        .padding(top = 4.dp)
        .then(if (contentDescription != null) Modifier.semantics { this.contentDescription = contentDescription } else Modifier)

    @Composable
    fun tick(text: String) = Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    when {
        // Start and end already read the same (span below the formatter's resolution): one centered label.
        start == end -> Row(rowModifier, horizontalArrangement = Arrangement.Center) { tick(start) }
        // Middle tick would duplicate an endpoint: drop it and keep the two distinct ends.
        mid == start || mid == end -> Row(rowModifier, horizontalArrangement = Arrangement.SpaceBetween) {
            tick(start)
            tick(end)
        }
        else -> Row(rowModifier, horizontalArrangement = Arrangement.SpaceBetween) {
            tick(start)
            tick(mid)
            tick(end)
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
