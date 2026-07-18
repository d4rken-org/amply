package eu.darken.amply

import android.app.Application
import dagger.hilt.android.HiltAndroidApp
import eu.darken.amply.common.debug.logging.LogCatLogger
import eu.darken.amply.common.debug.logging.Logging
import eu.darken.amply.common.debug.logging.log
import eu.darken.amply.common.debug.logging.logTag

@HiltAndroidApp
class AmplyApp : Application() {
    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.DEBUG) Logging.install(LogCatLogger())
        log(TAG, Logging.Priority.INFO) { "Amply started ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})" }
    }

    private companion object {
        val TAG = logTag("App")
    }
}
