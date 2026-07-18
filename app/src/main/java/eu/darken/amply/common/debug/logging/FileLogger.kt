package eu.darken.amply.common.debug.logging

import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.time.Instant

class FileLogger(private val logFile: File) : Logging.Logger {
    private var writer: OutputStreamWriter? = null

    @Synchronized
    fun start() {
        if (writer != null) return
        logFile.parentFile?.mkdirs()
        writer = OutputStreamWriter(FileOutputStream(logFile, true), Charsets.UTF_8).also {
            it.write("=== BEGIN Amply debug log ${Instant.now()} ===\n")
            it.flush()
        }
    }

    @Synchronized
    fun stop() {
        writer?.runCatching {
            write("=== END ${Instant.now()} ===\n")
            flush()
            close()
        }
        writer = null
    }

    @Synchronized
    override fun log(
        priority: Logging.Priority,
        tag: String,
        message: String,
        metadata: Map<String, Any>?,
    ) {
        writer?.runCatching {
            write("${Instant.now()} ${priority.shortLabel}/$tag: $message")
            if (!metadata.isNullOrEmpty()) write(" metadata=$metadata")
            write("\n")
            flush()
        }
    }

    override fun toString(): String = "FileLogger(${logFile.path})"
}
