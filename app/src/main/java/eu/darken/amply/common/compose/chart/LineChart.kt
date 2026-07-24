package eu.darken.amply.common.compose.chart

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.absolutePadding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import eu.darken.amply.common.compose.AmplyPreview
import eu.darken.amply.common.compose.PreviewWrapper
import kotlin.math.roundToInt

/** One point of a [ChartSeries]. A null [y] marks a gap so the line is broken rather than interpolated. */
data class ChartPoint(val x: Float, val y: Float?)

/** Which shared Y-axis a series is scaled against; `null` keeps the series self-normalized (shape only). */
enum class YAxisSide { LEFT, RIGHT }

/**
 * A shared Y-axis for every series assigned to one [YAxisSide]. [formatter] renders a tick value to its
 * label; [tickTarget] drives the nice-number step (scale quality); [maxLabels] only thins the *rendered*
 * labels (evenly-spaced subset always including the first and last tick) without changing the scale or
 * the gridlines; [bounds] clamps the axis so a physically-capped series can't overshoot; [minStep] floors
 * the step so two labels can never format identically at the display precision.
 */
data class ChartAxis(
    val formatter: (Float) -> String,
    val tickTarget: Int = 4,
    val maxLabels: Int = Int.MAX_VALUE,
    val bounds: ClosedFloatingPointRange<Float>? = null,
    val minStep: Float? = null,
)

/**
 * A single labelled line. With [axisSide] set (and the matching axis supplied to [LineChart]) the series
 * is scaled against that side's shared axis; otherwise it is normalized to its own min/max (curve *shape*
 * only). [rangeLabel] is an optional caller-formatted "min→max unit" legend string; [endLabel] is an
 * optional value drawn in the right gutter at the curve's end (its last non-null sample).
 */
data class ChartSeries(
    val label: String,
    val color: Color,
    val points: List<ChartPoint>,
    val rangeLabel: String? = null,
    val endLabel: String? = null,
    val axisSide: YAxisSide? = null,
)

