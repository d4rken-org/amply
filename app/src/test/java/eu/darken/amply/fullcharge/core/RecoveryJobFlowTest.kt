package eu.darken.amply.fullcharge.core

import eu.darken.amply.charging.core.ChargePolicy
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Test

class RecoveryJobFlowTest {

    private val fixedLimit = ChargePolicy.FixedLimit(80)
    private val converged = BootRecoveryFlow.Result(
        outcome = BootRecoveryFlow.Outcome.CONVERGED,
        restoreAttempted = true,
        rewrites = 0,
        retryRemaining = false,
    )
    private val gaveUp = BootRecoveryFlow.Result(
        outcome = BootRecoveryFlow.Outcome.GAVE_UP,
        restoreAttempted = true,
        rewrites = 1,
        retryRemaining = true,
    )

    @Test
    fun `a failing flow warns, continues, and reports nothing converged`() = runTest {
        val hooks = FakeHooks(onRecovery = { throw IllegalStateException("storage gone") })

        RecoveryJobFlow(hooks).run()

        hooks.warnings shouldBe 1
        hooks.convergedCalls shouldBe 0
        hooks.finished.shouldBeEmpty()
        hooks.continuations shouldBe 1
        hooks.lastResorts shouldBe 0
        // The flow keeps the target so the next service start retries it; nothing here clears it.
        hooks.pendingTarget shouldBe fixedLimit
    }

    @Test
    fun `a failing preamble is handled like a failing flow`() = runTest {
        val hooks = FakeHooks(onPrepare = { throw IllegalStateException("storage gone") })

        RecoveryJobFlow(hooks).run()

        hooks.recoveryRuns shouldBe 0
        hooks.warnings shouldBe 1
        hooks.convergedCalls shouldBe 0
        hooks.finished.shouldBeEmpty()
        hooks.continuations shouldBe 1
        hooks.pendingTarget shouldBe fixedLimit
    }

    @Test
    fun `a failing pickup capture is handled like a failing flow`() = runTest {
        val hooks = FakeHooks(onPickup = { throw IllegalStateException("storage gone") })

        RecoveryJobFlow(hooks).run()

        hooks.prepares shouldBe 0
        hooks.recoveryRuns shouldBe 0
        hooks.warnings shouldBe 1
        hooks.continuations shouldBe 1
    }

    @Test
    fun `a converged run clears the alarm and continues without a warning`() = runTest {
        val hooks = FakeHooks()

        RecoveryJobFlow(hooks).run()

        hooks.convergedCalls shouldBe 1
        hooks.finished shouldBe listOf(PICKUP to converged)
        hooks.warnings shouldBe 0
        hooks.continuations shouldBe 1
        hooks.lastResorts shouldBe 0
        hooks.pendingTarget shouldBe null
    }

    @Test
    fun `an unconverged run is finished without clearing the alarm`() = runTest {
        val hooks = FakeHooks(onRecovery = { gaveUp })

        RecoveryJobFlow(hooks).run()

        hooks.convergedCalls shouldBe 0
        hooks.finished shouldBe listOf(PICKUP to gaveUp)
        hooks.warnings shouldBe 0
        hooks.continuations shouldBe 1
    }

    @Test
    fun `a failing continuation falls back to the last resort`() = runTest {
        val hooks = FakeHooks(onContinue = { throw IllegalStateException("lock owner died") })

        RecoveryJobFlow(hooks).run()

        hooks.convergedCalls shouldBe 1
        hooks.warnings shouldBe 0
        hooks.lastResorts shouldBe 1
    }

    @Test
    fun `a failing flow and a failing continuation both warn and fall back`() = runTest {
        val hooks = FakeHooks(
            onRecovery = { throw IllegalStateException("storage gone") },
            onContinue = { throw IllegalStateException("storage gone") },
        )

        RecoveryJobFlow(hooks).run()

        hooks.warnings shouldBe 1
        hooks.lastResorts shouldBe 1
        hooks.convergedCalls shouldBe 0
    }

