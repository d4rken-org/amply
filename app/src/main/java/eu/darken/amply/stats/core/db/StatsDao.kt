package eu.darken.amply.stats.core.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface StatsDao {

    // --- charge_sessions ---

    @Insert
    suspend fun insertSession(session: ChargeSessionEntity): Long

    @Update
    suspend fun updateSession(session: ChargeSessionEntity)

    /**
     * Open sessions, newest first. Normally at most one exists; the recorder reconciles extras
     * (sealing all but the newest) rather than assuming a single row, so a crash that left two open
     * can never wedge capture.
     */
    @Query("SELECT * FROM charge_sessions WHERE endedAtWallMillis IS NULL ORDER BY startedElapsedRealtimeMillis DESC")
    suspend fun openSessions(): List<ChargeSessionEntity>

    /**
     * The single newest open session of the CURRENT boot as a live flow, for the dashboard's
     * current-charge card. Restricted to [bootId] so a dangling row left open by a pre-reboot crash
     * can't be shown as "charging now" in the window before startup repair seals it. Ordered by
     * [ChargeSessionEntity.id] (monotonic autoincrement), NOT elapsed-realtime, which resets on reboot.
     */
    @Query("SELECT * FROM charge_sessions WHERE endedAtWallMillis IS NULL AND bootId = :bootId ORDER BY id DESC LIMIT 1")
    fun openSessionFlow(bootId: Long): Flow<ChargeSessionEntity?>

    @Query("SELECT * FROM charge_sessions WHERE id = :id")
    suspend fun sessionById(id: Long): ChargeSessionEntity?

    /** Finished sessions for the history list, newest first. */
    @Query("SELECT * FROM charge_sessions WHERE endedAtWallMillis IS NOT NULL ORDER BY startedAtWallMillis DESC LIMIT :limit")
    fun finishedSessions(limit: Int): Flow<List<ChargeSessionEntity>>

    /** Count of finished sessions, for the dashboard teaser. */
    @Query("SELECT COUNT(*) FROM charge_sessions WHERE endedAtWallMillis IS NOT NULL")
    fun finishedSessionCount(): Flow<Int>

    @Query("SELECT * FROM charge_sessions WHERE id = :id")
    fun sessionFlow(id: Long): Flow<ChargeSessionEntity?>

    /** Delete one session; its [BatterySampleEntity] rows cascade (FK onDelete=CASCADE). */
    @Query("DELETE FROM charge_sessions WHERE id = :id")
    suspend fun deleteSession(id: Long)

    @Query("DELETE FROM charge_sessions")
    suspend fun deleteAllSessions()

    // --- battery_samples ---

    @Insert
    suspend fun insertSample(sample: BatterySampleEntity): Long

    @Query("SELECT * FROM battery_samples WHERE sessionId = :sessionId ORDER BY elapsedRealtimeMillis ASC")
    fun samplesForSession(sessionId: Long): Flow<List<BatterySampleEntity>>

    /**
     * The most recent [limit] samples of a session (re-sorted ascending), as a live flow for the
     * dashboard's compact current-charge curve. Bounded on purpose: an open session can last days at
     * an OEM charge limit, so an unbounded reload on every append would grow O(n). The detail screen
     * still reads the full curve via [samplesForSessionNow].
     */
    @Query(
        """
        SELECT * FROM (
            SELECT * FROM battery_samples WHERE sessionId = :sessionId
            ORDER BY elapsedRealtimeMillis DESC LIMIT :limit
        ) ORDER BY elapsedRealtimeMillis ASC
        """,
    )
    fun recentSamplesForSession(sessionId: Long, limit: Int): Flow<List<BatterySampleEntity>>

    /** Raw samples for a session as a plain list (curve rendering decimates in memory). */
    @Query("SELECT * FROM battery_samples WHERE sessionId = :sessionId ORDER BY elapsedRealtimeMillis ASC")
    suspend fun samplesForSessionNow(sessionId: Long): List<BatterySampleEntity>

    /** Retention: drop raw samples older than a cutoff whose session is already closed. */
    @Query(
        """
        DELETE FROM battery_samples
        WHERE wallMillis < :cutoffWallMillis
        AND sessionId IN (SELECT id FROM charge_sessions WHERE endedAtWallMillis IS NOT NULL)
        """,
    )
    suspend fun deleteSamplesOlderThan(cutoffWallMillis: Long): Int

    @Query("DELETE FROM battery_samples")
    suspend fun deleteAllSamples()
}
