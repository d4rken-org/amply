package eu.darken.amply.common.compose.chart

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.matchers.floats.plusOrMinus
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class ChartMathTest {

    private fun AxisScale.assertWellFormed() {
        ticks.first() shouldBe min
        ticks.last() shouldBe max
        (min < max) shouldBe true
        (ticks.size >= 2) shouldBe true
        val step = ticks[1] - ticks[0]
        (step > 0f) shouldBe true
        for (i in 1 until ticks.size) {
            (ticks[i] - ticks[i - 1]) shouldBe (step plusOrMinus step * 1e-3f)
        }
    }

    // --- niceScale -----------------------------------------------------------------------------

    @Test
    fun `typical span resolves to clean ticks within bounds`() {
        val scale = niceScale(20f, 85f, tickTarget = 4, bounds = 0f..100f)
        scale.assertWellFormed()
        scale.ticks shouldBe listOf(20f, 40f, 60f, 80f, 100f)
        (scale.min <= 20f) shouldBe true
        (scale.max >= 85f) shouldBe true
        scale.ticks.all { it in 0f..100f } shouldBe true
        (scale.ticks.size <= 4 + 1) shouldBe true
    }

    @Test
    fun `degenerate equal values expand to a valid range`() {
        val scale = niceScale(50f, 50f, tickTarget = 4, bounds = 0f..100f, minStep = 1f)
        scale.assertWellFormed()
        (scale.min <= 50f) shouldBe true
        (scale.max >= 50f) shouldBe true
        scale.ticks.all { it in 0f..100f } shouldBe true
    }

    @Test
    fun `constant full charge produces no tick above the upper bound`() {
        val scale = niceScale(100f, 100f, tickTarget = 4, bounds = 0f..100f, minStep = 1f)
        scale.assertWellFormed()
        scale.ticks.all { it <= 100f } shouldBe true
        (scale.max <= 100f) shouldBe true
    }

    @Test
    fun `tiny span below one still steps evenly`() {
        val scale = niceScale(0.2f, 0.5f, tickTarget = 4)
        scale.assertWellFormed()
        (scale.min <= 0.2f) shouldBe true
        (scale.max >= 0.5f) shouldBe true
    }

    @Test
    fun `negative temperature-like span keeps first tick at min`() {
        val scale = niceScale(-10f, 30f, tickTarget = 4)
        scale.assertWellFormed()
        (scale.min <= -10f) shouldBe true
        (scale.max >= 30f) shouldBe true
    }

    @Test
    fun `zero-floor bound never yields a negative tick`() {
        val scale = niceScale(5f, 50f, tickTarget = 4, bounds = 0f..100f)
        scale.assertWellFormed()
        scale.ticks.all { it >= 0f } shouldBe true
    }

    @Test
    fun `minStep coercion keeps ticks at least one step apart`() {
        val scale = niceScale(50_000f, 50_050f, tickTarget = 4, bounds = 0f..250_000f, minStep = 100f)
        scale.assertWellFormed()
        for (i in 1 until scale.ticks.size) {
            ((scale.ticks[i] - scale.ticks[i - 1]) >= 100f) shouldBe true
        }
    }

    @Test
    fun `scale always covers the data range`() {
        val cases = listOf(
            Triple(3f, 97f, 0f..100f),
            Triple(12f, 13f, 0f..100f),
            Triple(0f, 0f, 0f..100f),
        )
        cases.forEach { (lo, hi, bounds) ->
            val scale = niceScale(lo, hi, tickTarget = 4, bounds = bounds, minStep = 1f)
            scale.assertWellFormed()
            (scale.min <= lo) shouldBe true
            (scale.max >= hi) shouldBe true
            (scale.min >= bounds.start) shouldBe true
            (scale.max <= bounds.endInclusive) shouldBe true
        }
    }

    @Test
    fun `constant near a non-aligned upper bound stays covered without overshooting`() {
        val scale = niceScale(240_000f, 240_000f, tickTarget = 4, bounds = 0f..250_000f, minStep = 100f)
        scale.assertWellFormed()
        (scale.min <= 240_000f) shouldBe true
        (scale.max >= 240_000f) shouldBe true
        scale.ticks.all { it in 0f..250_000f } shouldBe true
    }

    @Test
    fun `span reaching a non-aligned upper bound is covered within bounds`() {
        val scale = niceScale(10_000f, 240_000f, tickTarget = 4, bounds = 0f..250_000f)
        scale.assertWellFormed()
        (scale.min <= 10_000f) shouldBe true
        (scale.max >= 240_000f) shouldBe true
        scale.ticks.all { it in 0f..250_000f } shouldBe true
    }

    @Test
    fun `full non-aligned span is covered exactly within bounds`() {
        val scale = niceScale(0f, 250_000f, tickTarget = 4, bounds = 0f..250_000f)
        scale.assertWellFormed()
        (scale.min <= 0f) shouldBe true
        (scale.max >= 250_000f) shouldBe true
        scale.ticks.all { it in 0f..250_000f } shouldBe true
    }

    @Test
    fun `niceScale rejects invalid arguments`() {
        shouldThrow<IllegalArgumentException> { niceScale(0f, 10f, tickTarget = 1) }
        shouldThrow<IllegalArgumentException> { niceScale(Float.NaN, 10f) }
        shouldThrow<IllegalArgumentException> { niceScale(0f, Float.POSITIVE_INFINITY) }
        shouldThrow<IllegalArgumentException> { niceScale(10f, 5f) }
        shouldThrow<IllegalArgumentException> { niceScale(0f, 10f, minStep = 0f) }
        shouldThrow<IllegalArgumentException> { niceScale(0f, 10f, minStep = -1f) }
    }

    @Test
    fun `minStep-blocked span covers both endpoints instead of dropping one`() {
        // Anchoring at the violated upper bound must not abandon dataMin=0 (fell back to [0, 9]).
        val scale = niceScale(0f, 9f, tickTarget = 4, bounds = 0f..9f, minStep = 5f)
        scale.assertWellFormed()
        (scale.min <= 0f) shouldBe true
        (scale.max >= 9f) shouldBe true
        scale.ticks.all { it in 0f..9f } shouldBe true
    }

    @Test
    fun `degenerate zero on a tiny bound never overshoots the upper bound`() {
        // The collapse guard must not extend a full step past boundHi (emitted a 0.5 tick).
        val scale = niceScale(0f, 0f, tickTarget = 4, bounds = 0f..0.1f)
        scale.assertWellFormed()
        scale.ticks.all { it in 0f..0.1f } shouldBe true
        (scale.min <= 0f) shouldBe true
    }

    @Test
    fun `decimal bound equal to the data does not explode the tick count`() {
        // A Float bound whose Double form lands on no nice grid must not spiral to thousands of ticks.
        val scale = niceScale(0f, 3.14f, tickTarget = 4, bounds = 0f..3.14f)
        scale.assertWellFormed()
        (scale.min <= 0f) shouldBe true
        (scale.max >= 3.14f) shouldBe true
        scale.ticks.all { it in 0f..3.14f } shouldBe true
        (scale.ticks.size <= 32) shouldBe true
    }

    @Test
    fun `near-one bound never emits a tick strictly outside the Float bounds`() {
        // The rounded axis endpoint equals the bound only within Double tolerance, but the materialized
        // Float tick would be 1f > 0.9999998f. Strict Float containment forces the terminal fallback.
        val bounds = 0f..0.9999998f
        val scale = niceScale(0f, 0.9999998f, tickTarget = 4, bounds = bounds)
        scale.assertWellFormed()
        scale.ticks.all { it in bounds } shouldBe true
        (scale.min <= 0f) shouldBe true
        (scale.max >= 0.9999998f) shouldBe true
    }

    @Test
    fun `bounded scales stay covered, in-bounds, bounded in count, and evenly spaced`() {
        data class Case(
            val dataMin: Float,
            val dataMax: Float,
            val bounds: ClosedFloatingPointRange<Float>,
            val minStep: Float?,
        )

        val cases = listOf(
            Case(0f, 9f, 0f..9f, 5f),
            Case(0f, 0f, 0f..0.1f, null),
            Case(0f, 3.14f, 0f..3.14f, null),
            Case(0f, 2.718f, 0f..2.718f, null),
            Case(20f, 85f, 0f..100f, null),
            Case(5f, 50f, 0f..100f, 1f),
            Case(0f, 250_000f, 0f..250_000f, null),
            Case(10_000f, 240_000f, 0f..250_000f, null),
            Case(240_000f, 240_000f, 0f..250_000f, 100f),
            Case(0f, 1f, 0f..1f, null),
            Case(0.1f, 0.9f, 0f..1f, null),
            Case(33f, 67f, 0f..100f, null),
            Case(1f, 99f, 0f..100f, null),
            Case(0f, 7f, 0f..7f, 3f),
            Case(0f, 0.9999998f, 0f..0.9999998f, null),
        )

        cases.forEach { c ->
            val scale = niceScale(c.dataMin, c.dataMax, tickTarget = 4, bounds = c.bounds, minStep = c.minStep)
            scale.assertWellFormed()

            val boundLo = c.bounds.start
            val boundHi = c.bounds.endInclusive
            val cMin = c.dataMin.coerceIn(boundLo, boundHi)
            val cMax = c.dataMax.coerceIn(boundLo, boundHi)
            val covTol = maxOf(kotlin.math.abs(cMin), kotlin.math.abs(cMax), 1f) * 1e-3f
            val boundTol = maxOf(kotlin.math.abs(boundLo), kotlin.math.abs(boundHi), 1f) * 1e-4f

            withClue("covers coerced data for $c") {
                (scale.min <= cMin + covTol) shouldBe true
                (scale.max >= cMax - covTol) shouldBe true
            }
            withClue("ticks within bounds for $c") {
                scale.ticks.all { it in (boundLo - boundTol)..(boundHi + boundTol) } shouldBe true
            }
            withClue("ticks strictly within Float bounds for $c") {
                scale.ticks.all { it in c.bounds } shouldBe true
            }
            withClue("tick count in 2..32 for $c") {
                (scale.ticks.size in 2..32) shouldBe true
            }
        }
    }

    // --- resolveEndLabels ----------------------------------------------------------------------

    @Test
    fun `already-separated labels are returned unchanged`() {
        val out = resolveEndLabels(listOf(10f, 50f, 90f), listOf(10f, 10f, 10f), 120f)
        out shouldBe listOf(10f, 50f, 90f)
    }

    @Test
    fun `two overlapping labels are pushed exactly apart`() {
        val out = resolveEndLabels(listOf(50f, 55f), listOf(10f, 10f), 100f)
        out[0]!! shouldBe (50f plusOrMinus 1e-3f)
        out[1]!! shouldBe (60f plusOrMinus 1e-3f)
        (out[1]!! - out[0]!!) shouldBe (10f plusOrMinus 1e-3f)
    }

    @Test
    fun `label clamps inside the top edge`() {
        val out = resolveEndLabels(listOf(0f), listOf(20f), 100f)
        out[0]!! shouldBe (10f plusOrMinus 1e-3f)
    }

    @Test
    fun `label clamps inside the bottom edge`() {
        val out = resolveEndLabels(listOf(100f), listOf(20f), 100f)
        out[0]!! shouldBe (90f plusOrMinus 1e-3f)
    }

    @Test
    fun `unsorted input keeps input order with sorted relative order`() {
        val out = resolveEndLabels(listOf(80f, 20f, 50f), listOf(10f, 10f, 10f), 200f)
        out shouldBe listOf(80f, 20f, 50f)
        // Sorted-by-desired relative order preserved: 20 < 50 < 80.
        (out[1]!! < out[2]!! && out[2]!! < out[0]!!) shouldBe true
    }

    @Test
    fun `ties are resolved deterministically by input index`() {
        val out = resolveEndLabels(listOf(50f, 50f), listOf(10f, 10f), 100f)
        out[0]!! shouldBe (50f plusOrMinus 1e-3f)
        out[1]!! shouldBe (60f plusOrMinus 1e-3f)
    }

    @Test
    fun `three labels piled at one y are separated without overlap`() {
        val out = resolveEndLabels(listOf(50f, 50f, 50f), listOf(10f, 10f, 10f), 100f)
        out.forEach { (it != null) shouldBe true }
        val sorted = out.filterNotNull().sorted()
        for (i in 1 until sorted.size) {
            ((sorted[i] - sorted[i - 1]) >= 10f - 1e-3f) shouldBe true
        }
    }

    @Test
    fun `a label taller than the canvas is omitted`() {
        val out = resolveEndLabels(listOf(50f), listOf(200f), 100f)
        out shouldBe listOf<Float?>(null)
    }

    @Test
    fun `lowest-priority labels are dropped until the rest fit`() {
        val out = resolveEndLabels(
            listOf(10f, 50f, 90f, 130f),
            listOf(40f, 40f, 40f, 40f),
            100f,
        )
        (out[0] != null) shouldBe true
        (out[1] != null) shouldBe true
        out[2] shouldBe null
        out[3] shouldBe null
    }

    @Test
    fun `resolveEndLabels rejects invalid arguments`() {
        shouldThrow<IllegalArgumentException> { resolveEndLabels(listOf(1f, 2f), listOf(1f), 100f) }
        shouldThrow<IllegalArgumentException> { resolveEndLabels(listOf(Float.NaN), listOf(1f), 100f) }
        shouldThrow<IllegalArgumentException> { resolveEndLabels(listOf(1f), listOf(-1f), 100f) }
        shouldThrow<IllegalArgumentException> { resolveEndLabels(listOf(1f), listOf(1f), 0f) }
    }
}
