package eu.darken.amply.fullcharge.core

import eu.darken.amply.charging.core.BackendKind
import eu.darken.amply.charging.core.ChargeObservation
import eu.darken.amply.charging.core.ChargePolicy
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.jupiter.api.Test

class BootRecoveryFlowTest {
    private val fixedLimit = ChargePolicy.FixedLimit(80)
    private val hardwareLongLife =
        ChargeObservation.Verified(fixedLimit, BackendKind.BATTERY_HARDWARE)

    @Test
    fun `nothing to do without session or pending target`() = runTest {
        val hooks = FakeHooks(sessionTarget = null)

        BootRecoveryFlow(hooks).run() shouldBe BootRecoveryFlow.Outcome.NOTHING_TO_DO
        hooks.restoreCalls shouldBe 0
    }

    @Test
    fun `restore failure notifies as write failure and skips convergence`() = runTest {
        val hooks = FakeHooks(restoreResult = false)

        BootRecoveryFlow(hooks).run() shouldBe BootRecoveryFlow.Outcome.RESTORE_FAILED
        hooks.failures shouldContainExactlyInAnyOrder listOf(true)
        hooks.rewrites.shouldBeEmpty()
        hooks.pending shouldBe null
    }

    @Test
    fun `hardware confirmation clears the pending target`() = runTest {
        val hooks = FakeHooks(observation = { hardwareLongLife })

        BootRecoveryFlow(hooks).run() shouldBe BootRecoveryFlow.Outcome.CONVERGED
        hooks.pending shouldBe null
        hooks.rewrites.shouldBeEmpty()
    }

    @Test
    fun `persistent divergence rewrites then gives up with a convergence notification`() = runTest {
        val hooks = FakeHooks()

        BootRecoveryFlow(hooks).run() shouldBe BootRecoveryFlow.Outcome.GAVE_UP
        hooks.rewrites shouldHaveSize BootRecoveryEngine.MAX_REWRITES
        hooks.failures shouldContainExactlyInAnyOrder listOf(false)
        hooks.pending shouldBe null
    }

    @Test
    fun `rewrite failure notifies as write failure and preserves the pending target for retry`() = runTest {
        val hooks = FakeHooks(rewriteResult = false)

        BootRecoveryFlow(hooks).run() shouldBe BootRecoveryFlow.Outcome.GAVE_UP
        hooks.failures shouldContainExactlyInAnyOrder listOf(true)
        hooks.pending shouldBe fixedLimit
    }

    @Test
    fun `a newer policy choice supersedes recovery without re-writes`() = runTest {
        val hooks = FakeHooks(intended = { ChargePolicy.Adaptive })

        BootRecoveryFlow(hooks).run() shouldBe BootRecoveryFlow.Outcome.SUPERSEDED
        hooks.rewrites.shouldBeEmpty()
        hooks.failures.shouldBeEmpty()
        hooks.pending shouldBe null
    }

    @Test
    fun `unplugged boot nudges once and finishes without alarm`() = runTest {
        val hooks = FakeHooks(
            snapshot = BatterySnapshot(plugged = false, percent = 80, chargingState = 4),
        )

        BootRecoveryFlow(hooks).run() shouldBe BootRecoveryFlow.Outcome.CONVERGED
        hooks.rewrites shouldHaveSize 1
        hooks.failures.shouldBeEmpty()
        hooks.pending shouldBe null
    }

    @Test
    fun `cancellation mid loop preserves the pending target and stops rewrites`() = runTest {
        val hooks = FakeHooks()
        val job = launch { BootRecoveryFlow(hooks).run() }
        repeat(4) { yield() }

        job.cancelAndJoin()

        hooks.pending shouldBe fixedLimit
        hooks.failures.shouldBeEmpty()
    }

    @Test
    fun `a pending target wins over a coexisting stale session without restoring it`() {
        // Process death inside setPersistentPolicy can leave both records; the pending target is
        // the newer intent, so the session's older restore policy must never be written.
        runTest {
            val hooks = FakeHooks(
                sessionTarget = fixedLimit,
                pending = ChargePolicy.Unrestricted,
                observation = { ChargeObservation.Verified(ChargePolicy.Unrestricted, BackendKind.BATTERY_HARDWARE) },
            )

            BootRecoveryFlow(hooks).run() shouldBe BootRecoveryFlow.Outcome.CONVERGED
            hooks.restoreCalls shouldBe 0
            hooks.staleSessionDrops shouldBe 1
            hooks.sessionTarget shouldBe null
            hooks.pending shouldBe null
        }
    }

    @Test
    fun `a stale last-request does not supersede a pending target`() = runTest {
        // Process death before setPersistentPolicy's write lands leaves lastRequested at the OLD
        // policy. That stale value must not be mistaken for a newer user choice — otherwise both
        // recovery records are discarded without protection ever being restored.
        val hooks = FakeHooks(
            sessionTarget = fixedLimit,
            pending = ChargePolicy.Unrestricted,
            intended = { fixedLimit },
            observation = { ChargeObservation.Verified(ChargePolicy.Unrestricted, BackendKind.BATTERY_HARDWARE) },
        )

        BootRecoveryFlow(hooks).run() shouldBe BootRecoveryFlow.Outcome.CONVERGED
        hooks.restoreCalls shouldBe 0
        hooks.staleSessionDrops shouldBe 1
        hooks.pending shouldBe null
    }

    @Test
    fun `a genuinely newer choice still supersedes a pending-target recovery`() = runTest {
        // The baseline capture sees the pre-recovery value; the choice changes only afterwards,
        // so it differs from both the pending target and the stale baseline.
        var intendedCalls = 0
        val hooks = FakeHooks(
            sessionTarget = null,
            pending = ChargePolicy.Unrestricted,
            intended = { if (intendedCalls++ == 0) ChargePolicy.Unrestricted else ChargePolicy.Adaptive },
        )

        BootRecoveryFlow(hooks).run() shouldBe BootRecoveryFlow.Outcome.SUPERSEDED
        hooks.rewrites.shouldBeEmpty()
        hooks.pending shouldBe null
    }

    @Test
    fun `a pending-only target resumes convergence without touching session restore`() = runTest {
        val hooks = FakeHooks(
            sessionTarget = null,
            pending = fixedLimit,
            observation = { hardwareLongLife },
        )

        BootRecoveryFlow(hooks).run() shouldBe BootRecoveryFlow.Outcome.CONVERGED
        hooks.restoreCalls shouldBe 0
        hooks.staleSessionDrops shouldBe 0
        hooks.pending shouldBe null
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
        var staleSessionDrops = 0
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

        override suspend fun dropStaleSession() {
            staleSessionDrops++
            sessionTarget = null
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
