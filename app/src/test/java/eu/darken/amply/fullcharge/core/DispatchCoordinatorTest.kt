package eu.darken.amply.fullcharge.core

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

/**
 * The dispatch core of [ChargeSessionService]: FIFO per stream, mutual exclusion across both streams,
 * generation filtering, and the lifecycle rules the service depends on.
 *
 * Wherever a test claims to prove exclusion, the handler suspends on a [CompletableDeferred]. With
 * non-suspending handlers on a single test dispatcher an "only one ran at a time" assertion holds even
 * with the lock deleted, so it would prove nothing.
 */
class DispatchCoordinatorTest {

    private val commandsSeen = mutableListOf<String>()
    private val evaluationsSeen = mutableListOf<String>()
    private val errors = mutableListOf<Pair<String, Exception>>()

    private var onCommand: suspend (String) -> Unit = { commandsSeen += it }
    private var onEvaluation: suspend (String) -> Unit = { evaluationsSeen += it }

    private fun TestScope.launched(): DispatchCoordinator<String, String> =
        DispatchCoordinator<String, String>().also { it.launchInto(backgroundScope) }

    private fun DispatchCoordinator<String, String>.launchInto(scope: CoroutineScope) = launch(
        scope = scope,
        onCommand = { onCommand(it) },
        onEvaluation = { onEvaluation(it) },
        onError = { label, e -> errors += label to e },
    )

    // --- Ordering ---------------------------------------------------------------------------------

    @Test
    fun `commands drain in submission order`() = runTest {
        val coordinator = launched()

        coordinator.submitCommand("a") shouldBe true
        coordinator.submitCommand("b") shouldBe true
        coordinator.submitCommand("c") shouldBe true
        advanceUntilIdle()

        commandsSeen shouldBe listOf("a", "b", "c")
    }

    @Test
    fun `evaluations drain in submission order`() = runTest {
        val coordinator = launched()

        coordinator.submitEvaluation("A") shouldBe true
        coordinator.submitEvaluation("B") shouldBe true
        coordinator.submitEvaluation("C") shouldBe true
        advanceUntilIdle()

        evaluationsSeen shouldBe listOf("A", "B", "C")
    }

    // --- Per-item failure isolation ---------------------------------------------------------------

    @Test
    fun `a throwing command is reported and the consumer keeps draining`() = runTest {
        onCommand = {
            if (it == "boom") throw IllegalStateException("nope")
            commandsSeen += it
        }
        val coordinator = launched()

        coordinator.submitCommand("boom")
        coordinator.submitCommand("next")
        advanceUntilIdle()

        commandsSeen shouldBe listOf("next")
        errors.map { it.first } shouldBe listOf("Command boom")
        errors.single().second.message shouldBe "nope"
    }

    @Test
    fun `a throwing evaluation is reported and the consumer keeps draining`() = runTest {
        onEvaluation = {
            if (it == "boom") throw IllegalStateException("nope")
            evaluationsSeen += it
        }
        val coordinator = launched()

        coordinator.submitEvaluation("boom")
        coordinator.submitEvaluation("next")
        advanceUntilIdle()

        evaluationsSeen shouldBe listOf("next")
        errors.map { it.first } shouldBe listOf("Battery evaluation")
    }

    // --- Generations ------------------------------------------------------------------------------

    @Test
    fun `an evaluation queued before a new run is dropped`() = runTest {
        val coordinator = launched()

        coordinator.submitEvaluation("stale")
        coordinator.newRun()
        coordinator.submitEvaluation("live")
        advanceUntilIdle()

        evaluationsSeen shouldBe listOf("live")
    }

    /**
     * The post-lock re-check specifically: the consumer already passed the fast-path check and is parked
     * on the lock when a command restarts monitoring. Queue-then-newRun only covers the fast path.
     */
    @Test
    fun `an evaluation that passed the fast path is dropped when the run changes under the lock`() = runTest {
        val coordinator = launched()
        val gate = CompletableDeferred<Unit>()
        backgroundScope.launch { coordinator.withExclusive { gate.await() } }
        advanceUntilIdle()

        coordinator.submitEvaluation("stale")
        // The evaluation consumer receives it, passes the generation fast path, and parks on the lock.
        advanceUntilIdle()
        coordinator.newRun()
        gate.complete(Unit)
        advanceUntilIdle()

        evaluationsSeen shouldBe emptyList()
    }

    @Test
    fun `a stale captured generation is dropped while the live one is delivered`() = runTest {
        val coordinator = launched()
        val stale = coordinator.currentGeneration
        coordinator.newRun()

        coordinator.submitEvaluationForGeneration("stale", stale)
        coordinator.submitEvaluationForGeneration("live", coordinator.currentGeneration)
        advanceUntilIdle()

        evaluationsSeen shouldBe listOf("live")
    }

    // --- Open / closed ----------------------------------------------------------------------------

    @Test
    fun `submitEvaluationIfOpen drops while closed and enqueues while open`() = runTest {
        val coordinator = launched()

        coordinator.isOpen shouldBe false
        coordinator.submitEvaluationIfOpen("closed") shouldBe false
        coordinator.open()
        coordinator.isOpen shouldBe true
        coordinator.submitEvaluationIfOpen("open") shouldBe true
        advanceUntilIdle()

        evaluationsSeen shouldBe listOf("open")
    }