/**
 * Minimal, dependency-free line chart drawn on a Compose [Canvas]. Series either share a per-side
 * [ChartAxis] (real, comparable scale with tick labels + gridlines on the left, sparse labels on the
 * right) or self-normalize to their own range (curve shape only) — a series whose [ChartSeries.axisSide]
 * has no matching axis param here falls back to self-normalization.
 *
 * Curves stop short of the right edge; a reserved gutter shows each series' [ChartSeries.endLabel]
 * (colored, collision-resolved, joined to its curve end by a faint dashed leader). Awkward cases are
 * handled explicitly: empty input renders a placeholder, a single point renders a dot, a zero-range
 * series renders a centered flat line, and null points break the stroke.
 *
 * The physical plot block (the Canvas and the x-label row) is pinned to [LayoutDirection.Ltr] via a
 * `CompositionLocalProvider`, and all axis/end labels are measured with `LayoutDirection.Ltr`, so the
 * time axis, its labels, and BiDi-sensitive label strings never flip in RTL locales; the x-label row
 * additionally uses absolute (direction-independent) padding so it stays aligned under the plot. Only
 * the legend below remains direction-aware (its mirroring is correct localization). [chartHeight] sizes
 * only the canvas — the x-label row and legend add their own height. The public [modifier] applies to
 * the whole component.
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
    leftAxis: ChartAxis? = null,
    rightAxis: ChartAxis? = null,
    chartContentDescription: String? = null,
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
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val labelStyle = MaterialTheme.typography.labelSmall
    val measurer = rememberTextMeasurer()

    val leftDrawable = drawable.filter { it.axisSide == YAxisSide.LEFT }
    val rightDrawable = drawable.filter { it.axisSide == YAxisSide.RIGHT }
    val leftScale = if (leftAxis != null && leftDrawable.isNotEmpty()) buildScale(leftDrawable, leftAxis) else null
    val rightScale = if (rightAxis != null && rightDrawable.isNotEmpty()) buildScale(rightDrawable, rightAxis) else null

    // All chart text is measured LTR so BiDi-sensitive labels (e.g. "32.0 °C") never reorder in RTL.
    val leftLabelIndices = if (leftScale != null) thinnedIndices(leftScale.ticks.size, leftAxis!!.maxLabels) else emptyList()
    val leftLabelLayouts = leftLabelIndices.map {
        measurer.measure(leftAxis!!.formatter(leftScale!!.ticks[it]), labelStyle, layoutDirection = LayoutDirection.Ltr)
    }
    val rightLabelIndices = if (rightScale != null) thinnedIndices(rightScale.ticks.size, rightAxis!!.maxLabels) else emptyList()
    val rightLabelLayouts = rightLabelIndices.map {
        measurer.measure(rightAxis!!.formatter(rightScale!!.ticks[it]), labelStyle, layoutDirection = LayoutDirection.Ltr)
    }

    val endSeries = drawable.filter { it.endLabel != null }
    val endLayouts = endSeries.map { measurer.measure(it.endLabel!!, labelStyle, layoutDirection = LayoutDirection.Ltr) }

    val maxLeftLabelW = leftLabelLayouts.maxOfOrNull { it.size.width } ?: 0
    val maxRightLabelW = rightLabelLayouts.maxOfOrNull { it.size.width } ?: 0
    val maxEndLabelW = endLayouts.maxOfOrNull { it.size.width } ?: 0

    val density = LocalDensity.current
    val gap4 = with(density) { 4.dp.toPx() }
    val gap6 = with(density) { 6.dp.toPx() }
    val minPlot = with(density) { 96.dp.toPx() }

    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        val totalW = with(density) { maxWidth.toPx() }

        val leftGutter = if (leftScale != null && maxLeftLabelW > 0) maxLeftLabelW + gap4 else 0f
        var endGutter = if (maxEndLabelW > 0) maxEndLabelW + gap6 else 0f
        var rightGutter = if (rightScale != null && maxRightLabelW > 0) maxRightLabelW + gap4 else 0f
        var drawEndLabels = endGutter > 0f
        var drawRightLabels = rightGutter > 0f

        // Degradation ladder: keep the plot at least 96dp wide by dropping the right-axis labels first,
        // then the end labels (falling back to the previous full-width layout). Deterministic, no partial.
        if (totalW - leftGutter - endGutter - rightGutter < minPlot && drawRightLabels) {
            rightGutter = 0f
            drawRightLabels = false
        }
        if (totalW - leftGutter - endGutter - rightGutter < minPlot && drawEndLabels) {
            endGutter = 0f
            drawEndLabels = false
        }

        val plotLeft = leftGutter
        val plotRight = (totalW - endGutter - rightGutter).coerceAtLeast(plotLeft + 1f)

        Column(modifier = Modifier.fillMaxWidth()) {
            // Pin the physical plot (Canvas + x-label row) to LTR so the time axis and its labels never
            // mirror in RTL, matching the LTR-drawn curves. The legend below stays direction-aware.
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                Canvas(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(chartHeight)
                        .padding(vertical = 8.dp)
                        .then(
                            if (chartContentDescription != null) {
                                Modifier.semantics { this.contentDescription = chartContentDescription }
                            } else {
                                Modifier
                            },
                        ),
                ) {
                    val h = size.height
                    val dash = PathEffect.dashPathEffect(floatArrayOf(6f, 6f))

                    // Left-axis gridlines: one per tick across the plot, except the min tick (the baseline).
                    if (leftScale != null) {
                        val span = (leftScale.max - leftScale.min).takeIf { it > 0f } ?: 1f
                        leftScale.ticks.forEachIndexed { i, tick ->
                            if (i == 0) return@forEachIndexed
                            val y = h * (1f - (tick - leftScale.min) / span)
                            drawLine(gridColor, Offset(plotLeft, y), Offset(plotRight, y), strokeWidth = 1f)
                        }
                    }
                    drawLine(gridColor, Offset(plotLeft, h), Offset(plotRight, h), strokeWidth = 1f)

                    drawable.forEach { s ->
                        drawSeriesLine(s, globalXRange, plotLeft, plotRight, pyFor(s, leftScale, rightScale, h))
                    }

                    // Left-axis tick labels: right-aligned in the left gutter, vertically centered + clamped.
                    leftLabelIndices.forEachIndexed { k, idx ->
                        val layout = leftLabelLayouts[k]
                        val span = (leftScale!!.max - leftScale.min).takeIf { it > 0f } ?: 1f
                        val y = h * (1f - (leftScale.ticks[idx] - leftScale.min) / span)
                        val top = (y - layout.size.height / 2f).coerceIn(0f, (h - layout.size.height).coerceAtLeast(0f))
                        drawText(layout, color = labelColor, topLeft = Offset((maxLeftLabelW - layout.size.width).toFloat(), top))
                    }

                    // Right-axis labels: right-aligned at the far right canvas edge, thinned, y-clamped.
                    if (drawRightLabels) {
                        rightLabelIndices.forEachIndexed { k, idx ->
                            val layout = rightLabelLayouts[k]
                            val span = (rightScale!!.max - rightScale.min).takeIf { it > 0f } ?: 1f
                            val y = h * (1f - (rightScale.ticks[idx] - rightScale.min) / span)
                            val top = (y - layout.size.height / 2f).coerceIn(0f, (h - layout.size.height).coerceAtLeast(0f))
                            drawText(layout, color = labelColor, topLeft = Offset(size.width - layout.size.width, top))
                        }
                    }

                    // End-of-curve labels: desired y at each series' last non-null point, collision-resolved.
                    if (drawEndLabels && endSeries.isNotEmpty() && h > 0f) {
                        val desired = endSeries.map { s ->
                            val last = s.points.last { it.y != null }
                            pyFor(s, leftScale, rightScale, h)(last.y!!)
                        }
                        val heights = endLayouts.map { it.size.height.toFloat() }
                        val placed = resolveEndLabels(desired, heights, h)
                        val xSpan = (globalXRange.endInclusive - globalXRange.start).takeIf { it > 0f } ?: 1f
                        endSeries.forEachIndexed { i, s ->
                            val center = placed[i] ?: return@forEachIndexed
                            val layout = endLayouts[i]
                            val labelX = plotRight + gap6
                            val last = s.points.last { it.y != null }
                            val startX = plotLeft + ((last.x - globalXRange.start) / xSpan) * (plotRight - plotLeft)
                            val startY = pyFor(s, leftScale, rightScale, h)(last.y!!)
                            drawLine(
                                color = s.color.copy(alpha = 0.4f),
                                start = Offset(startX, startY),
                                end = Offset(labelX, center),
                                strokeWidth = 1.5f,
                                pathEffect = dash,
                            )
                            drawText(layout, color = s.color, topLeft = Offset(labelX, center - layout.size.height / 2f))
                        }
                    }
                }

                if (xAxisFormatter != null) {
                    XAxisLabels(
                        xRange = globalXRange,
                        formatter = xAxisFormatter,
                        contentDescription = xAxisContentDescription,
                        startPadding = with(density) { plotLeft.toDp() },
                        endPadding = with(density) { (endGutter + rightGutter).toDp() },
                    )
                }
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
    }
}

@Composable
private fun XAxisLabels(
    xRange: ClosedFloatingPointRange<Float>,
    formatter: (Float) -> String,
    contentDescription: String?,
    startPadding: Dp,
    endPadding: Dp,
) {
    val start = formatter(xRange.start)
    val mid = formatter((xRange.start + xRange.endInclusive) / 2f)
    val end = formatter(xRange.endInclusive)
    val rowModifier = Modifier
        .fillMaxWidth()
        .absolutePadding(left = startPadding, right = endPadding)
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

/** Nice-number axis over the union of a side's series, coerced into the axis bounds before scaling. */
private fun buildScale(seriesOnSide: List<ChartSeries>, axis: ChartAxis): AxisScale {
    val ys = seriesOnSide.flatMap { s -> s.points.mapNotNull { it.y } }
    var dataMin = ys.min()
    var dataMax = ys.max()
    axis.bounds?.let {
        dataMin = dataMin.coerceIn(it)
        dataMax = dataMax.coerceIn(it)
    }
    return niceScale(dataMin, dataMax, axis.tickTarget, axis.bounds, axis.minStep)
}

