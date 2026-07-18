package eu.darken.amply.common.debug.logging

import java.io.PrintWriter
import java.io.StringWriter

object Logging {
    enum class Priority(val androidValue: Int, val shortLabel: String) {
        VERBOSE(2, "V"),
        DEBUG(3, "D"),
        INFO(4, "I"),
        WARN(5, "W"),
        ERROR(6, "E"),
        ASSERT(7, "WTF"),
    }

    interface Logger {
        fun isLoggable(priority: Priority): Boolean = true
        fun log(priority: Priority, tag: String, message: String, metadata: Map<String, Any>?)
    }

    private val installed = mutableListOf<Logger>()
    val hasReceivers: Boolean get() = synchronized(installed) { installed.isNotEmpty() }

    fun install(logger: Logger) = synchronized(installed) {
        if (logger !in installed) installed += logger
    }

    fun remove(logger: Logger) = synchronized(installed) { installed -= logger }

    fun logInternal(tag: String, priority: Priority, metadata: Map<String, Any>?, message: String) {
        synchronized(installed) { installed.toList() }
            .filter { it.isLoggable(priority) }
            .forEach { it.log(priority, tag, message, metadata) }
    }
}

inline fun log(
    tag: String,
    priority: Logging.Priority = Logging.Priority.DEBUG,
    metadata: Map<String, Any>? = null,
    message: () -> String,
) {
    if (Logging.hasReceivers) Logging.logInternal(tag, priority, metadata, message())
}

inline fun Any.log(
    priority: Logging.Priority = Logging.Priority.DEBUG,
    metadata: Map<String, Any>? = null,
    message: () -> String,
) = log(logTag(this::class.java.simpleName.removeSuffix("Kt")), priority, metadata, message)

fun logTag(vararg tags: String): String = "AMP:" + tags.joinToString(":")

fun Throwable.asLog(): String = StringWriter(256).also { writer ->
    PrintWriter(writer, false).use { printWriter -> printStackTrace(printWriter) }
}.toString()
