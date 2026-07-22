package eu.darken.amply.stats.core

import android.content.Intent

/**
 * The unenriched hand-off from [ChargeStatsWatcher] to [ChargeStatsRecorder]. Constructed on the
 * charge-session service's evaluation thread with only cheap in-memory copies plus a reference to
 * the already-evaluated battery intent — no Binder or DataStore reads. The recorder parses the
 * intent and reads live battery properties on its own IO thread, keeping all such work off the
 * service's `commandMutex`.
 */
data class RawStatsTick(
    val plugged: Boolean,
    val percent: Int,
    val batteryStatus: Int,
    val sessionActive: Boolean,
    val batteryIntent: Intent?,
    val observedElapsedRealtimeMillis: Long,
    val wallMillis: Long,
)
