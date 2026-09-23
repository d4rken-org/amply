package eu.darken.amply.battery.core

import eu.darken.amply.battery.core.CurrentUnitInference.merge
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class CurrentUnitInferenceTest {

    private fun CurrentUnitState.observe(
        raw: Int?,
        at: Long,
        plugged: Int? = 0,
        interactive: Boolean = true,
    ): CurrentUnitState = CurrentUnitInference.observe(
        state = this,
        rawCurrent = raw,
        plugged = plugged,
        interactive = interactive,
        nowElapsedMillis = at,
    )

    /** Three valid unplugged interactive readings spanning exactly the minimum window. */
    private fun CurrentUnitState.observeMilliStreak(raw: Int = -300, from: Long = 0L): CurrentUnitState =
        observe(raw, from).observe(raw, from + 30_000).observe(raw, from + 60_000)

    private enum class Kind { MILLI_EVIDENCE, NEUTRAL, MICRO_PROOF, ABSENT }

    @Test
    fun `magnitude boundaries`() {
        val table = listOf(
            9_999 to Kind.MILLI_EVIDENCE,
            1 to Kind.MILLI_EVIDENCE,
            10_000 to Kind.NEUTRAL,
            19_999 to Kind.NEUTRAL,
            20_000 to Kind.MICRO_PROOF,
            99_999_999 to Kind.MICRO_PROOF,
            100_000_000 to Kind.NEUTRAL,
            Int.MAX_VALUE to Kind.NEUTRAL,
        )
        for ((magnitude, kind) in table) {
            for (raw in listOf(magnitude, -magnitude)) {
                assertKind(raw, kind)
            }
        }
        assertKind(0, Kind.ABSENT)
        assertKind(null, Kind.ABSENT)
        assertKind(Int.MIN_VALUE, Kind.ABSENT)
    }

    private fun assertKind(raw: Int?, kind: Kind) {
        // A single reading: MICRO iff it is a microamp proof, and never a unit otherwise.
        val single = CurrentUnitState.UNKNOWN.observe(raw, at = 0)
        single.unit shouldBe if (kind == Kind.MICRO_PROOF) CurrentUnit.MICRO else CurrentUnit.UNKNOWN
        // Milliamp evidence is the only kind that sustains a streak into MILLI.
        val streak = CurrentUnitState.UNKNOWN.observe(raw, 0).observe(raw, 30_000).observe(raw, 60_000)
        streak.unit shouldBe when (kind) {
            Kind.MILLI_EVIDENCE -> CurrentUnit.MILLI
            Kind.MICRO_PROOF -> CurrentUnit.MICRO
            Kind.NEUTRAL, Kind.ABSENT -> CurrentUnit.UNKNOWN
        }
        if (kind != Kind.MILLI_EVIDENCE && kind != Kind.MICRO_PROOF) {
            streak.streakCount shouldBe 0
            streak.streakStartElapsedMillis shouldBe null
        }
    }

    @Test
    fun `S20 FE unplugged interactive sequence learns MILLI`() {
        val first = CurrentUnitState.UNKNOWN.observe(-254, 0)
        first shouldBe CurrentUnitState(CurrentUnit.UNKNOWN, 0L, 1, 0L)

        val second = first.observe(-313, 30_000)
        second shouldBe CurrentUnitState(CurrentUnit.UNKNOWN, 0L, 2, 30_000L)

        second.observe(-438, 60_000) shouldBe CurrentUnitState(CurrentUnit.MILLI, null, 0, 60_000L)
    }

    @Test
    fun `three readings inside the span stay UNKNOWN until a fourth crosses it`() {
        val three = CurrentUnitState.UNKNOWN
            .observe(-254, 0)
            .observe(-313, 20_000)
            .observe(-438, 59_999)
        three.unit shouldBe CurrentUnit.UNKNOWN
        three.streakCount shouldBe 3

        three.observe(-300, 60_000).unit shouldBe CurrentUnit.MILLI
    }

    @Test
    fun `a long span with too few readings stays UNKNOWN`() {
        val two = CurrentUnitState.UNKNOWN.observe(-254, 0).observe(-313, 600_000)
        two.unit shouldBe CurrentUnit.UNKNOWN
        two.streakCount shouldBe 2
    }

    @Test
    fun `Tab A9+ reading proves MICRO immediately in any state`() {
        val states = listOf(
            Triple(0, true, "unplugged interactive"),
            Triple(0, false, "unplugged screen off"),
            Triple(1, true, "AC"),
            Triple(2, false, "USB"),
            Triple(4, true, "wireless"),
        )
        for ((plugged, interactive, _) in states) {
            CurrentUnitState.UNKNOWN.observe(-215_000, 0, plugged, interactive).unit shouldBe CurrentUnit.MICRO
        }
        CurrentUnitState.UNKNOWN.observe(-215_000, 0, plugged = null).unit shouldBe CurrentUnit.MICRO
    }

    @Test
    fun `plugged small readings at a limit hold never learn MILLI`() {
        for (plugged in listOf(1, 2, 4)) {
            for (interactive in listOf(true, false)) {
                var state = CurrentUnitState.UNKNOWN
                for (i in 0..13) {
                    state = state.observe(i, at = i * 30_000L, plugged = plugged, interactive = interactive)
                }
                state.unit shouldBe CurrentUnit.UNKNOWN
                state.streakCount shouldBe 0
            }
        }
    }

    @Test
    fun `unplugged but not interactive readings never learn MILLI`() {
        var state = CurrentUnitState.UNKNOWN
        for (i in 0 until 10) {
            state = state.observe(-300, at = i * 30_000L, interactive = false)
        }
        state.unit shouldBe CurrentUnit.UNKNOWN
        state.streakCount shouldBe 0
    }

    @Test
    fun `unknown plug state never learns MILLI`() {
        var state = CurrentUnitState.UNKNOWN
        for (i in 0 until 10) {
            state = state.observe(-300, at = i * 30_000L, plugged = null)
        }
        state.unit shouldBe CurrentUnit.UNKNOWN
        state.streakCount shouldBe 0
    }

    @Test
    fun `a non-evidence reading resets the streak`() {
        val breakers = listOf<(CurrentUnitState) -> CurrentUnitState>(
            { it.observe(-15_000, 40_000) },
            { it.observe(-150_000_000, 40_000) },
            { it.observe(0, 40_000) },
            { it.observe(null, 40_000) },
            { it.observe(Int.MIN_VALUE, 40_000) },
            { it.observe(-300, 40_000, plugged = 2) },
            { it.observe(-300, 40_000, plugged = null) },
            { it.observe(-300, 40_000, interactive = false) },
        )
        for (breaker in breakers) {
            val two = CurrentUnitState.UNKNOWN.observe(-300, 0).observe(-300, 30_000)
            two.streakCount shouldBe 2

            val broken = breaker(two)
            broken.unit shouldBe CurrentUnit.UNKNOWN
            broken.streakCount shouldBe 0
            broken.streakStartElapsedMillis shouldBe null
            broken.lastObservedElapsedMillis shouldBe 40_000L

            val fresh = broken.observe(-300, 70_000)
            fresh.streakCount shouldBe 1
            fresh.streakStartElapsedMillis shouldBe 70_000L
            // The old streak would have satisfied both thresholds here; the fresh one must not.
            fresh.unit shouldBe CurrentUnit.UNKNOWN
        }
    }

    @Test
    fun `a backwards clock resets the streak even when still after its start`() {
        val two = CurrentUnitState.UNKNOWN.observe(-300, 0).observe(-300, 60_000)
        two.streakCount shouldBe 2

        val back = two.observe(-300, 30_000)
        back.unit shouldBe CurrentUnit.UNKNOWN
        back.streakCount shouldBe 1
        back.streakStartElapsedMillis shouldBe 30_000L
        back.lastObservedElapsedMillis shouldBe 30_000L
    }

    @Test
    fun `a backwards clock with a non-evidence reading clears the streak`() {
        val two = CurrentUnitState.UNKNOWN.observe(-300, 0).observe(-300, 60_000)
        val back = two.observe(-15_000, 30_000)
        back.streakCount shouldBe 0
        back.streakStartElapsedMillis shouldBe null
    }

    @Test
    fun `lastObserved tracks every observation`() {
        CurrentUnitState.UNKNOWN.observe(null, 5).lastObservedElapsedMillis shouldBe 5L
        CurrentUnitState.UNKNOWN.observe(-215_000, 7).lastObservedElapsedMillis shouldBe 7L
        val milli = CurrentUnitState.UNKNOWN.observeMilliStreak()
        milli.observe(-15_000, 90_000).lastObservedElapsedMillis shouldBe 90_000L
        val micro = CurrentUnitState.UNKNOWN.observe(-215_000, 0)
        micro.observe(-300, 3).lastObservedElapsedMillis shouldBe 3L
    }

    @Test
    fun `MICRO is terminal`() {
        var state = CurrentUnitState.UNKNOWN.observe(-215_000, 0)
        for (i in 1..10) {
            state = state.observe(-300, at = i * 30_000L)
        }
        state.unit shouldBe CurrentUnit.MICRO
        state.streakCount shouldBe 0
        state.streakStartElapsedMillis shouldBe null
    }

    @Test
    fun `MILLI ignores everything but a microamp proof`() {
        var state = CurrentUnitState.UNKNOWN.observeMilliStreak()
        state.unit shouldBe CurrentUnit.MILLI
        val readings = listOf(-300, -15_000, -150_000_000, 0, Int.MIN_VALUE, 19_999)
        readings.forEachIndexed { i, raw ->
            state = state.observe(raw, at = 90_000L + i * 30_000L, plugged = 1, interactive = false)
        }
        state.unit shouldBe CurrentUnit.MILLI
        state.streakCount shouldBe 0
    }

    @Test
    fun `MILLI flips to MICRO on a 20_000 reading`() {
        val milli = CurrentUnitState.UNKNOWN.observeMilliStreak()
        milli.unit shouldBe CurrentUnit.MILLI

        milli.observe(20_000, 90_000, plugged = 2, interactive = false) shouldBe
            CurrentUnitState(CurrentUnit.MICRO, null, 0, 90_000L)
    }

    @Test
    fun `a false MILLI from low but valid microamp readings flips on the first proof`() {
        // A microamp device idling at single-digit mA draw reads as 3_000..9_000, which looks like milliamps.
        val falseMilli = CurrentUnitState.UNKNOWN
            .observe(-3_200, 0)
            .observe(-4_100, 30_000)
            .observe(-9_000, 60_000)
        falseMilli.unit shouldBe CurrentUnit.MILLI

        val stillMilli = falseMilli.observe(-19_999, 90_000)
        stillMilli.unit shouldBe CurrentUnit.MILLI

        stillMilli.observe(-20_000, 120_000).unit shouldBe CurrentUnit.MICRO
    }

    @Test
    fun `merge precedence`() {
        val table = listOf(
            Triple(CurrentUnit.UNKNOWN, CurrentUnit.UNKNOWN, CurrentUnit.UNKNOWN),
            Triple(CurrentUnit.UNKNOWN, CurrentUnit.MILLI, CurrentUnit.MILLI),
            Triple(CurrentUnit.UNKNOWN, CurrentUnit.MICRO, CurrentUnit.MICRO),
            Triple(CurrentUnit.MILLI, CurrentUnit.UNKNOWN, CurrentUnit.MILLI),
            Triple(CurrentUnit.MILLI, CurrentUnit.MILLI, CurrentUnit.MILLI),
            Triple(CurrentUnit.MILLI, CurrentUnit.MICRO, CurrentUnit.MICRO),
            Triple(CurrentUnit.MICRO, CurrentUnit.UNKNOWN, CurrentUnit.MICRO),
            Triple(CurrentUnit.MICRO, CurrentUnit.MILLI, CurrentUnit.MICRO),
            Triple(CurrentUnit.MICRO, CurrentUnit.MICRO, CurrentUnit.MICRO),
        )
        table.size shouldBe CurrentUnit.entries.size * CurrentUnit.entries.size
        for ((a, b, expected) in table) {
            merge(a, b) shouldBe expected
        }
    }
}
