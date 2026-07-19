package eu.darken.amply.fullcharge.core

import com.google.common.truth.Truth.assertThat
import eu.darken.amply.charging.core.BackendKind
import eu.darken.amply.charging.core.ChargeObservation
import eu.darken.amply.charging.core.ChargePolicy
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.Test

class BootRecoveryFlowTest {
    private val fixedLimit = ChargePolicy.FixedLimit(80)
    private val hardwareLongLife =
        ChargeObservation.Verified(fixedLimit, BackendKind.BATTERY_HARDWARE)

    @Test
    fun `nothing to do without session or pending target`() = runTest {
        val hooks = FakeHooks(sessionTarget = null)

        assertThat(BootRecoveryFlow(hooks).run()).isEqualTo(BootRecoveryFlow.Outcome.NOTHING_TO_DO)
        assertThat(hooks.restoreCalls).isEqualTo(0)
    }

    @Test
    fun `restore failure notifies as write failure and skips convergence`() = runTest {
        val hooks = FakeHooks(restoreResult = false)

        assertThat(BootRecoveryFlow(hooks).run()).isEqualTo(BootRecoveryFlow.Outcome.RESTORE_FAILED)
        assertThat(hooks.failures).containsExactly(true)
        assertThat(hooks.rewrites).isEmpty()
        assertThat(hooks.pending).isNull()
    }

    @Test
    fun `hardware confirmation clears the pending target`() = runTest {
        val hooks = FakeHooks(observation = { hardwareLongLife })

        assertThat(BootRecoveryFlow(hooks).run()).isEqualTo(BootRecoveryFlow.Outcome.CONVERGED)
        assertThat(hooks.pending).isNull()
        assertThat(hooks.rewrites).isEmpty()
    }

    @Test
    fun `persistent divergence rewrites then gives up with a convergence notification`() = runTest {
        val hooks = FakeHooks()

        assertThat(BootRecoveryFlow(hooks).run()).isEqualTo(BootRecoveryFlow.Outcome.GAVE_UP)
        assertThat(hooks.rewrites).hasSize(BootRecoveryEngine.MAX_REWRITES)
        assertThat(hooks.failures).containsExactly(false)
        assertThat(hooks.pending).isNull()
    }

    @Test
    fun `rewrite failure notifies as write failure and preserves the pending target for retry`() = runTest {
        val hooks = FakeHooks(rewriteResult = false)

        assertThat(BootRecoveryFlow(hooks).run()).isEqualTo(BootRecoveryFlow.Outcome.GAVE_UP)
        assertThat(hooks.failures).containsExactly(true)
        assertThat(hooks.pending).isEqualTo(fixedLimit)
    }

    @Test
    fun `a newer policy choice supersedes recovery without re-writes`() = runTest {
        val hooks = FakeHooks(intended = { ChargePolicy.Adaptive })

        assertThat(BootRecoveryFlow(hooks).run()).isEqualTo(BootRecoveryFlow.Outcome.SUPERSEDED)
        assertThat(hooks.rewrites).isEmpty()
        assertThat(hooks.failures).isEmpty()
        assertThat(hooks.pending).isNull()
    }

    @Test
    fun `unplugged boot nudges once and finishes without alarm`() = runTest {
        val hooks = FakeHooks(
            snapshot = BatterySnapshot(plugged = false, percent = 80, chargingState = 4),
        )

        assertThat(BootRecoveryFlow(hooks).run()).isEqualTo(BootRecoveryFlow.Outcome.CONVERGED)
        assertThat(hooks.rewrites).hasSize(1)
        assertThat(hooks.failures).isEmpty()
        assertThat(hooks.pending).isNull()
    }

    @Test
    fun `cancellation mid loop preserves the pending target and stops rewrites`() = runTest {
        val hooks = FakeHooks()
        val job = launch { BootRecoveryFlow(hooks).run() }
        repeat(4) { yield() }

        job.cancelAndJoin()

        assertThat(hooks.pending).isEqualTo(fixedLimit)
        assertThat(hooks.failures).isEmpty()
    }

    @Test
    fun `boot dispatch prefers recovery and falls back to monitoring`() {
        assertThat(BootRecoveryFlow.bootAction(sessionExists = true, pendingRecovery = false, gestureEnabled = true))
            .isEqualTo(ChargeSessionService.ACTION_RECOVER)
        assertThat(BootRecoveryFlow.bootAction(sessionExists = false, pendingRecovery = true, gestureEnabled = false))
            .isEqualTo(ChargeSessionService.ACTION_RECOVER)
        assertThat(BootRecoveryFlow.bootAction(sessionExists = false, pendingRecovery = false, gestureEnabled = true))
            .isEqualTo(ChargeSessionService.ACTION_MONITOR)
        assertThat(BootRecoveryFlow.bootAction(sessionExists = false, pendingRecovery = false, gestureEnabled = false))
            .isNull()
    }

    private inner class FakeHooks(
        var sessionTarget: ChargePolicy? = fixedLimit,
        var pending: ChargePolicy? = null,
        val restoreResult: Boolean = true,
        val rewriteResult: Boolean = true,
        var snapshot: BatterySnapshot? = BatterySnapshot(plugged = true, percent = 80, chargingState = 1),
        val observation: (BatterySnapshot) -> ChargeObservation? = { null },
        val intended: (() -> ChargePolicy?)? = null,
    ) : BootRecoveryFlow.Hooks {
        var now = 0L
        var restoreCalls = 0
        val rewrites = mutableListOf<ChargePolicy>()
        val failures = mutableListOf<Boolean>()

        override suspend fun currentSessionTarget() = sessionTarget
        override suspend fun pendingTarget() = pending
        override suspend fun setPendingTarget(policy: ChargePolicy) {
            pending = policy
        }

        override suspend fun clearPendingTarget() {
            pending = null
        }

        override suspend fun restoreSession(): Boolean {
            restoreCalls++
            if (restoreResult) sessionTarget = null
            return restoreResult
        }

        override suspend fun rewrite(policy: ChargePolicy): Boolean {
            rewrites += policy
            return rewriteResult
        }

        override fun batterySnapshot() = snapshot
        override fun hardwareObservation(snapshot: BatterySnapshot) = observation(snapshot)
        override suspend fun intendedTarget() = intended?.invoke() ?: pending ?: sessionTarget

        override fun notifyFailure(writeFailed: Boolean) {
            failures += writeFailed
        }

        override suspend fun tick() {
            now += BootRecoveryEngine.TICK_MILLIS
            yield()
        }

        override fun elapsedRealtime() = now
    }
}
