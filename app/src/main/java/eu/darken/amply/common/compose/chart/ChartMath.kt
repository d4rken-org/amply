package eu.darken.amply.common.compose.chart

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * A resolved numeric axis: [min]..[max] with evenly-stepped [ticks]. By construction
 * `ticks.first() == min` and `ticks.last() == max` (within fp tolerance) and the spacing is constant.
 * Pure data — no Android/Compose types — so the layout math is JVM-unit-testable.
 */
data class AxisScale(
    val min: Float,
    val max: Float,
    val ticks: List<Float>,
)

/**
 * "Nice numbers" axis for a data span: picks a step from {1,2,5}×10^k so ticks read cleanly, then
 * floors/ceils the data range onto that step and clamps the result into [bounds].
 *
 * - [tickTarget] is the desired tick *count* (drives step granularity); the produced count is roughly
 *   [tickTarget] and never more than a small margin above it.
 * - [bounds] clamps min/max so a physically-capped series can't overshoot (a constant-100% series must
 *   not yield a 101% tick, a near-zero floor must not go negative). Callers must pass data already
 *   inside [bounds]; the clamp only trims the rounded axis endpoints.
 * - [minStep] forces a floor on the step (e.g. 0.1 W display precision → two labels can never format
 *   identically); the coerced step is still a nice {1,2,5}×10^k number. This is **best-effort** — see
 *   the coverage guarantee paragraph; the terminal fallback relaxes it when constraints conflict.
 *
 * A degenerate span ([dataMin] == [dataMax]) is expanded by one step (clamped into [bounds]).
 *
 * **Coverage guarantee**: the returned scale always covers the (already-bounds-coerced) input —
 * `min <= dataMin` and `max >= dataMax` (compared at Float precision) — while still never emitting a
 * tick outside [bounds]. Without [bounds] the floor/ceil rounding covers the data unconditionally.
 * With [bounds]: when snapping the rounded endpoints onto the [bounds]-aligned grid would cut a bound
 * below the data (a bound that is not step-aligned), the computation is retried with the next smaller
 * nice step; if [minStep] blocks any smaller step, the grid is anchored at the violated bound. Should
 * that grid still fail to cover both endpoints or land a tick outside [bounds] — the caller contract
 * guarantees the coerced data lies within [bounds] — the result is the two-tick terminal fallback
 * `[bounds.start, bounds.endInclusive]`, which trivially covers the data with even spacing. Containment
 * is enforced at **strict Float precision** on the materialized ticks (a tick whose Float cast lands even
 * one ULP outside the Float bounds triggers the same terminal fallback). A hard [MAX_TICKS] cap is a final
 * guard against any dense-grid pathology: an over-dense grid falls back to the terminal `[bounds]` scale
 * (or, with no [bounds], to a two-tick `[min, max]` scale).
 *
 * **Property precedence when constraints conflict**: data coverage and [bounds] containment are hard
 * guarantees; the {1,2,5}×10^k nice step and [minStep] are best-effort and are both relaxed in the
 * terminal fallback (whose single step is the [bounds] span). Callers that need [minStep] strictly must
 * pass a [bounds]/[minStep] combination that admits a nice grid — an aligned bound at least 2×[minStep]
 * wide.
 */
