package eu.darken.amply.stats.core

import android.os.BatteryManager
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class ChargeBandExtractorTest {

    private val charging = BatteryManager.BATTERY_STATUS_CHARGING
    private val notCharging = BatteryManager.BATTERY_STATUS_NOT_CHARGING

    private fun sample(elapsed: Long, percent: Int?, status: Int? = charging) =
        ChargeStepSample(elapsedMillis = elapsed, percent = percent, batteryStatus = status)

    private fun extract(samples: List<ChargeStepSample>, sessionId: Long = 1L) =
        ChargeBandExtractor.extract(sessionId, ChargingType.AC, samples)

    /** A clean climb at [stepMillis] per percent, from [from] up to (and including) [to]. */
    private fun climb(from: Int, to: Int, stepMillis: Long = 60_000L, start: Long = 0L) =
        (0..(to - from)).map { i -> sample(start + i * stepMillis, from + i) }

    @Test
    fun `only completed steps count, and the session's first one is discarded`() {
        // 40 → 43 gives three transitions, but the 40 → 41 step began before recording did, and 43
        // is never completed because nothing observed 44.
        val observations = extract(climb(40, 43))
        observations.map { it.percentFrom } shouldBe listOf(41, 42)
        observations.map { it.millis } shouldBe listOf(60_000L, 60_000L)
        observations.first().sessionId shouldBe 1L
        observations.first().chargingType shouldBe ChargingType.AC
    }

    @Test
    fun `a step spanning a non-charging sample is discarded`() {
        val observations = extract(
            listOf(
                sample(0, 60),
                sample(60_000, 61),
                // The 61 → 62 step contains a hold, so it describes a pause and not a charge rate.
                sample(90_000, 61, status = notCharging),
                sample(120_000, 62),
                sample(180_000, 63),
            ),
        )
        observations.map { it.percentFrom } shouldBe listOf(62)
    }

    @Test
    fun `a plateau at an OEM limit contributes nothing`() {
        // Recording continues for an hour at 80% and the level never leaves it, so the 80 → 81 step
        // is never completed and no rate is learned from the hold.
        val samples = climb(78, 80) + (1..12).map { i -> sample(120_000 + i * 300_000L, 80, notCharging) }
        extract(samples).map { it.percentFrom } shouldBe listOf(79)
    }

    @Test
    fun `sub-minimum and over-maximum steps are discarded`() {
        val tooFast = extract(
            listOf(sample(0, 50), sample(60_000, 51), sample(61_000, 52), sample(121_000, 53)),
        )
        // The 51 → 52 step took a second, which no charger does; 52 → 53 is normal.
        tooFast.map { it.percentFrom } shouldBe listOf(52)

        val tooSlow = extract(
            listOf(sample(0, 50), sample(60_000, 51), sample(4_000_000, 52), sample(4_060_000, 53)),
        )
        tooSlow.map { it.percentFrom } shouldBe listOf(52)
    }

    @Test
    fun `a null percent breaks the run`() {
        val observations = extract(
            listOf(
                sample(0, 70),
                sample(60_000, 71),
                sample(120_000, null),
                sample(180_000, 72),
                sample(240_000, 73),
                sample(300_000, 74),
            ),
        )
        // Neither 71 → 72 nor 72 → 73 is credited: across the unreadable sample the level could have
        // moved unseen, so the first step after the break has the same problem as a session's first.
        // 73 → 74 counts, because both its ends were observed.
        observations.map { it.percentFrom } shouldBe listOf(73)
    }

    @Test
    fun `a multi-percent jump breaks the run`() {
        val observations = extract(
            listOf(
                sample(0, 20),
                sample(60_000, 21),
                sample(120_000, 25),
                sample(180_000, 26),
                sample(240_000, 27),
            ),
        )
        observations.map { it.percentFrom } shouldBe listOf(26)
    }

    @Test
    fun `time running backwards breaks the run but not the rest of the session`() {
        val observations = extract(
            listOf(
                sample(0, 30),
                sample(60_000, 31),
                // A new clock base: everything before it is incomparable, but what follows is fine.
                sample(10_000, 32),
                sample(70_000, 33),
                sample(130_000, 34),
                sample(190_000, 35),
            ),
        )
        observations.map { it.percentFrom } shouldBe listOf(34)
    }

    @Test
    fun `a level wobble contributes one observation, not two`() {
        val observations = extract(
            listOf(
                sample(0, 79),
                sample(60_000, 80),
                sample(120_000, 79),
                sample(180_000, 80),
                sample(240_000, 81),
                sample(300_000, 82),
            ),
        )
        observations.map { it.percentFrom } shouldBe listOf(80, 81)
        observations.count { it.percentFrom == 80 } shouldBe 1
    }

    @Test
    fun `an empty or single-sample session yields nothing`() {
        extract(emptyList()).shouldBeEmpty()
        extract(listOf(sample(0, 50))).shouldBeEmpty()
    }

    @Test
    fun `each observation carries the durations it actually measured`() {
        val observations = extract(
            listOf(
                sample(0, 10),
                sample(30_000, 11),
                sample(75_000, 12),
                sample(200_000, 13),
            ),
        )
        observations.map { it.percentFrom to it.millis } shouldBe listOf(11 to 45_000L, 12 to 125_000L)
    }
}
