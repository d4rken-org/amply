package eu.darken.amply.common.debug.logging

import android.os.Build
import android.util.Log
import kotlin.math.min

class LogCatLogger : Logging.Logger {
    override fun log(
        priority: Logging.Priority,
        tag: String,
        message: String,
        metadata: Map<String, Any>?,
    ) {
        val effectiveTag = if (tag.length <= 23 || Build.VERSION.SDK_INT >= 26) tag else tag.take(23)
        val fullMessage = if (metadata.isNullOrEmpty()) message else "$message metadata=$metadata"
        var index = 0
        while (index < fullMessage.length) {
            val end = min(index + MAX_LOG_LENGTH, fullMessage.length)
            Log.println(priority.androidValue, effectiveTag, fullMessage.substring(index, end))
            index = end
        }
        if (fullMessage.isEmpty()) Log.println(priority.androidValue, effectiveTag, "")
    }

    private companion object {
        const val MAX_LOG_LENGTH = 4_000
    }
}
