package eu.darken.amply

import android.app.Application
import dagger.hilt.android.HiltAndroidApp
import eu.darken.amply.charging.core.access.AutoWssGrantCoordinator
import eu.darken.amply.common.debug.logging.LogCatLogger
import eu.darken.amply.common.debug.logging.Logging
import eu.darken.amply.common.debug.logging.log
import eu.darken.amply.common.debug.logging.logTag
import javax.inject.Inject

@HiltAndroidApp
class AmplyApp : Application() {

    @Inject lateinit var autoWssGrantCoordinator: AutoWssGrantCoordinator

    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.DEBUG) Logging.install(LogCatLogger())
        log(TAG, Logging.Priority.INFO) { "Amply started ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})" }
        // Once Shizuku access exists, grant the durable WRITE_SECURE_SETTINGS ourselves instead of
        // making the user tap the separate setup-card button.
        autoWssGrantCoordinator.start()
    }

    private companion object {
        val TAG = logTag("App")
    }
}