/** Evenly-spaced subset of tick indices (always including the first and last) to render as labels. */
private fun thinnedIndices(tickCount: Int, maxLabels: Int): List<Int> {
    if (tickCount <= 0) return emptyList()
    if (tickCount == 1) return listOf(0)
    val m = maxLabels.coerceAtLeast(2)
    if (m >= tickCount) return (0 until tickCount).toList()
    val picked = LinkedHashSet<Int>()
    for (i in 0 until m) {
        picked.add((i.toDouble() * (tickCount - 1) / (m - 1)).roundToInt())
    }
    return picked.sorted()
}

/** Maps a series value to a canvas y, via its side's shared scale or its own self-normalized range. */
private fun pyFor(
    series: ChartSeries,
    leftScale: AxisScale?,
    rightScale: AxisScale?,
    height: Float,
): (Float) -> Float {
    val scale = when {
        series.axisSide == YAxisSide.LEFT && leftScale != null -> leftScale
        series.axisSide == YAxisSide.RIGHT && rightScale != null -> rightScale
        else -> null
    }
    if (scale != null) {
        val span = (scale.max - scale.min).takeIf { it > 0f } ?: 1f
        return { y -> height * (1f - (y - scale.min) / span) }
    }
    val ys = series.points.mapNotNull { it.y }
    val yMin = ys.min()
    val yMax = ys.max()
    val ySpan = (yMax - yMin).takeIf { it > 0f }
    return { y -> if (ySpan == null) height / 2f else height * (1f - (y - yMin) / ySpan) }
}

