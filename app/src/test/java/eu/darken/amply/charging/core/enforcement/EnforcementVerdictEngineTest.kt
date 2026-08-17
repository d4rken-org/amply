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
        // The adapter's hardware hold signal. Defaults to the behavioural `held` flag, i.e. the
        // honest device shape: the ROM reports the hold state exactly while it is holding.
        hardwareHold: Boolean? = held,
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
        hardwareHold = hardwareHold,
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
    fun `a plateau without a hardware hold signal never confirms`() {
        // The false-CONFIRM this gate exists for: a hot phone (or a weak charger) parks the battery
        // at 75-80% under an 80% cap and reports NOT_CHARGING for well over five minutes. Behaviourally
        // identical to a cap hold; only the hardware signal tells them apart.
        listOf(false, null).forEach { signal ->
            run(riseThenHold(holdMinutes = 20).map { it.copy(hardwareHold = signal) }) shouldBe null
        }
        // The same plateau WITH the signal is the real thing.
        run(riseThenHold(holdMinutes = 20)) shouldBe EnforcementVerdict.CONFIRMED
    }

    @Test
    fun `a declining level breaks the hold`() {
        val rise = (70..cap).mapIndexed { index, percent -> sample(percent, index * 30_000L) }
        val hold = (0 until 6).map { sample(percent = cap, elapsed = 330_000L + it * 30_000L, held = true) }
        // The battery starts LOSING charge while plugged, with the hold signal still set. A plateau
        // test that only required "not increasing" would keep counting this as a hold.
        val decline = (0 until 40).map { sample(percent = cap - 1, elapsed = 510_000L + it * 30_000L, held = true) }
        run(rise + hold + decline) shouldBe null
    }

    @Test
    fun `a drop clears the rise, so the plateau after it cannot confirm on its own`() {
        val rise = (70..(cap - 1)).mapIndexed { index, percent -> sample(percent, index * 30_000L) }
        val drop = sample(percent = cap - 2, elapsed = 300_000L)
        val plateau = (0 until 40).map { sample(percent = cap - 2, elapsed = 330_000L + it * 30_000L, held = true) }
        run(rise + listOf(drop) + plateau) shouldBe null
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
    fun `a resting level above the cap does not refute`() {
        // Plugged in at 95% with an 80% cap and staying there: the device stopped charging, which is
        // no evidence against it. Only a climb from that level is.
        run((0 until 20).map { sample(95, it * 30_000L) }) shouldBe null
    }

    @Test
    fun `an epoch that opens above the cap still refutes once the level climbs`() {
        // The gap this closes: an 80% cap restored early from a full-charge session at 84% (or a
        // process death at 82%). The old in-cap-only climb base stayed null forever, so a build that
        // ignores the cap charged all the way to 100% without ever being refuted.
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
