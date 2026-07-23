package eu.darken.amply.charging.core.access

import eu.darken.amply.charging.core.ChargingRepository
import eu.darken.amply.charging.core.ChargingState
import eu.darken.amply.common.debug.logging.Logging
import eu.darken.amply.common.debug.logging.log
import eu.darken.amply.common.debug.logging.logTag
import eu.darken.amply.main.core.OnboardingSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/** The Shizuku/WSS signals [AutoWssGrantPolicy] decides on, distilled from [ChargingState] + onboarding. */
internal data class AutoWssGrantInputs(
    val onboardingComplete: Boolean,
    val controlEnabled: Boolean,
    val shizukuReady: Boolean,
    val wssReady: Boolean,
    val writeRequiresShizuku: Boolean,
)

/**
 * Process-lifetime observer that auto-grants WRITE_SECURE_SETTINGS the moment Amply first sees Shizuku
 * ready without WSS. It lives here rather than in a screen ViewModel because the singleton
 * [ChargingRepository] is refreshed from many surfaces (tile, widget, worker, session service), so
 * readiness can be observed with no dashboard on screen — and a per-screen latch would re-fire across
 * activity recreation. Started once from `AmplyApp.onCreate()`.
 */
@Singleton
class AutoWssGrantCoordinator @Inject constructor(
    private val repository: ChargingRepository,
    private val onboardingSettings: OnboardingSettings,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val started = AtomicBoolean(false)

    fun start() {
        if (!started.compareAndSet(false, true)) return
        scope.launch {
            observeAutoWssGrant(
                inputs = autoWssGrantInputs(repository.state, onboardingSettings.isComplete),
                grantScope = scope,
            ) {
                // The repository single-flights this call, so a concurrent manual tap or an overlapping
                // re-arm cannot run two pm grants. Catch (but re-throw cancellation) so a grant failure
                // never escapes into the launch and reaches the app's uncaught-exception handler.
                try {
                    repository.grantWriteSecureSettings()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    log(AutoWssGrantLog.TAG, Logging.Priority.WARN) { "Auto WSS grant failed: ${e.message}" }
                }
            }
        }
    }
}

/**
 * Builds the coordinator's input stream. Resilient by design: [OnboardingSettings.isComplete] reads
 * DataStore, which can throw on an I/O or corruption error; without this the uncaught exception would
 * propagate out of the collector's `launch` and crash the app. Transient errors are retried a few times,
 * then the stream ends quietly (auto-grant degrades to the manual setup-card button — no crash).
 */
internal fun autoWssGrantInputs(
    states: Flow<ChargingState>,
    onboardingComplete: Flow<Boolean>,
): Flow<AutoWssGrantInputs> = combine(states, onboardingComplete) { state, onboarded ->
    AutoWssGrantInputs(
        onboardingComplete = onboarded,
        controlEnabled = state.controlEnabled,
        shizukuReady = state.access?.shizuku?.ready == true,
        wssReady = state.access?.direct?.ready == true,
        writeRequiresShizuku = state.writeRequiresShizuku,
    )
}.distinctUntilChanged()
    .retryWhen { cause, attempt ->
        if (cause is CancellationException) return@retryWhen false
        log(AutoWssGrantLog.TAG, Logging.Priority.WARN) {
            "Auto WSS input error (attempt $attempt): ${cause.message}"
        }
        if (attempt < AutoWssGrantLog.MAX_INPUT_RETRIES) {
            delay(AutoWssGrantLog.RETRY_DELAY_MILLIS)
            true
        } else {
            false
        }
    }
    .catch { log(AutoWssGrantLog.TAG, Logging.Priority.ERROR) { "Auto WSS input stream stopped: ${it.message}" } }

/**
 * Core orchestration, top-level so it is JVM-testable without constructing the coordinator's Android
 * dependencies. The collector must stay non-blocking: it launches the grant on [grantScope] instead of
 * awaiting it, so the grant's deliberate ~25s suspension can never suspend this collector. A Shizuku
 * bounce that the collector *observes* re-arms the next episode; note [repository.state] is a conflated
 * StateFlow, so a bounce that lands entirely between two collector runs is coalesced away — that episode
 * simply falls back to the manual button, which is the intended failure behaviour.
 *
 * [grantScope] is expected to isolate grant failures from this collector (the production scope is backed
 * by a [SupervisorJob], and the caller additionally wraps [grant] in a catch).
 */
internal suspend fun observeAutoWssGrant(
    inputs: Flow<AutoWssGrantInputs>,
    grantScope: CoroutineScope,
    grant: suspend () -> Unit,
) {
    var attempted = false
    inputs.collect { input ->
        val outcome = AutoWssGrantPolicy.evaluate(
            onboardingComplete = input.onboardingComplete,
            controlEnabled = input.controlEnabled,
            shizukuReady = input.shizukuReady,
            wssReady = input.wssReady,
            writeRequiresShizuku = input.writeRequiresShizuku,
            attempted = attempted,
        )
        attempted = outcome.attempted
        if (outcome.grant) grantScope.launch { grant() }
    }
}

/** Log tag + tuning shared by the top-level [autoWssGrantInputs] helper. */
private object AutoWssGrantLog {
    val TAG = logTag("Charging", "AutoWssGrant")
    const val MAX_INPUT_RETRIES = 3L
    const val RETRY_DELAY_MILLIS = 1_000L
}