    /** The 30s poll and the expiry nudge submit unconditionally — only the receiver gates on open. */
    @Test
    fun `submitEvaluation enqueues even while closed`() = runTest {
        val coordinator = launched()

        coordinator.isOpen shouldBe false
        coordinator.submitEvaluation("poll") shouldBe true
        advanceUntilIdle()

        evaluationsSeen shouldBe listOf("poll")
    }

    @Test
    fun `closeAndInvalidate stops accepting and invalidates what is already queued`() = runTest {
        val coordinator = launched()
        coordinator.open()
        coordinator.submitEvaluationIfOpen("queued") shouldBe true

        coordinator.closeAndInvalidate()

        coordinator.isOpen shouldBe false
        coordinator.submitEvaluationIfOpen("after") shouldBe false
        advanceUntilIdle()
        evaluationsSeen shouldBe emptyList()
    }

    // --- Mutual exclusion -------------------------------------------------------------------------

    @Test
    fun `a suspended command handler blocks a queued evaluation from starting`() = runTest {
        val gate = CompletableDeferred<Unit>()
        onCommand = {
            commandsSeen += it
            gate.await()
        }
        val coordinator = launched()

        coordinator.submitCommand("held")
        coordinator.submitEvaluation("waiting")
        advanceUntilIdle()

        commandsSeen shouldBe listOf("held")
        evaluationsSeen shouldBe emptyList()

        gate.complete(Unit)
        advanceUntilIdle()
        evaluationsSeen shouldBe listOf("waiting")
    }

    @Test
    fun `withExclusive blocks both consumers until it releases`() = runTest {
        val coordinator = launched()
        val gate = CompletableDeferred<Unit>()
        backgroundScope.launch { coordinator.withExclusive { gate.await() } }
        advanceUntilIdle()

        coordinator.submitCommand("c")
        coordinator.submitEvaluation("e")
        advanceUntilIdle()

        commandsSeen shouldBe emptyList()
        evaluationsSeen shouldBe emptyList()

        gate.complete(Unit)
        advanceUntilIdle()
        commandsSeen shouldBe listOf("c")
        evaluationsSeen shouldBe listOf("e")
    }

    /**
     * ACTION_START cancel-and-joins the recovery job while already holding the lock, and the recovery
     * tail waits on that same lock. That only avoids deadlock because a cancelled waiter aborts its
     * acquisition instead of blocking the canceller.
     */
    @Test
    fun `a waiter cancelled while the lock is held aborts its acquisition`() = runTest {
        val coordinator = launched()
        val gate = CompletableDeferred<Unit>()
        backgroundScope.launch { coordinator.withExclusive { gate.await() } }
        advanceUntilIdle()

        var ran = false
        val waiter = backgroundScope.launch { coordinator.withExclusive { ran = true } }
        advanceUntilIdle()

        // Cancelled and joined WHILE the lock is still held: the join must complete.
        waiter.cancelAndJoin()
        ran shouldBe false

        gate.complete(Unit)
        advanceUntilIdle()
        ran shouldBe false
    }

    // --- Lifecycle --------------------------------------------------------------------------------

    @Test
    fun `launching twice throws`() = runTest {
        val coordinator = launched()

        shouldThrow<IllegalStateException> { coordinator.launchInto(backgroundScope) }
    }

    @Test
    fun `launching after shutdown throws`() = runTest {
        val coordinator = DispatchCoordinator<String, String>()
        coordinator.shutdown()

        shouldThrow<IllegalStateException> { coordinator.launchInto(backgroundScope) }
    }

    @Test
    fun `open and every submit are inert after shutdown`() = runTest {
        val coordinator = launched()

        coordinator.shutdown()

        coordinator.open()
        coordinator.isOpen shouldBe false
        coordinator.submitCommand("c") shouldBe false
        coordinator.submitEvaluation("e") shouldBe false
        coordinator.submitEvaluationIfOpen("e") shouldBe false
        coordinator.submitEvaluationForGeneration("e", coordinator.currentGeneration) shouldBe false
        advanceUntilIdle()

        commandsSeen shouldBe emptyList()
        evaluationsSeen shouldBe emptyList()
    }

    /**
     * shutdown() closes the queues and nothing else: an in-flight handler runs to completion (only the
     * owner cancelling the scope stops it), and both consumers end once the queues are drained.
     */
    @Test
    fun `shutdown ends both consumers without cutting an in-flight handler short`() = runTest {
        val gate = CompletableDeferred<Unit>()
        var finished = false
        onCommand = {
            commandsSeen += it
            gate.await()
            finished = true
        }
        val coordinator = launched()

        coordinator.submitCommand("held")
        advanceUntilIdle()
        commandsSeen shouldBe listOf("held")
        finished shouldBe false

        coordinator.shutdown()
        advanceUntilIdle()
        finished shouldBe false
        consumersActive() shouldBe true

        gate.complete(Unit)
        advanceUntilIdle()
        finished shouldBe true
        consumersActive() shouldBe false
    }

    private fun TestScope.consumersActive(): Boolean =
        backgroundScope.coroutineContext[Job]!!.children.any { it.isActive }
}
