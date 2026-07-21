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
 * Failure isolation: a throwing read emits the last-known readout (or [BatteryReadout.UNKNOWN]
 * before the first success) instead of terminating the flow, so a single bad poll can never tear
 * down the combined dashboard state or strand onboarding. Reads run on [Dispatchers.IO] so the
 * sticky-broadcast query and property reads never touch the main thread.
 */
@Singleton
class BatteryReadoutSource @Inject constructor(
    private val reader: BatteryReader,
) {
    fun readouts(intervalMillis: Long = DEFAULT_INTERVAL_MILLIS): Flow<BatteryReadout> = flow {
        var last = BatteryReadout.UNKNOWN
        while (true) {
            last = try {
                reader.read()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log(TAG, Logging.Priority.WARN) { "Battery read failed, keeping last value: ${e.message}" }
                last
            }
            emit(last)
            delay(intervalMillis)
        }
    }.flowOn(Dispatchers.IO)

    private companion object {
        val TAG = logTag("Battery", "ReadoutSource")
        const val DEFAULT_INTERVAL_MILLIS = 3_000L
    }
}
