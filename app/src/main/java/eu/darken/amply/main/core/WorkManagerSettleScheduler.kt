package eu.darken.amply.main.core

import android.content.Context
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import dagger.hilt.android.qualifiers.ApplicationContext
import eu.darken.amply.charging.core.SETTLING_WINDOW_MILLIS
import eu.darken.amply.charging.core.SettleScheduler
import eu.darken.amply.charging.core.UNCONFIRMED_THRESHOLD_MILLIS
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
        enqueue(
            SettleRefreshWorker.UNIQUE_NAME,
            requestedAtMillis + SETTLING_WINDOW_MILLIS + CLEAR_BUFFER_MILLIS,
        )
        // Second, separate push at the hardware-unconfirmed threshold: the silent-failure case this
        // detector exists for (no HAL transition) may produce no charging-status broadcast, and the
        // dashboard's own deadline observer disarms when pending clears at the window — without this
        // the warning could wait indefinitely for an unrelated refresh trigger.
        enqueue(
            UNCONFIRMED_UNIQUE_NAME,
            requestedAtMillis + UNCONFIRMED_THRESHOLD_MILLIS + CLEAR_BUFFER_MILLIS,
        )
    }

    private fun enqueue(uniqueName: String, fireAtMillis: Long) {
        val delay = (fireAtMillis - System.currentTimeMillis()).coerceAtLeast(0L)
        val request = OneTimeWorkRequestBuilder<SettleRefreshWorker>()
            .setInitialDelay(delay, TimeUnit.MILLISECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            uniqueName,
            ExistingWorkPolicy.REPLACE,
            request,
        )
    }

    private companion object {
        const val CLEAR_BUFFER_MILLIS = 1_000L
        const val UNCONFIRMED_UNIQUE_NAME = "${SettleRefreshWorker.UNIQUE_NAME}_unconfirmed"
    }
}
