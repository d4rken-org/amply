package eu.darken.amply.charging.core.enforcement

import android.os.BatteryManager
import eu.darken.amply.charging.core.BackendKind
import eu.darken.amply.charging.core.ChargeObservation
import eu.darken.amply.charging.core.ChargePolicy
import eu.darken.amply.common.ca.toCaString
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class EnforcementVerdictEngineTest {

    private val cap = 80

    private fun sample(
        percent: Int,
        elapsed: Long,
        held: Boolean = false,
        plugged: Boolean = true,
        sessionActive: Boolean = false,
        configured: ChargeObservation? = ChargeObservation.Verified(ChargePolicy.FixedLimit(cap), BackendKind.SHIZUKU),
        policyGeneration: Long = 1L,
        plugSessionId: Long = 1L,
        batteryStatus: Int? = null,
    ) = EnforcementSample(
        adapterId = "lineageos-chargingcontrol-v1",
        buildIdentity = "build-a",
        configured = configured,
        sessionActive = sessionActive,
        plugged = plugged,
        percent = percent,
        batteryStatus = batteryStatus
            ?: if (held) BatteryManager.BATTERY_STATUS_NOT_CHARGING else BatteryManager.BATTERY_STATUS_CHARGING,
        chargingStatus = null,
        currentNowMicroamps = null,
        policyGeneration = policyGeneration,
        plugSessionId = plugSessionId,
        elapsedRealtimeMillis = elapsed,
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

    /** Climb from 70% to the cap, then sit held at the cap for [holdMinutes]. */
    private fun riseThenHold(holdMinutes: Int, holdPercent: Int = cap): List<EnforcementSample> {
        val rise = (70..holdPercent).mapIndexed { index, percent ->
            sample(percent = percent, elapsed = index * 30_000L)
        }
        val holdStart = rise.size * 30_000L
        val hold = (0 until holdMinutes * 2).map { tick ->
            sample(percent = holdPercent, elapsed = holdStart + tick * 30_000L, held = true)
        }
        return rise + hold
    }

    @Test
    fun `a rise followed by a sustained plateau confirms`() {
        run(riseThenHold(holdMinutes = 6)) shouldBe EnforcementVerdict.CONFIRMED
    }

    @Test
    fun `a single held tick does not confirm`() {
        val samples = (70..cap).mapIndexed { index, percent -> sample(percent, index * 30_000L) } +
            sample(percent = cap, elapsed = 1_000_000L, held = true)
        run(samples) shouldBe null
    }

    @Test
    fun `a hold shorter than the minimum window does not confirm`() {
        run(riseThenHold(holdMinutes = 2)) shouldBe null
    }

    @Test
    fun `a thermal pause far below the cap does not confirm`() {
        // Charging stalls at 60% for half an hour: heldNow is true the whole time, but the level is
        // nowhere near the configured cap.
        val samples = (50..60).mapIndexed { index, percent -> sample(percent, index * 30_000L) } +
            (0 until 60).map { sample(percent = 60, elapsed = 400_000L + it * 30_000L, held = true) }
        run(samples) shouldBe null
    }

    @Test
    fun `a plateau without an observed rise does not confirm`() {
        // Plugged in already sitting at the cap: nothing was observed climbing into the hold.
        val samples = (0 until 40).map { sample(percent = cap, elapsed = it * 30_000L, held = true) }
        run(samples) shouldBe null
    }

    @Test
    fun `unplugged samples produce no verdict`() {
        run(riseThenHold(holdMinutes = 6).map { it.copy(plugged = false) }) shouldBe null
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
            run(riseThenHold(holdMinutes = 6).map { it.copy(configured = configured) }) shouldBe null
            run(listOf(sample(70, 0L), sample(90, 60_000L)).map { it.copy(configured = configured) }) shouldBe null
        }
    }

    @Test
    fun `an unknown level moves nothing`() {
        val samples = riseThenHold(holdMinutes = 6).flatMap { listOf(it.copy(percent = -1), it) }
        // The interleaved unknown readings neither break the climb nor advance the hold.
        run(samples) shouldBe EnforcementVerdict.CONFIRMED
        // And on their own they decide nothing at all.
        run(List(50) { sample(percent = -1, elapsed = it * 30_000L, held = true) }) shouldBe null
    }

    @Test
    fun `charging past the cap refutes`() {
        val samples = (70..(cap + EnforcementVerdictEngine.OVERSHOOT_ALLOWANCE)).mapIndexed { index, percent ->
            sample(percent, index * 30_000L)
        }
        run(samples) shouldBe EnforcementVerdict.REFUTED
    }

    @Test
    fun `refutation ignores the reported battery status`() {
        // A ROM can carry the level past the cap while reporting NOT_CHARGING / FULL / UNKNOWN;
        // gating on BATTERY_STATUS_CHARGING would leave such a build trusted indefinitely.
        listOf(
            BatteryManager.BATTERY_STATUS_NOT_CHARGING,
            BatteryManager.BATTERY_STATUS_FULL,
            BatteryManager.BATTERY_STATUS_UNKNOWN,
            BatteryManager.BATTERY_STATUS_DISCHARGING,
        ).forEach { status ->
            val samples = (70..(cap + EnforcementVerdictEngine.OVERSHOOT_ALLOWANCE)).mapIndexed { index, percent ->
                sample(percent, index * 30_000L, batteryStatus = status)
            }
            run(samples) shouldBe EnforcementVerdict.REFUTED
        }
    }

    @Test
    fun `a device already above the cap does not refute`() {
        // Plugged in at 95% with an 80% cap: the level was never observed climbing from inside the cap.
        val samples = (95..99).mapIndexed { index, percent -> sample(percent, index * 30_000L) }
        run(samples) shouldBe null
    }

    @Test
    fun `a cap change mid-epoch resets instead of refuting`() {
        // Sitting at 78% under an 80% cap, the user switches to 70%: a bare watermark would read the
        // very next sample as "charging past 70" and refute a good device.
        val below = (70..78).mapIndexed { index, percent -> sample(percent, index * 30_000L) }
        val afterSwitch = (78..85).mapIndexed { index, percent ->
            sample(percent, 300_000L + index * 30_000L, policyGeneration = 2L)
                .copy(configured = ChargeObservation.Verified(ChargePolicy.FixedLimit(70), BackendKind.SHIZUKU))
        }
        run(below + afterSwitch) shouldBe null
    }

    @Test
    fun `a new plug session restarts the window`() {
        val first = (70..78).mapIndexed { index, percent -> sample(percent, index * 30_000L) }
        // Replugged already above the cap: the fresh epoch has no in-cap climb base.
        val second = (84..90).mapIndexed { index, percent ->
            sample(percent, 600_000L + index * 30_000L, plugSessionId = 2L)
        }
        run(first + second) shouldBe null
    }

    @Test
    fun `a cut and resumed charge still confirms`() {
        // Charging pauses (level drops a point), resumes, then holds: the interruption must not
        // disqualify the plateau that follows.
        val rise = (70..78).mapIndexed { index, percent -> sample(percent, index * 30_000L) }
        val dip = listOf(sample(77, 300_000L), sample(78, 330_000L), sample(79, 360_000L), sample(80, 390_000L))
        val hold = (0 until 14).map { sample(percent = cap, elapsed = 420_000L + it * 30_000L, held = true) }
        run(rise + dip + hold) shouldBe EnforcementVerdict.CONFIRMED
    }

    @Test
    fun `the confirm and refute bands are disjoint`() {
        // The bands are asymmetric on purpose (hysteresis below the cap, a small allowance above it);
        // what must never happen is one level qualifying for both verdicts.
        val confirmBand = (cap - EnforcementVerdictEngine.HOLD_BAND)..cap
        val refuteBand = (cap + EnforcementVerdictEngine.OVERSHOOT_ALLOWANCE)..100
        confirmBand.none { it in refuteBand } shouldBe true
        refuteBand.first shouldBeGreaterThan confirmBand.last
    }
}
