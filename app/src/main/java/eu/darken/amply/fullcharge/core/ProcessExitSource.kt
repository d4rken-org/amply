package eu.darken.amply.fullcharge.core

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import dagger.hilt.android.qualifiers.ApplicationContext
import eu.darken.amply.common.debug.logging.Logging
import eu.darken.amply.common.debug.logging.log
import eu.darken.amply.common.debug.logging.logTag
import javax.inject.Inject
import javax.inject.Singleton

/** The historical process-exit records, abstracted so the assessor stays pure-JVM testable. */
fun interface ExitSource {
    fun recentExits(limit: Int): List<ExitRecord>
}

/**
 * Thin wrapper over `ActivityManager.getHistoricalProcessExitReasons`, filtered to Amply's main
 * process. Purely cosmetic input to the interruption reason — best-effort: below API 30 or on any
 * failure it returns an empty list, so classification simply falls back to [InterruptionReason.OTHER].
 */
@Singleton
class ProcessExitSource @Inject constructor(
    @ApplicationContext private val context: Context,
) : ExitSource {
    override fun recentExits(limit: Int): List<ExitRecord> {
        if (Build.VERSION.SDK_INT < 30) return emptyList()
        return try {
            val am = context.getSystemService(ActivityManager::class.java)
            val mainProcessName = context.packageName
            am.getHistoricalProcessExitReasons(null, 0, limit)
                .filter { it.processName == mainProcessName }
                .map { ExitRecord(timestampMillis = it.timestamp, pid = it.pid, reason = it.reason) }
        } catch (e: Exception) {
            log(TAG, Logging.Priority.WARN) { "Could not read historical exit reasons: ${e.message}" }
            emptyList()
        }
    }

    private companion object {
        val TAG = logTag("FullCharge", "ProcessExitSource")
    }
}