private fun xRange(series: List<ChartSeries>): ClosedFloatingPointRange<Float> {
    val xs = series.flatMap { s -> s.points.filter { it.y != null }.map { it.x } }
    val min = xs.min()
    val max = xs.max()
    return if (min == max) min..(min + 1f) else min..max
}

private fun DrawScope.drawSeriesLine(
    series: ChartSeries,
    xRange: ClosedFloatingPointRange<Float>,
    plotLeft: Float,
    plotRight: Float,
    py: (Float) -> Float,
) {
    val drawn = series.points.filter { it.y != null }
    if (drawn.isEmpty()) return
    val xSpan = (xRange.endInclusive - xRange.start).takeIf { it > 0f } ?: 1f

    fun px(x: Float) = plotLeft + ((x - xRange.start) / xSpan) * (plotRight - plotLeft)

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

@AmplyPreview
@Composable
private fun LineChartPreview() = PreviewWrapper {
    val primary = MaterialTheme.colorScheme.primary
    val tertiary = MaterialTheme.colorScheme.tertiary
    val error = MaterialTheme.colorScheme.error
    val xs = (0..10).map { it * 300_000f }
    LineChart(
        series = listOf(
            ChartSeries(
                label = "Level",
                color = primary,
                points = xs.mapIndexed { i, x -> ChartPoint(x, (40 + i * 5).coerceAtMost(100).toFloat()) },
                rangeLabel = "40→90%",
                endLabel = "90%",
                axisSide = YAxisSide.LEFT,
            ),
            ChartSeries(
                label = "Power",
                color = tertiary,
                points = xs.mapIndexed { i, x -> ChartPoint(x, (18_000f - i * 1_200f).coerceAtLeast(6_000f)) },
                rangeLabel = "6.0→18.0 W",
                endLabel = "6.0 W",
                axisSide = YAxisSide.RIGHT,
            ),
            ChartSeries(
                label = "Temperature (shape only)",
                color = error,
                points = xs.mapIndexed { i, x -> ChartPoint(x, 300f + i) },
                rangeLabel = "30.0→31.0 °C",
                endLabel = "31.0 °C",
            ),
        ),
        emptyLabel = "No curve data",
        leftAxis = ChartAxis(formatter = { "${it.roundToInt()}%" }, bounds = 0f..100f, minStep = 1f),
        rightAxis = ChartAxis(
            formatter = { "%.1f W".format(it / 1_000f) },
            maxLabels = 2,
            bounds = 0f..250_000f,
            minStep = 100f,
        ),
        xAxisFormatter = { "${(it / 60_000f).roundToInt()}m" },
        chartContentDescription = "Level: 90%, Power: 6.0 W, Temperature: 31.0 °C",
    )
}

@AmplyPreview
@Composable
private fun LineChartCompactPreview() = PreviewWrapper {
    val primary = MaterialTheme.colorScheme.primary
    val tertiary = MaterialTheme.colorScheme.tertiary
    val xs = (0..8).map { it * 300_000f }
    LineChart(
        series = listOf(
            ChartSeries(
                label = "Level",
                color = primary,
                points = xs.mapIndexed { i, x -> ChartPoint(x, (55 + i * 4).coerceAtMost(100).toFloat()) },
                endLabel = "87%",
            ),
            ChartSeries(
                label = "Power",
                color = tertiary,
                points = xs.mapIndexed { i, x -> ChartPoint(x, (15_000f - i * 900f).coerceAtLeast(4_000f)) },
                endLabel = "7.8 W",
            ),
        ),
        emptyLabel = "No curve data",
        chartHeight = 84.dp,
    )
}
