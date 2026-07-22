package eu.darken.amply.charging.core.access

import eu.darken.amply.charging.core.ChargingState
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.io.IOException

/**
 * Drives the orchestration with an [UnconfinedTestDispatcher] so the collector subscribes eagerly and
 * processes each emission (and its launched grant) synchronously — the grant counter reflects launches
 * the moment an input is emitted.
 */
class AutoWssGrantCoordinatorTest {

    private val ready = AutoWssGrantInputs(
        onboardingComplete = true, controlEnabled = true, shizukuReady = true, wssReady = false,
        writeRequiresShizuku = false,
    )
    private val wssGranted = ready.copy(wssReady = true)
    private val shizukuGone = ready.copy(shizukuReady = false)
    private val preOnboarding = ready.copy(onboardingComplete = false)

    @Test
    fun `a single ready episode grants exactly once`() = runTest(UnconfinedTestDispatcher()) {
        val inputs = MutableSharedFlow<AutoWssGrantInputs>(extraBufferCapacity = 64)
        var grants = 0
        backgroundScope.launch { observeAutoWssGrant(inputs, backgroundScope) { grants++ } }

        repeat(3) { inputs.emit(ready) }

        grants shouldBe 1
    }

    @Test
    fun `obtaining wss ends the episode and a later loss re-arms`() = runTest(UnconfinedTestDispatcher()) {
        val inputs = MutableSharedFlow<AutoWssGrantInputs>(extraBufferCapacity = 64)
        var grants = 0
        backgroundScope.launch { observeAutoWssGrant(inputs, backgroundScope) { grants++ } }

        inputs.emit(ready)       // grant #1
        inputs.emit(wssGranted)  // episode over, no grant
        inputs.emit(ready)       // wss lost again -> grant #2

        grants shouldBe 2
    }

    @Test
    fun `a shizuku bounce during a slow grant re-arms without being conflated`() = runTest(UnconfinedTestDispatcher()) {
        val inputs = MutableSharedFlow<AutoWssGrantInputs>(extraBufferCapacity = 64)
        val gate = CompletableDeferred<Unit>()
        var grants = 0
        backgroundScope.launch { observeAutoWssGrant(inputs, backgroundScope) { grants++; gate.await() } }

        inputs.emit(ready)          // grant #1 starts, then suspends on the gate
        grants shouldBe 1
        // The collector must NOT be blocked inside the suspended grant: it still sees the reset...
        inputs.emit(shizukuGone)
        inputs.emit(ready)          // ...and re-arms -> grant #2
        grants shouldBe 2

        gate.complete(Unit)
    }

    @Test
    fun `a failing grant does not stop future episodes`() = runTest(UnconfinedTestDispatcher()) {
        val inputs = MutableSharedFlow<AutoWssGrantInputs>(extraBufferCapacity = 64)
        var grants = 0
        // Mirrors the production wiring, which wraps the grant in a catch before handing it here.
        backgroundScope.launch {
            observeAutoWssGrant(inputs, backgroundScope) {
                grants++
                runCatching { if (grants == 1) throw RuntimeException("boom") }
            }
        }

        inputs.emit(ready)          // grant #1 fails internally
        inputs.emit(shizukuGone)
        inputs.emit(ready)          // grant #2 still happens

        grants shouldBe 2
    }

    @Test
    fun `never grants before onboarding then grants once onboarded`() = runTest(UnconfinedTestDispatcher()) {
        val inputs = MutableSharedFlow<AutoWssGrantInputs>(extraBufferCapacity = 64)
        var grants = 0
        backgroundScope.launch { observeAutoWssGrant(inputs, backgroundScope) { grants++ } }

        inputs.emit(preOnboarding)
        grants shouldBe 0
        inputs.emit(ready)          // onboarding complete now -> grant
        grants shouldBe 1
    }

    @Test
    fun `input stream survives a datastore read error instead of crashing`() = runTest {
        val states = MutableStateFlow(ChargingState())
        var onboardingSubscriptions = 0
        val onboarding = flow {
            onboardingSubscriptions++
            emit(true)
            throw IOException("datastore boom")
        }

        // retryWhen re-subscribes a bounded number of times, then catch ends the stream quietly:
        // collection must complete without the IOException escaping (which in production would crash
        // the app via the collector's launch).
        val collected = autoWssGrantInputs(states, onboarding).toList()

        collected.shouldNotBeEmpty()
        onboardingSubscriptions shouldBe 4 // initial attempt + 3 retries
    }
}