fun niceScale(
    dataMin: Float,
    dataMax: Float,
    tickTarget: Int = 4,
    bounds: ClosedFloatingPointRange<Float>? = null,
    minStep: Float? = null,
): AxisScale {
    require(dataMin.isFinite() && dataMax.isFinite()) { "niceScale inputs must be finite" }
    require(dataMin <= dataMax) { "dataMin ($dataMin) must be <= dataMax ($dataMax)" }
    require(tickTarget >= 2) { "tickTarget ($tickTarget) must be >= 2" }
    if (minStep != null) {
        require(minStep.isFinite() && minStep > 0f) { "minStep ($minStep) must be finite and > 0" }
    }

    val lo = dataMin.toDouble()
    val hi = dataMax.toDouble()
    val degenerate = hi - lo < DEGENERATE_EPS

    // Synthetic span for a constant series so it still resolves to a sensible step.
    val span = if (degenerate) max(max(kotlin.math.abs(lo), kotlin.math.abs(hi)), 1.0) else hi - lo

    val minStepFloor = minStep?.let { niceNum(it.toDouble(), round = false) }
    var step = niceNum(span / (tickTarget - 1), round = true)
    if (minStepFloor != null && step < minStepFloor) step = minStepFloor
    if (step <= 0.0) step = 1.0

    // Resolve the grid; if a non-aligned bound clamped the axis below the data, retry with the next
    // smaller nice step (a finer grid snaps closer to the bounds) until coverage holds or minStep blocks.
    var grid = resolveGrid(lo, hi, step, degenerate, bounds)
    var refinements = 0
    while (!grid.covers(lo, hi) && refinements < MAX_STEP_REFINEMENTS) {
        val smaller = nextSmallerNiceStep(grid.step)
        if (minStepFloor != null && smaller < minStepFloor - GRID_EPS * minStepFloor) break
        grid = resolveGrid(lo, hi, smaller, degenerate, bounds)
        refinements++
    }

    // minStep prevented a fine-enough step: anchor the grid at the violated bound so coverage holds.
    if (!grid.covers(lo, hi) && bounds != null) {
        grid = anchorAtViolatedBound(lo, hi, grid, bounds)
    }

    if (bounds != null) {
        // Terminal fallback: the coerced data lies within [bounds] by the caller contract, so
        // [boundLo, boundHi] always covers it, never emits an out-of-bounds tick, and is trivially even.
        // Use it whenever the computed grid still fails coverage (a clamped anchor that dropped an
        // endpoint) or would place a tick outside [bounds] (the collapse guard overshooting a bound).
        val boundLo = bounds.start.toDouble()
        val boundHi = bounds.endInclusive.toDouble()
        val boundTol = max(max(kotlin.math.abs(boundLo), kotlin.math.abs(boundHi)), 1.0) * GRID_EPS
        val withinBounds = grid.min >= boundLo - boundTol && grid.max <= boundHi + boundTol
        if (!grid.covers(lo, hi) || !withinBounds) {
            return twoTickScale(boundLo, boundHi)
        }
    }

    val scale = buildTicks(grid.min, grid.max, grid.step)

    // Belt-and-braces: never return a pathologically dense axis. Guards against any future refinement
    // edge case producing a grid far finer than [tickTarget] warrants.
    if (scale.ticks.size > MAX_TICKS) {
        return if (bounds != null) {
            twoTickScale(bounds.start.toDouble(), bounds.endInclusive.toDouble())
        } else {
            twoTickScale(grid.min, grid.max)
        }
    }

    // Strict Float containment: the tolerance-based `withinBounds` check above operates on the Double
    // grid coordinates, but the returned ticks are Float. A rounded endpoint that equals a Float bound
    // only to within tolerance can still cast to a tick one ULP outside the Float bound (e.g. tick 1f vs
    // bound 0.9999998f). Reject any tick that lies strictly outside the Float bounds — plain Float
    // comparison, no tolerance — and fall back to the terminal scale, whose ticks ARE the Float bounds.
    if (bounds != null && scale.ticks.any { it !in bounds }) {
        return twoTickScale(bounds.start.toDouble(), bounds.endInclusive.toDouble())
    }
    return scale
}

/** A resolved even grid ([min]..[max] on a constant [step]); the intermediate form of a nice scale. */
private data class Grid(val min: Double, val max: Double, val step: Double)

/**
 * True when the grid spans the data — `min <= dataMin` and `max >= dataMax`, compared at **Float**
 * precision with an ULP-scale relative tolerance that is independent of [step]. A step-shrinking Double
 * tolerance (the old form) never let a non-nice Float bound like `3.14f` count as covered, so refinement
 * spiralled to the finest step; comparing the Float-cast endpoints against the Float data fixes that: a
 * bound that IS the data always reads as covered.
 */
private fun Grid.covers(dataMin: Double, dataMax: Double): Boolean {
    val dMin = dataMin.toFloat()
    val dMax = dataMax.toFloat()
    val tolMin = max(kotlin.math.abs(dMin), 1f) * COVERAGE_TOL_REL
    val tolMax = max(kotlin.math.abs(dMax), 1f) * COVERAGE_TOL_REL
    return min.toFloat() <= dMin + tolMin && max.toFloat() >= dMax - tolMax
}

