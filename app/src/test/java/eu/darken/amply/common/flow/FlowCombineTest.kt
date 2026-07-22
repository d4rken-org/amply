package eu.darken.amply.common.flow

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

class FlowCombineTest {

    @Test
    fun `combine passes all six values in order and preserves types`() = runTest {
        val result = combine(
            flowOf(1),
            flowOf("a"),
            flowOf(true),
            flowOf(2L),
            flowOf(3.0),
            flowOf('x'),
        ) { a, b, c, d, e, f -> listOf(a, b, c, d, e, f) }.first()

        result shouldBe listOf(1, "a", true, 2L, 3.0, 'x')
    }

    @Test
    fun `changes in the first flow group propagate`() = runTest {
        var latest: Int? = null
        combine(
            flowOf(1, 2),
            flowOf("a"),
            flowOf(true),
            flowOf(0L),
            flowOf(0.0),
            flowOf('z'),
        ) { a, _, _, _, _, _ -> a }.collect { latest = it }

        latest shouldBe 2
    }

    @Test
    fun `changes in the second flow group propagate`() = runTest {
        // Guards the internal two-group implementation: a change in flow 5 must reach the result.
        var latest: Double? = null
        combine(
            flowOf(1),
            flowOf("a"),
            flowOf(true),
            flowOf(0L),
            flowOf(1.0, 2.0),
            flowOf('z'),
        ) { _, _, _, _, e, _ -> e }.collect { latest = it }

        latest shouldBe 2.0
    }
}
