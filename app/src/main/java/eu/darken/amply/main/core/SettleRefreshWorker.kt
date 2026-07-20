package eu.darken.amply.main.core

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import eu.darken.amply.charging.core.ChargingRepository
import kotlinx.coroutines.CancellationException

/**
 * Fires once at the end of a request's settling window: re-reads charging state (which expires the
 * transient "applying…" cue) and pushes the widget/tile so they clear even if the app process was
 * killed after the write. Enqueued by [WorkManagerSettleScheduler] as unique, replaceable work.
 */
class SettleRefreshWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val entryPoint = EntryPointAccessors.fromApplication(
            applicationContext,
            SettleWorkerEntryPoint::class.java,
        )
        return try {
            entryPoint.chargingRepository().refresh()
            // Await the surface push in the worker's own scope so the process can't die before it lands.
            SurfaceUpdater.updateNow(applicationContext)
            Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.retry()
        }
    }

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface SettleWorkerEntryPoint {
        fun chargingRepository(): ChargingRepository
    }

    companion object {
        const val UNIQUE_NAME = "settle-refresh"
    }
}
