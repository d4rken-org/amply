package eu.darken.amply

import android.app.Application
import android.util.Log
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import dagger.hilt.android.HiltAndroidApp
import eu.darken.amply.charging.core.access.AutoWssGrantCoordinator
import eu.darken.amply.common.debug.logging.LogCatLogger
import eu.darken.amply.common.debug.logging.Logging
import eu.darken.amply.common.debug.logging.log
import eu.darken.amply.common.debug.logging.logTag
import eu.darken.amply.upgrade.core.UpgradeSurfaceSync
import javax.inject.Inject

@HiltAndroidApp
class AmplyApp : Application(), Configuration.Provider {

    @Inject lateinit var autoWssGrantCoordinator: AutoWssGrantCoordinator
    @Inject lateinit var upgradeSurfaceSync: UpgradeSurfaceSync
    @Inject lateinit var workerFactory: HiltWorkerFactory

    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.DEBUG) Logging.install(LogCatLogger())
        log(TAG, Logging.Priority.INFO) { "Amply started ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})" }
        // Once Shizuku access exists, grant the durable WRITE_SECURE_SETTINGS ourselves instead of
        // making the user tap the separate setup-card button.
        autoWssGrantCoordinator.start()
        // The tile and widget render from stale snapshots; nothing else pushes them when an
        // entitlement lands or lapses.
        upgradeSurfaceSync.start()
    }

    // Read lazily by WorkManager's on-demand initialization (the androidx.startup initializer is
    // removed in the manifest), so the injected factory is always set by the time it is asked for.
    // Workers this factory doesn't know fall back to WorkManager's default reflective factory.
    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setMinimumLoggingLevel(if (BuildConfig.DEBUG) Log.DEBUG else Log.WARN)
            .setWorkerFactory(workerFactory)
            .build()

    private companion object {
        val TAG = logTag("App")
    }
}
