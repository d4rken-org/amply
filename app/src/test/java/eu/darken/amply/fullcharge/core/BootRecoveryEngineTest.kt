package eu.darken.amply.fullcharge.core

import com.google.common.truth.Truth.assertThat
import eu.darken.amply.charging.core.BackendKind
import eu.darken.amply.charging.core.ChargeObservation
import eu.darken.amply.charging.core.ChargePolicy
import org.junit.Test

class BootRecoveryEngineTest {
    private val fixedLimit = ChargePolicy.FixedLimit(80)
    private val hardwareLongLife =
        ChargeObservation.Verified(fixedLimit, BackendKind.BATTERY_HARDWARE)
    private val hardwareAdaptive =
        ChargeObservation.Verified(ChargePolicy.Adaptive, BackendKind.BATTERY_HARDWARE)

    @Test
    fun `matching hardware read converges immediately`() {
        assertThat(decide(observation = hardwareLongLife))
            .isEqualTo(RecoveryDecision.DONE_OK)
        assertThat(decide(target = ChargePolicy.Adaptive, observation = hardwareAdaptive))
            .isEqualTo(RecoveryDecision.DONE_OK)
    }

    @Test
    fun `shizuku verification is not hardware confirmation`() {
        val shizukuRead = ChargeObservation.Verified(fixedLimit, BackendKind.SHIZUKU)

        assertThat(decide(observation = shizukuRead, sinceLastWrite = 1_000))
            .isEqualTo(RecoveryDecision.WAIT)
    }

    @Test
    fun `strict track waits out the verify delay`() {
        assertThat(decide(sinceLastWrite = BootRecoveryEngine.VERIFY_DELAY_MILLIS - 1))
            .isEqualTo(RecoveryDecision.WAIT)
    }

    @Test
    fun `strict track rewrites after the verify delay`() {
        assertThat(decide(sinceLastWrite = BootRecoveryEngine.VERIFY_DELAY_MILLIS))
            .isEqualTo(RecoveryDecision.REWRITE)
    }

    @Test
    fun `contradicting hardware read rewrites at any battery level`() {
        assertThat(
            decide(
                percent = 30,
                observation = hardwareAdaptive,
                sinceLastWrite = BootRecoveryEngine.VERIFY_DELAY_MILLIS,
            ),
        ).isEqualTo(RecoveryDecision.REWRITE)
    }

    @Test
    fun `unrestricted target contradicted by long life state rewrites`() {
        assertThat(
            decide(
                target = ChargePolicy.Unrestricted,
                observation = hardwareLongLife,
                sinceLastWrite = BootRecoveryEngine.VERIFY_DELAY_MILLIS,
            ),
        ).isEqualTo(RecoveryDecision.REWRITE)
    }

    @Test
    fun `exhausted rewrites hold until the budget expires`() {
        assertThat(
            decide(
                rewriteCount = BootRecoveryEngine.MAX_REWRITES,
                sinceLastWrite = BootRecoveryEngine.VERIFY_DELAY_MILLIS,
                totalElapsed = BootRecoveryEngine.TOTAL_BUDGET_MILLIS - 1,
            ),
        ).isEqualTo(RecoveryDecision.WAIT)
    }

    @Test
    fun `late tick past the budget gives up instead of rewriting`() {
        assertThat(
            decide(
                rewriteCount = 1,
                sinceLastWrite = BootRecoveryEngine.VERIFY_DELAY_MILLIS,
                totalElapsed = BootRecoveryEngine.TOTAL_BUDGET_MILLIS,
            ),
        ).isEqualTo(RecoveryDecision.GIVE_UP)
    }

    @Test
    fun `strict track gives up only at budget exhaustion`() {
        assertThat(
            decide(
                rewriteCount = BootRecoveryEngine.MAX_REWRITES,
                sinceLastWrite = BootRecoveryEngine.VERIFY_DELAY_MILLIS,
                totalElapsed = BootRecoveryEngine.TOTAL_BUDGET_MILLIS,
            ),
        ).isEqualTo(RecoveryDecision.GIVE_UP)
    }

    @Test
    fun `below limit fixed target uses the nudge track`() {
        assertThat(
            decide(
                percent = BootRecoveryEngine.NEAR_LIMIT_PERCENT - 1,
                sinceLastWrite = BootRecoveryEngine.NUDGE_DELAY_MILLIS,
            ),
        ).isEqualTo(RecoveryDecision.REWRITE)
        assertThat(
            decide(
                percent = BootRecoveryEngine.NEAR_LIMIT_PERCENT - 1,
                rewriteCount = 1,
                totalElapsed = BootRecoveryEngine.TOTAL_BUDGET_MILLIS,
            ),
        ).isEqualTo(RecoveryDecision.DONE_OK)
    }

    @Test
    fun `unplugged waits for the nudge delay`() {
        assertThat(
            decide(
                plugged = false,
                sinceLastWrite = BootRecoveryEngine.NUDGE_DELAY_MILLIS - 1,
            ),
        ).isEqualTo(RecoveryDecision.WAIT)
    }

    @Test
    fun `unplugged nudges exactly once and never alarms`() {
        assertThat(decide(plugged = false, sinceLastWrite = BootRecoveryEngine.NUDGE_DELAY_MILLIS))
            .isEqualTo(RecoveryDecision.REWRITE)
        assertThat(
            decide(
                plugged = false,
                rewriteCount = 1,
                totalElapsed = BootRecoveryEngine.TOTAL_BUDGET_MILLIS * 2,
            ),
        ).isEqualTo(RecoveryDecision.DONE_OK)
    }

    @Test
    fun `plugged inconclusive adaptive and unrestricted targets use the nudge track`() {
        assertThat(
            decide(
                target = ChargePolicy.Adaptive,
                sinceLastWrite = BootRecoveryEngine.NUDGE_DELAY_MILLIS,
            ),
        ).isEqualTo(RecoveryDecision.REWRITE)
        assertThat(
            decide(
                target = ChargePolicy.Unrestricted,
                rewriteCount = 1,
                totalElapsed = BootRecoveryEngine.TOTAL_BUDGET_MILLIS,
            ),
        ).isEqualTo(RecoveryDecision.DONE_OK)
    }

    @Test
    fun `plugging in mid window upgrades a nudged run to strict verification`() {
        assertThat(
            decide(
                rewriteCount = 1,
                sinceLastWrite = BootRecoveryEngine.VERIFY_DELAY_MILLIS,
                totalElapsed = BootRecoveryEngine.NUDGE_DELAY_MILLIS + 30_000,
            ),
        ).isEqualTo(RecoveryDecision.REWRITE)
    }

    @Test
    fun `null observation while plugged near the limit counts as unconverged`() {
        assertThat(
            decide(
                observation = null,
                sinceLastWrite = BootRecoveryEngine.VERIFY_DELAY_MILLIS,
            ),
        ).isEqualTo(RecoveryDecision.REWRITE)
    }

    private fun decide(
        target: ChargePolicy = fixedLimit,
        plugged: Boolean = true,
        percent: Int = 80,
        observation: ChargeObservation? = null,
        sinceLastWrite: Long = 0,
        totalElapsed: Long = sinceLastWrite,
        rewriteCount: Int = 0,
    ) = BootRecoveryEngine.decide(
        target = target,
        plugged = plugged,
        percent = percent,
        observation = observation,
        sinceLastWriteMillis = sinceLastWrite,
        totalElapsedMillis = totalElapsed,
        rewriteCount = rewriteCount,
    )
}