/** Floors/ceils the data onto [step], clamps onto the [bounds]-aligned grid, and un-collapses the result. */
private fun resolveGrid(
    dataMin: Double,
    dataMax: Double,
    step: Double,
    degenerate: Boolean,
    bounds: ClosedFloatingPointRange<Float>?,
): Grid {
    var axisMin: Double
    var axisMax: Double
    if (degenerate) {
        val base = (dataMin / step).roundToInt() * step
        axisMin = base - step
        axisMax = base + step
    } else {
        axisMin = floor(dataMin / step + GRID_EPS) * step
        axisMax = ceil(dataMax / step - GRID_EPS) * step
    }

    if (bounds != null) {
        val boundLo = bounds.start.toDouble()
        val boundHi = bounds.endInclusive.toDouble()
        // Snap the bound onto the same 0-origin grid so tick spacing stays constant.
        val gridLo = ceil(boundLo / step - GRID_EPS) * step
        val gridHi = floor(boundHi / step + GRID_EPS) * step
        axisMin = axisMin.coerceAtLeast(gridLo)
        axisMax = axisMax.coerceAtMost(gridHi)
    }

    // Guard against a collapsed axis (degenerate value on a grid line, or bounds trimming both ends).
    if (axisMax - axisMin < step - GRID_EPS * step) {
        if (bounds != null && axisMin - step >= bounds.start.toDouble() - GRID_EPS) {
            axisMin -= step
        } else {
            axisMax = axisMin + step
        }
    }

    return Grid(axisMin, axisMax, step)
}

/**
 * Fallback when no in-[bounds] step can both span the data and stay step-aligned to the bounds: pin the
 * grid to whichever bound was cut below the data, so that bound becomes a tick and its end is covered.
 * Ticks march inward by [Grid.step]; the count is capped so no tick escapes [bounds].
 */
private fun anchorAtViolatedBound(
    dataMin: Double,
    dataMax: Double,
    grid: Grid,
    bounds: ClosedFloatingPointRange<Float>,
): Grid {
    val step = grid.step
    val boundLo = bounds.start.toDouble()
    val boundHi = bounds.endInclusive.toDouble()
    val tol = step * GRID_EPS
    val maxSteps = floor((boundHi - boundLo) / step + GRID_EPS).toInt().coerceAtLeast(1)
    return if (grid.max < dataMax - tol) {
        // Upper bound was cut below the data: make boundHi a tick, extend inward toward dataMin.
        val stepsToData = ceil((boundHi - dataMin) / step - GRID_EPS).toInt().coerceAtLeast(1)
        val count = min(stepsToData, maxSteps)
        Grid(min = boundHi - count * step, max = boundHi, step = step)
    } else {
        // Lower bound was cut above the data: make boundLo a tick, extend inward toward dataMax.
        val stepsToData = ceil((dataMax - boundLo) / step - GRID_EPS).toInt().coerceAtLeast(1)
        val count = min(stepsToData, maxSteps)
        Grid(min = boundLo, max = boundLo + count * step, step = step)
    }
}

/** A minimal two-tick [AxisScale] spanning [lo]..[hi] — the terminal fallback (trivially even spacing). */
private fun twoTickScale(lo: Double, hi: Double): AxisScale =
    AxisScale(min = lo.toFloat(), max = hi.toFloat(), ticks = listOf(lo.toFloat(), hi.toFloat()))

/** Materializes an even grid into an [AxisScale] with `ticks.first() == min` / `ticks.last() == max`. */
private fun buildTicks(axisMin: Double, axisMax: Double, step: Double): AxisScale {
    val count = ((axisMax - axisMin) / step).roundToInt().coerceAtLeast(1)
    val ticks = (0..count).map { i -> (axisMin + i * step).toFloat() }
    return AxisScale(min = ticks.first(), max = ticks.last(), ticks = ticks)
}

/** Next lower value in the {1,2,5}×10^k ladder (5→2, 2→1, 1→5×10^(k-1)). */
private fun nextSmallerNiceStep(step: Double): Double {
    val exp = floor(log10(step) + GRID_EPS)
    val base = 10.0.pow(exp)
    val f = (step / base).roundToInt()
    return when {
        f >= 10 -> 5.0 * base
        f >= 5 -> 2.0 * base
        f >= 2 -> 1.0 * base
        else -> 5.0 * base / 10.0
    }
}

