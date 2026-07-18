package eu.darken.amply.common.debug

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import eu.darken.amply.BuildConfig
import eu.darken.amply.common.debug.logging.FileLogger
import eu.darken.amply.common.debug.logging.Logging
import eu.darken.amply.common.debug.logging.log
import eu.darken.amply.common.debug.logging.logTag
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.inject.Inject
import javax.inject.Singleton

data class DebugLogState(
    val recording: Boolean = false,
    val currentPath: String? = null,
    val sessions: List<File> = emptyList(),
)

@Singleton
class DebugLogManager @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val mutex = Mutex()
    private val root = File(context.cacheDir, "debug/logs")
    private val mutableState = MutableStateFlow(DebugLogState())
    val state: StateFlow<DebugLogState> = mutableState.asStateFlow()
    private var activeLogger: FileLogger? = null
    private var activeDirectory: File? = null

    init {
        refresh()
    }

    suspend fun start(): Unit = mutex.withLock {
        if (activeLogger != null) return@withLock
        withContext(Dispatchers.IO) {
            root.mkdirs()
            val directory = File(root, "amply_${BuildConfig.VERSION_NAME}_${STAMP.format(Instant.now())}")
            val logFile = File(directory, "core.log")
            File(directory, "device.txt").apply {
                parentFile?.mkdirs()
                writeText(
                    "app=${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})\n" +
                        "device=${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}\n" +
                        "android=${android.os.Build.VERSION.RELEASE} api=${android.os.Build.VERSION.SDK_INT}\n" +
                        "build=${android.os.Build.DISPLAY}\n",
                )
            }
            val logger = FileLogger(logFile)
            logger.start()
            Logging.install(logger)
            activeDirectory = directory
            activeLogger = logger
            log(TAG, Logging.Priority.INFO) { "Debug recording started: ${directory.name}" }
        }
        refresh()
    }

    suspend fun stop(): File? = mutex.withLock {
        val directory = activeDirectory ?: return@withLock null
        withContext(Dispatchers.IO) {
            log(TAG, Logging.Priority.INFO) { "Debug recording stopping" }
            activeLogger?.let(Logging::remove)
            activeLogger?.stop()
            activeLogger = null
            activeDirectory = null
            zipDirectory(directory).also { directory.deleteRecursively() }
        }.also { refresh() }
    }

    suspend fun latestShareUri(): Uri? = mutex.withLock {
        withContext(Dispatchers.IO) {
            val file = sessionFiles().firstOrNull() ?: return@withContext null
            FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        }
    }

    suspend fun clear() = mutex.withLock {
        withContext(Dispatchers.IO) {
            sessionFiles().forEach { it.delete() }
        }
        refresh()
    }

    fun refresh() {
        mutableState.value = DebugLogState(
            recording = activeLogger != null,
            currentPath = activeDirectory?.path,
            sessions = sessionFiles(),
        )
    }

    private fun sessionFiles(): List<File> = root.listFiles()
        .orEmpty()
        .filter { it.isFile && it.extension == "zip" }
        .sortedByDescending(File::lastModified)

    private fun zipDirectory(directory: File): File {
        val target = File(root, "${directory.name}.zip")
        ZipOutputStream(target.outputStream().buffered()).use { zip ->
            directory.walkTopDown().filter(File::isFile).forEach { file ->
                zip.putNextEntry(ZipEntry(file.relativeTo(directory).invariantSeparatorsPath))
                file.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
        return target
    }

    private companion object {
        val TAG = logTag("Debug", "LogManager")
        val STAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")
            .withZone(ZoneOffset.UTC)
    }
}
