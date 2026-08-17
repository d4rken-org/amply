package eu.darken.amply.charging.core.enforcement

import eu.darken.amply.charging.core.BackendKind
import eu.darken.amply.charging.core.ChargeObservation
import eu.darken.amply.charging.core.ChargePolicy
import eu.darken.amply.common.ca.toCaString
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import kotlin.random.Random

class EnforcementVerdictEngineTest {

    private val cap = 80

    private fun sample(
        percent: Int,
        elapsed: Long,
        plugged: Boolean = true,
        sessionActive: Boolean = false,
        configured: ChargeObservation? = ChargeObservation.Verified(ChargePolicy.FixedLimit(cap), BackendKind.SHIZUKU),
        policyGeneration: Long = 1L,
        plugSessionId: Long = 1L,
    ) = EnforcementSample(
        adapterId = "lineageos-chargingcontrol-v1",
        buildIdentity = "build-a",
        configured = configured,
        sessionActive = sessionActive,
        plugged = plugged,
        percent = percent,
        policyGeneration = policyGeneration,
        plugSessionId = plugSessionId,
        wallMillis = 1_000L + elapsed,
    )

    /**
     * Feed a sequence and return the FIRST verdict the engine reached, or null. The engine keeps
     * emitting while the condition holds (the recorder is what dedupes against the store), so only
     * the first one is meaningful here.
     */
    private fun run(samples: List<EnforcementSample>): EnforcementVerdict? {
        var progress: EnforcementProgress? = null
        var first: EnforcementVerdict? = null
        samples.forEach {
            val outcome = EnforcementVerdictEngine.evaluate(progress, it)
            progress = outcome.progress
            if (first == null) first = outcome.verdict
        }
        return first
    }

    /** Climb from 70% to [holdPercent], then sit there for [holdMinutes]. */
    private fun riseThenHold(holdMinutes: Int, holdPercent: Int = cap): List<EnforcementSample> {
        val rise = (70..holdPercent).mapIndexed { index, percent ->
            sample(percent = percent, elapsed = index * 30_000L)
        }
        val holdStart = rise.size * 30_000L
        val hold = (0 until holdMinutes * 2).map { tick ->
            sample(percent = holdPercent, elapsed = holdStart + tick * 30_000L)
        }
        return rise + hold
    }

    @Test
    fun `a rise followed by a sustained plateau at the cap decides nothing`() {
        // The plateau is the shape a working cap produces, and also the shape a thermal pause or a
        // weak charger produces. Nothing observable separates them, so it stays undecided forever —
        // including the hardware charging state, which the sample deliberately no longer carries:
        // measured on a Pixel 6 / LineageOS 23.2, `Charging state: 4` was reported while the device
        // was actively charging at level 70 under an 80% cap.
        run(riseThenHold(holdMinutes = 6)) shouldBe null
        run(riseThenHold(holdMinutes = 120)) shouldBe null
    }

    @Test
    fun `no input sequence can produce a confirmation`() {
        // The engine has exactly one verdict, and nothing may reach a second one. Sweeps the whole
        // input space that moves state: levels, plug state, sessions, epochs.
        EnforcementVerdict.entries.toList() shouldBe listOf(EnforcementVerdict.REFUTED)

        val random = Random(20260816)
        repeat(2_000) {
            var progress: EnforcementProgress? = null
            repeat(40) { tick ->
                val candidate = sample(
                    percent = random.nextInt(-1, 101),
                    elapsed = tick * 30_000L,
                    plugged = random.nextBoolean(),
                    sessionActive = random.nextInt(10) == 0,
                    policyGeneration = random.nextLong(1, 3),
                    plugSessionId = random.nextLong(1, 3),
                )
                val outcome = EnforcementVerdictEngine.evaluate(progress, candidate)
                progress = outcome.progress
                // A non-null verdict is REFUTED by construction (single-value enum); what this
                // asserts is that every one of them is actually justified by a climb past the cap.
                outcome.verdict?.let {
                    it shouldBe EnforcementVerdict.REFUTED
                    (candidate.percent >= cap + EnforcementVerdictEngine.OVERSHOOT_ALLOWANCE) shouldBe true
                }
            }
        }
    }

    @Test
    fun `charging past the cap refutes`() {
        val samples = (70..(cap + EnforcementVerdictEngine.OVERSHOOT_ALLOWANCE)).mapIndexed { index, percent ->
            sample(percent, index * 30_000L)
        }
        run(samples) shouldBe EnforcementVerdict.REFUTED
    }

    @Test
    fun `a climb that stops inside the allowance does not refute`() {
        val samples = (70 until (cap + EnforcementVerdictEngine.OVERSHOOT_ALLOWANCE)).mapIndexed { index, percent ->
            sample(percent, index * 30_000L)
        }
        run(samples) shouldBe null
    }

