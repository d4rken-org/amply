package eu.darken.amply.battery.core

import eu.darken.amply.common.debug.logging.Logging
import eu.darken.amply.common.debug.logging.log
import eu.darken.amply.common.debug.logging.logTag
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Emits a fresh [BatteryReadout] on a fixed interval while collected. Cold, so collection lifetime
 * is controlled by the collector (the dashboard's `WhileSubscribed`), which means no polling runs
 * while the UI is gone — there is no background work here at all.
 *
 * Failure isolation, bounded: a throwing read repeats the last-known readout instead of terminating
 * the flow, so a single bad poll can never tear down the combined dashboard state or strand
 * onboarding. But repeating it *forever* would turn a dead reader into a frozen display that still
 * says "Charging · 82%" long after the cable came out — the surfaces that render this label it
 * "Now". So the repeat is capped at [STALE_TOLERANCE_READS] consecutive failures, after which the
 * flow emits [BatteryReadout.UNKNOWN] and every field honestly reads "Not reported" until a read
 * succeeds again.
 *
 * Reads run on [Dispatchers.IO] so the sticky-broadcast query and property reads never touch the
 * main thread.
 */
@Singleton
class BatteryReadoutSource @Inject constructor(
    private val reader: BatteryReader,
) {
    fun readouts(intervalMillis: Long = DEFAULT_INTERVAL_MILLIS): Flow<BatteryReadout> =
        batteryReadouts(intervalMillis, reader::read).flowOn(Dispatchers.IO)

    private companion object {
        const val DEFAULT_INTERVAL_MILLIS = 3_000L
    }
}

/**
 * The polling/staleness loop, extracted from the Android-bound [BatteryReadoutSource] so the failure
 * behaviour above is JVM-testable against a scripted [read] rather than a real device.
 */
internal fun batteryReadouts(
    intervalMillis: Long,
    read: () -> BatteryReadout,
): Flow<BatteryReadout> = flow {
    // The last *successful* read, so a recovery always emits fresh data rather than a stale copy.
    var lastSuccess: BatteryReadout? = null
    var consecutiveFailures = 0
    while (true) {
        val fresh = try {
            read()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log(TAG, Logging.Priority.WARN) { "Battery read failed (#${consecutiveFailures + 1}): ${e.message}" }
            null
        }
        if (fresh != null) {
            lastSuccess = fresh
            consecutiveFailures = 0
        } else {
            consecutiveFailures++
        }
        emit(
            when {
                fresh != null -> fresh
                // Ride out a blip on the last good reading…
                consecutiveFailures <= STALE_TOLERANCE_READS -> lastSuccess ?: BatteryReadout.UNKNOWN
                // …but stop asserting it once the reader is properly broken.
                else -> BatteryReadout.UNKNOWN
            },
        )
        delay(intervalMillis)
    }
}

/**
 * Consecutive failed reads that may still show the previous reading. At the default interval that is
 * ~6s of tolerance — long enough to hide a transient failure, short enough that a genuinely stale
 * value is never presented as the current state for more than a glance.
 */
private const val STALE_TOLERANCE_READS = 2

private val TAG = logTag("Battery", "ReadoutSource")
