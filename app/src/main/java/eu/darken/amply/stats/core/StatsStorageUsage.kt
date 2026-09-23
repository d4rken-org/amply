package eu.darken.amply.stats.core

import android.content.Context
import androidx.sqlite.db.SimpleSQLiteQuery
import dagger.Lazy
import dagger.hilt.android.qualifiers.ApplicationContext
import eu.darken.amply.common.debug.logging.Logging
import eu.darken.amply.common.debug.logging.asLog
import eu.darken.amply.common.debug.logging.log
import eu.darken.amply.common.debug.logging.logTag
import eu.darken.amply.stats.core.db.StatsDatabase
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * How much space the recorded history takes up in `stats.db`, for the charging-history settings.
 *
 * Counted as `(page_count - freelist_count) * page_size`, not as the file size: SQLite keeps freed
 * pages in the file and nothing here vacuums, so the file would never shrink after a purge or a clear.
 * Removing a few rows from a partly filled page frees no page, so small deletes may not move the value.
 */
@Singleton
class StatsStorageUsage internal constructor(
    private val context: Context,
    private val database: Lazy<StatsDatabase>,
    private val dispatcher: CoroutineDispatcher,
    private val pollIntervalMillis: Long,
) {
    @Inject constructor(
        @ApplicationContext context: Context,
        database: Lazy<StatsDatabase>,
        @StatsDispatcher dispatcher: CoroutineDispatcher,
    ) : this(context, database, dispatcher, EXISTENCE_POLL_MILLIS)

    /**
     * Bytes used by the history, or null while there is none on disk or it can't be read.
     *
     * Room is only opened once the file exists, because opening it would create the file. Until then
     * this polls for the file with a plain stat, for as long as it is collected.
     */
    fun historyStorageBytes(): Flow<Long?> = flow {
        while (!databaseExists()) {
            emit(null)
            delay(pollIntervalMillis)
        }
        val db = database.get()
        emitAll(
            db.invalidationTracker
                .createFlow(TABLE_SESSIONS, TABLE_SAMPLES)
                .map { db.usedBytesOrNull() },
        )
    }
        .catch { e ->
            log(TAG, Logging.Priority.WARN) { "History storage flow failed: ${e.asLog()}" }
            emit(null)
        }
        .distinctUntilChanged()
        .flowOn(dispatcher)

    private fun databaseExists(): Boolean =
        runCatching { context.getDatabasePath(StatsDatabase.NAME).exists() }.getOrDefault(false)

    private fun StatsDatabase.usedBytesOrNull(): Long? = try {
        (pragma("page_count") - pragma("freelist_count")) * pragma("page_size")
    } catch (e: Exception) {
        log(TAG, Logging.Priority.WARN) { "History storage read failed: ${e.asLog()}" }
        null
    }

    private fun StatsDatabase.pragma(name: String): Long =
        query(SimpleSQLiteQuery("PRAGMA $name")).use { cursor ->
            check(cursor.moveToFirst()) { "PRAGMA $name returned no row" }
            cursor.getLong(0)
        }

    private companion object {
        val TAG = logTag("Stats", "Storage")
        const val EXISTENCE_POLL_MILLIS = 5_000L
        const val TABLE_SESSIONS = "charge_sessions"
        const val TABLE_SAMPLES = "battery_samples"
    }
}
