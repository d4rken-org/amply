package eu.darken.amply.main.core

import android.content.Context
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import dagger.hilt.android.qualifiers.ApplicationContext
import eu.darken.amply.charging.core.SETTLING_WINDOW_MILLIS
import eu.darken.amply.charging.core.SettleScheduler
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Durable [SettleScheduler]: enqueues a single, replaceable [SettleRefreshWorker] to run just after the
 * settling window of the latest request. Unique work with [ExistingWorkPolicy.REPLACE] coalesces rapid
 * successive requests to one pending clear, and WorkManager guarantees it runs even across process death
 * (at the cost of a possible few-seconds-late clear — the in-app clock keeps the dashboard prompt).
 */
@Singleton
class WorkManagerSettleScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
) : SettleScheduler {

    override fun schedule(requestedAtMillis: Long) {
        val fireAt = requestedAtMillis + SETTLING_WINDOW_MILLIS + CLEAR_BUFFER_MILLIS
        val delay = (fireAt - System.currentTimeMillis()).coerceAtLeast(0L)
        val request = OneTimeWorkRequestBuilder<SettleRefreshWorker>()
            .setInitialDelay(delay, TimeUnit.MILLISECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            SettleRefreshWorker.UNIQUE_NAME,
            ExistingWorkPolicy.REPLACE,
            request,
        )
    }

    private companion object {
        const val CLEAR_BUFFER_MILLIS = 1_000L
    }
}