    @Test
    fun `a failing warning does not escape and still continues`() = runTest {
        val hooks = FakeHooks(
            onRecovery = { throw IllegalStateException("storage gone") },
            onWarn = { throw IllegalStateException("no notification permission") },
        )

        RecoveryJobFlow(hooks).run()

        hooks.warnings shouldBe 1
        hooks.continuations shouldBe 1
        hooks.lastResorts shouldBe 0
    }

    @Test
    fun `cancellation of the flow propagates without a warning`() = runTest {
        val hooks = FakeHooks(onRecovery = { throw CancellationException("superseded") })

        shouldThrow<CancellationException> { RecoveryJobFlow(hooks).run() }

        hooks.warnings shouldBe 0
        hooks.convergedCalls shouldBe 0
        hooks.finished.shouldBeEmpty()
        hooks.lastResorts shouldBe 0
        // The terminal continuation is in a finally, so a cancelled job still attempts it.
        hooks.continuations shouldBe 1
    }

    @Test
    fun `cancellation of the continuation propagates without a last resort`() = runTest {
        val hooks = FakeHooks(onContinue = { throw CancellationException("superseded") })

        shouldThrow<CancellationException> { RecoveryJobFlow(hooks).run() }

        hooks.warnings shouldBe 0
        hooks.lastResorts shouldBe 0
    }

    /**
     * A [kotlinx.coroutines.TimeoutCancellationException] from a post-write backend bind is a
     * CancellationException that did NOT cancel this job — the supersession path would stop the
     * service while the protective write is still owed.
     */
    @Test
    fun `a backend timeout inside recovery warns instead of taking the supersession path`() = runTest {
        val hooks = FakeHooks(onRecovery = { withTimeout(1) { awaitCancellation() } })

        RecoveryJobFlow(hooks).run()

        hooks.warnings shouldBe 1
        hooks.continuations shouldBe 1
        hooks.convergedCalls shouldBe 0
        hooks.finished.shouldBeEmpty()
        hooks.lastResorts shouldBe 0
        hooks.pendingTarget shouldBe fixedLimit
    }

    private inner class FakeHooks(
        val onPickup: () -> String = { PICKUP },
        val onPrepare: () -> Unit = {},
        val onRecovery: suspend () -> BootRecoveryFlow.Result = { converged },
        val onWarn: () -> Unit = {},
        val onContinue: () -> Unit = {},
    ) : RecoveryJobFlow.Hooks<String> {
        var pendingTarget: ChargePolicy? = fixedLimit
        var prepares = 0
        var recoveryRuns = 0
        var convergedCalls = 0
        var warnings = 0
        var continuations = 0
        var lastResorts = 0
        val finished = mutableListOf<Pair<String, BootRecoveryFlow.Result>>()

        override suspend fun capturePickup(): String = onPickup()

        override suspend fun prepare() {
            prepares++
            onPrepare()
        }

        override suspend fun runRecovery(): BootRecoveryFlow.Result {
            recoveryRuns++
            val result = onRecovery()
            // Mirrors BootRecoveryFlow: it clears the pending target when it converges and keeps it
            // when a re-write fails, so a target left behind here is one the flow means to retry.
            if (result.outcome == BootRecoveryFlow.Outcome.CONVERGED) pendingTarget = null
            return result
        }

        override fun onConverged() {
            convergedCalls++
        }

        override suspend fun onFinished(pickup: String, result: BootRecoveryFlow.Result) {
            finished += pickup to result
        }

        override fun warnRecoveryOwed() {
            warnings++
            onWarn()
        }

        override suspend fun continueOrStop() {
            continuations++
            onContinue()
        }

        override fun lastResort() {
            lastResorts++
        }
    }

    companion object {
        private const val PICKUP = "pickup"
    }
}