/** Smallest {1,2,5}×10^k value at least (round=false) / nearest (round=true) to [value]. */
private fun niceNum(value: Double, round: Boolean): Double {
    if (value <= 0.0) return 1.0
    val exp = floor(log10(value))
    val base = 10.0.pow(exp)
    val f = value / base
    val nf = if (round) {
        when {
            f < 1.5 -> 1.0
            f < 3.0 -> 2.0
            f < 7.0 -> 5.0
            else -> 10.0
        }
    } else {
        when {
            f <= 1.0 -> 1.0
            f <= 2.0 -> 2.0
            f <= 5.0 -> 5.0
            else -> 10.0
        }
    }
    return nf * base
}

/**
 * 1-D placement for end-of-curve value labels. Given each label's desired vertical center and height,
 * returns the resolved center per input slot — or `null` for a label that had to be omitted.
 *
 * Input order is priority order: if the labels cannot all fit ([labelHeights] sum > [canvasHeight]),
 * labels are dropped from the END of the input until the rest fit (deterministic; never overlapping).
 * Kept labels are sorted by desired center (ties by input index), greedily separated so none overlap,
 * and clamped fully inside `[0, canvasHeight]`; that sorted relative order is preserved. Results are
 * returned in the original input order.
 */
fun resolveEndLabels(
    desiredCenters: List<Float>,
    labelHeights: List<Float>,
    canvasHeight: Float,
): List<Float?> {
    require(desiredCenters.size == labelHeights.size) { "desiredCenters and labelHeights must match in size" }
    require(canvasHeight.isFinite() && canvasHeight > 0f) { "canvasHeight ($canvasHeight) must be finite and > 0" }
    require(desiredCenters.all { it.isFinite() }) { "desiredCenters must be finite" }
    require(labelHeights.all { it.isFinite() && it >= 0f }) { "labelHeights must be finite and >= 0" }

    val n = desiredCenters.size
    if (n == 0) return emptyList()

    // Keep the longest input prefix whose heights still fit (drop lowest-priority tail first).
    var kept = 0
    var cumulative = 0f
    for (h in labelHeights) {
        if (cumulative + h <= canvasHeight) {
            cumulative += h
            kept++
        } else {
            break
        }
    }

    val result = MutableList<Float?>(n) { null }
    if (kept == 0) return result

    val order = (0 until kept).sortedWith(compareBy({ desiredCenters[it] }, { it }))
    val h = DoubleArray(kept) { labelHeights[order[it]].toDouble() }
    val pos = DoubleArray(kept) { desiredCenters[order[it]].toDouble() }
    val height = canvasHeight.toDouble()

    // Push down to remove overlaps, honouring desired centers as lower bounds.
    for (i in 1 until kept) {
        val minC = pos[i - 1] + h[i - 1] / 2.0 + h[i] / 2.0
        if (pos[i] < minC) pos[i] = minC
    }
    // Shift the chain up if it ran past the bottom edge.
    if (pos[kept - 1] + h[kept - 1] / 2.0 > height) {
        pos[kept - 1] = height - h[kept - 1] / 2.0
        for (i in kept - 2 downTo 0) {
            val maxC = pos[i + 1] - h[i + 1] / 2.0 - h[i] / 2.0
            if (pos[i] > maxC) pos[i] = maxC
        }
    }
    // Shift the chain down if it ran past the top edge.
    if (pos[0] - h[0] / 2.0 < 0.0) {
        pos[0] = h[0] / 2.0
        for (i in 1 until kept) {
            val minC = pos[i - 1] + h[i - 1] / 2.0 + h[i] / 2.0
            if (pos[i] < minC) pos[i] = minC
        }
    }

    for (i in 0 until kept) result[order[i]] = pos[i].toFloat()
    return result
}

private const val DEGENERATE_EPS = 1e-6
private const val GRID_EPS = 1e-6

/** Relative tolerance for the Float-precision coverage check (~a few Float ULPs), independent of the step. */
private const val COVERAGE_TOL_REL = 1e-6f

/** Upper bound on nice-step refinements while chasing bounds coverage (guards against a runaway loop). */
private const val MAX_STEP_REFINEMENTS = 12

/** Hard cap on the produced tick count; an over-dense grid falls back to a two-tick scale (see [niceScale]). */
private const val MAX_TICKS = 32
