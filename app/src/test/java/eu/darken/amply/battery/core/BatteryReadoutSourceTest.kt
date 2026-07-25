package eu.darken.amply.battery.core

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

class BatteryReadoutSourceTest {

    private val charging = BatteryReadout(levelPercent = 82, plugged = 1, temperatureTenthsC = 310)
    private val later = BatteryReadout(levelPercent = 60, plugged = 0, temperatureTenthsC = 295)

    /** Replays a scripted sequence of reads; `null` entries throw, standing in for a broken reader. */
    private fun script(vararg reads: BatteryReadout?): () -> BatteryReadout {
        var index = 0
        return {
            val next = reads[minOf(index, reads.lastIndex)]
            index++
            next ?: error("reader is broken")
        }
    }

    private suspend fun emissions(read: () -> BatteryReadout, count: Int): List<BatteryReadout> =
        batteryReadouts(intervalMillis = 0L, read = read).take(count).toList()

    @Test
    fun `successful reads pass straight through`() = runTest {
        emissions(script(charging, later), 2) shouldBe listOf(charging, later)
    }

    @Test
    fun `a blip keeps the last good reading rather than flashing empty`() = runTest {
        val states = emissions(script(charging, null, null), 3)
        states[1] shouldBe charging
        states[2] shouldBe charging
    }

    @Test
    fun `a sustained failure stops asserting a stale reading`() = runTest {
        // Tolerance is 2 consecutive failures; the third must not still claim "Charging · 82%".
        val states = emissions(script(charging, null, null, null, null), 5)
        states[0] shouldBe charging
        states[1] shouldBe charging
        states[2] shouldBe charging
        states[3] shouldBe BatteryReadout.UNKNOWN
        states[4] shouldBe BatteryReadout.UNKNOWN
    }

    @Test
    fun `a failure before any success is unknown, never invented`() = runTest {
        emissions(script(null), 1) shouldBe listOf(BatteryReadout.UNKNOWN)
    }

    @Test
    fun `recovery emits the fresh reading, not the pre-failure copy`() = runTest {
        val states = emissions(script(charging, null, null, null, later), 5)
        states[3] shouldBe BatteryReadout.UNKNOWN
        states[4] shouldBe later
    }

    @Test
    fun `cancellation propagates instead of being swallowed as a failed read`() = runTest {
        // The catch-all that absorbs reader failures must not also absorb collector cancellation, or
        // the polling loop would survive its own scope being cancelled.
        shouldThrow<CancellationException> {
            batteryReadouts(intervalMillis = 0L, read = { throw CancellationException("collector gone") })
                .first()
        }
    }

    @Test
    fun `the failure budget resets after a success`() = runTest {
        // fail, fail, succeed, fail, fail — the trailing pair is within budget again, so the last
        // good reading may still be shown rather than collapsing to unknown.
        val states = emissions(script(charging, null, null, later, null, null), 6)
        states[3] shouldBe later
        states[4] shouldBe later
        states[5] shouldBe later
    }
}
