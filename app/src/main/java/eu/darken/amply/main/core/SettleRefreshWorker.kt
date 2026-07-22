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
            // updateNow awaits Glance *enqueuing* its durable update work, not the eventual composition +
            // AppWidgetManager delivery (Glance does that later in its own worker). Process-death safety for
            // the actual push therefore rests on Glance's durable work, not on awaiting here; this call still
            // ensures the enqueue happens (and a failure at that step yields Result.retry below).
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