    @Test
    fun `a drop clears the rise, so the climb has to re-establish itself`() {
        // Charging pauses and the level slips a point: the rise that follows must be observed again
        // before anything past the cap counts as the device ignoring it.
        val rise = (70..(cap + 1)).mapIndexed { index, percent -> sample(percent, index * 30_000L) }
        val drop = listOf(sample(cap, 300_000L))
        run(rise + drop) shouldBe null
        // Resuming from the drop and climbing on does refute.
        val resumed = (cap..(cap + EnforcementVerdictEngine.OVERSHOOT_ALLOWANCE)).mapIndexed { index, percent ->
            sample(percent, 330_000L + index * 30_000L)
        }
        run(rise + drop + resumed) shouldBe EnforcementVerdict.REFUTED
    }

    @Test
    fun `unplugged samples produce no verdict`() {
        // Charging past the cap while "unplugged" is nonsense data, not a refutation.
        run(listOf(sample(70, 0L), sample(90, 60_000L)).map { it.copy(plugged = false) }) shouldBe null
    }

    @Test
    fun `an active full-charge session produces no verdict`() {
        // The session deliberately lifts the cap; charging past it proves nothing about the hardware.
        run(listOf(sample(70, 0L), sample(90, 60_000L)).map { it.copy(sessionActive = true) }) shouldBe null
    }

    @Test
    fun `only a verified fixed limit is evaluated`() {
        val unevaluable = listOf(
            ChargeObservation.Verified(ChargePolicy.Adaptive, BackendKind.SHIZUKU),
            ChargeObservation.Verified(ChargePolicy.Unrestricted, BackendKind.SHIZUKU),
            ChargeObservation.Verified(ChargePolicy.FixedLimit(100), BackendKind.SHIZUKU),
            ChargeObservation.LastRequested(ChargePolicy.FixedLimit(80)),
            ChargeObservation.Unknown("unreadable".toCaString()),
            null,
        )
        unevaluable.forEach { configured ->
            run(listOf(sample(70, 0L), sample(90, 60_000L)).map { it.copy(configured = configured) }) shouldBe null
        }
    }

    @Test
    fun `an unknown level moves nothing`() {
        val climb = (70..(cap + EnforcementVerdictEngine.OVERSHOOT_ALLOWANCE)).mapIndexed { index, percent ->
            sample(percent, index * 30_000L)
        }
        // Interleaved unknown readings neither break nor advance the climb.
        run(climb.flatMap { listOf(it.copy(percent = -1), it) }) shouldBe EnforcementVerdict.REFUTED
        // And on their own they decide nothing at all.
        run(List(50) { sample(percent = -1, elapsed = it * 30_000L) }) shouldBe null
    }

    @Test
    fun `a resting level above the cap does not refute`() {
        // Plugged in at 95% with an 80% cap and staying there: the device stopped charging, which is
        // no evidence against it. Only a climb from that level is.
        run((0 until 20).map { sample(95, it * 30_000L) }) shouldBe null
    }

    @Test
    fun `an epoch that opens above the cap still refutes once the level climbs`() {
        // The gap this closes: an 80% cap restored early from a full-charge session at 84% (or a
        // process death at 82%). An in-cap-only climb base stays null forever, so a build that
        // ignores the cap charges all the way to 100% without ever being refuted.
        run(listOf(sample(84, 0L), sample(85, 30_000L))) shouldBe EnforcementVerdict.REFUTED
        run(listOf(sample(82, 0L), sample(83, 30_000L), sample(84, 60_000L))) shouldBe
            EnforcementVerdict.REFUTED
    }

    @Test
    fun `a cap change mid-epoch resets instead of refuting`() {
        // Sitting at 78% under an 80% cap, the user switches to 70%: a bare watermark would read the
        // very next sample as "charging past 70" and refute a good device. The new epoch opens above
        // its cap, and a device that honours it simply stops charging there.
        val below = (70..78).mapIndexed { index, percent -> sample(percent, index * 30_000L) }
        val afterSwitch = (0 until 10).map { tick ->
            sample(78, 300_000L + tick * 30_000L, policyGeneration = 2L)
                .copy(configured = ChargeObservation.Verified(ChargePolicy.FixedLimit(70), BackendKind.SHIZUKU))
        }
        run(below + afterSwitch) shouldBe null
    }

    @Test
    fun `a new plug session restarts the window`() {
        // A climb of 78 → 84 inside ONE epoch refutes; split across a replug it must not, because the
        // fresh epoch has seen no climb of its own yet.
        val first = (70..78).mapIndexed { index, percent -> sample(percent, index * 30_000L) }
        val second = (0 until 10).map { tick ->
            sample(84, 600_000L + tick * 30_000L, plugSessionId = 2L)
        }
        run(first + second) shouldBe null
    }
}
