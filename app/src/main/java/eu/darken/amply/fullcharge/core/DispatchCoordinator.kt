package eu.darken.amply.fullcharge.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicInteger

/**
 * Serialized two-queue dispatch core for the charge-session service: a [C]ommand stream (user/system
 * intents) and an [E]valuation stream (battery observations), drained by one consumer each but under a
 * SINGLE shared lock, so a command and an evaluation can never run concurrently.
 *
 * Why a queue per stream rather than one:
 * - Commands arrive from `onStartCommand` on the main thread and must keep arrival order, so rapid taps
 *   (e.g. "∞ 80%" then "∞ 100%") can never be reordered by the dispatcher and finish on the wrong one.
 * - Evaluations arrive from the battery receiver, the 30s poll and the gesture expiry nudge. Concurrent
 *   evaluations could observe plug edges out of order and corrupt the gesture state machine (its per-tick
 *   preference reads suspend, widening the reorder window).
 *
 * Deliberately free of Android types: payloads carry whatever the service needs (`Intent`,
 * `SystemClock.elapsedRealtime()`), so this stays a pure, JVM-testable unit.
 *
 * **Generations.** [newRun] stamps a new monitoring run. Evaluations are stamped on submit and filtered
 * TWICE on drain — once before acquiring the lock, and once inside it, because a command can restart
 * monitoring between the two. Events queued for a previous run can therefore never replay into freshly
 * reset state. The counter is an [AtomicInteger] so a concurrent increment cannot be lost, but that is
 * NOT a substitute for the ordering invariant: [newRun] and [closeAndInvalidate] must only ever be called
 * from a path that already holds exclusivity (a command handler, an evaluation handler, or [withExclusive]),
 * so the generation change stays atomic with respect to the gesture-state mutation it belongs to.
 *
 * **Lifecycle.** [launch] is single-shot — a second consumer per channel would race for the lock and
 * destroy FIFO order — and [shutdown] is terminal: afterwards [open] is a no-op and every submit is
 * rejected. [shutdown] closes the queues and does NOTHING else; it deliberately does not cancel the
 * consumers. Closing lets an in-flight handler finish; stopping them is the owning scope's job, and the
 * owner must cancel that scope immediately after [shutdown]. Cancelling consumers here would release the
 * lock mid-handler and let a waiter (e.g. a recovery tail parked in [withExclusive]) run during teardown.
 */
class DispatchCoordinator<C : Any, E : Any> {

    private data class Envelope<E : Any>(val payload: E, val generation: Int)

    // One lock for BOTH consumers: this is what serializes commands against evaluations.
    private val mutex = Mutex()
    private val commands = Channel<C>(Channel.UNLIMITED)
    private val evaluations = Channel<Envelope<E>>(Channel.UNLIMITED)
    private val generation = AtomicInteger(0)

    @Volatile private var accepting = false
    @Volatile private var launched = false
    @Volatile private var terminated = false

    /** The current monitoring run, for callers that must capture it now and submit later. */
    val currentGeneration: Int get() = generation.get()

    /** Whether broadcast-driven evaluations are currently welcome (see [submitEvaluationIfOpen]). */
    val isOpen: Boolean get() = accepting

    /**
     * Start the two consumers on [scope]. Single-shot: throws [IllegalStateException] if already launched
     * or if the coordinator was already shut down.
     *
     * A failure in one item (e.g. a surface update throwing) must not kill its consumer and strand
     * everything queued behind it, so each item is isolated: cancellation propagates, anything else is
     * reported to [onError] and the consumer moves on.
     */
    fun launch(
        scope: CoroutineScope,
        onCommand: suspend (C) -> Unit,
        onEvaluation: suspend (E) -> Unit,
        onError: (String, Exception) -> Unit,
    ) {
        check(!launched) { "DispatchCoordinator was already launched" }
        check(!terminated) { "DispatchCoordinator was already shut down" }
        launched = true
        scope.launch {
            for (command in commands) {
                try {
                    mutex.withLock { onCommand(command) }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    onError("Command $command", e)
                }
            }
        }
        scope.launch {
            for (envelope in evaluations) {
                if (envelope.generation != generation.get()) continue
                try {
                    mutex.withLock {
                        // Re-check under the lock: a command can restart monitoring between the
                        // fast-path check above and the mutex acquisition.
                        if (envelope.generation != generation.get()) return@withLock
                        onEvaluation(envelope.payload)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    onError("Battery evaluation", e)
                }
            }
        }
    }

    /**
     * Begin a new monitoring run: everything already queued for the previous one is dropped on drain.
     * Only call from an already-exclusive path (see the generations note in the class doc).
     */
    fun newRun(): Int = generation.incrementAndGet()

    /** Start accepting broadcast-driven evaluations. No-op once [shutdown] has run. */
    fun open() {
        if (terminated) return
        accepting = true
    }

    /** Stop accepting broadcast-driven evaluations; already-queued ones stay valid. */
    fun close() {
        accepting = false
    }

    /**
     * Stop accepting broadcast-driven evaluations AND invalidate everything already queued for this run.
     * Only call from an already-exclusive path (see the generations note in the class doc).
     */
    fun closeAndInvalidate() {
        accepting = false
        generation.incrementAndGet()
    }

    fun submitCommand(command: C): Boolean = commands.trySend(command).isSuccess

    /** Submit only while [isOpen] — for the battery receiver, which must fall silent while quiesced. */
    fun submitEvaluationIfOpen(payload: E): Boolean {
        if (!accepting) return false
        return submitEvaluation(payload)
    }

    /** Submit for the current run regardless of [isOpen] — for the service's own polling paths. */
    fun submitEvaluation(payload: E): Boolean = submitEvaluationForGeneration(payload, generation.get())

    /**
     * Submit for a run captured earlier, so a delayed nudge scheduled while that run was live cannot
     * leak into a later one.
     */
    fun submitEvaluationForGeneration(payload: E, generation: Int): Boolean =
        evaluations.trySend(Envelope(payload, generation)).isSuccess

    /**
     * Run [block] under the same lock the consumers use, for service paths that mutate dispatch state
     * outside a handler. A bare, cancellable delegation on purpose: no `NonCancellable`, no retry, no
     * swallowed cancellation. Callers that cancel-and-join a job while already holding this lock rely on
     * a cancelled waiter aborting its acquisition instead of blocking the canceller.
     */
    suspend fun <T> withExclusive(block: suspend () -> T): T = mutex.withLock { block() }

    /**
     * Close both queues. Terminal, and deliberately nothing else — see the lifecycle note in the class
     * doc. The owner must cancel the consumers' scope immediately afterwards.
     */
    fun shutdown() {
        terminated = true
        commands.close()
        evaluations.close()
    }
}
